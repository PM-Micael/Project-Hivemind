package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.BlockActionPayload;
import com.projecthivemind.network.SyncActionsPayload;
import com.projecthivemind.network.WeakToolPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the player can tell units to do to a block: walk to it (any unit), or dig it or interact with it (workers
 * only), or cancel what they are doing there. Every request is checked here; the client's list of units is never
 * trusted.
 */
public final class HiveActions {
    /** The identity the fake player uses when a unit right-clicks a block. */
    public static final GameProfile FAKE_PROFILE = new GameProfile(UUID.fromString("5b9fd6b8-3f0c-4a46-8d58-0f6e3a3f9c11"), "[Hive Unit]");

    /** Spacing between units sent to the same spot, so a group fans out instead of piling up. */
    private static final double FORMATION_SPACING = 1.1D;

    private HiveActions() {
    }

    public static void handle(ServerPlayer player, BlockActionPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = request.pos();
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            return;
        }

        switch (request.action()) {
            case WALK_TO -> walkTo(player, level, request.unitIds(), pos);
            case DIG -> dig(player, level, heart, request.unitIds(), pos, request.confirmed());
            case INTERACT -> interact(player, level, request.unitIds(), pos);
            case CANCEL -> cancel(player, level, heart, pos);
        }
        syncActions(player, heart);
    }

    // ---- the actions ----

    /** Each unit walks to stand on top of the block. A group fans out on a ring around the spot. */
    private static void walkTo(ServerPlayer player, ServerLevel level, List<Integer> ids, BlockPos pos) {
        List<Mob> units = commandable(player, level, ids, false);
        Vec3 target = Vec3.atBottomCenterOf(pos.above());
        for (int i = 0; i < units.size(); i++) {
            double angle = units.size() == 1 ? 0.0D : i * (2.0D * Math.PI / units.size());
            double radius = units.size() == 1 ? 0.0D : FORMATION_SPACING * Math.max(1.0D, units.size() / 4.0D);
            Mob unit = units.get(i);
            ((HiveUnit) unit).setAction(null);
            boolean pathFound = unit.getNavigation().moveTo(target.x + Math.cos(angle) * radius, target.y, target.z + Math.sin(angle) * radius, 1.0D);
            if (pathFound) {
                ((HiveUnit) unit).setAction(new UnitAction(UnitAction.Kind.WALK, pos));
            }
        }
    }

    /**
     * Workers dig the block. If none of the hive's tools can harvest it, the player is asked first (once); digging
     * anyway still breaks the block, it just drops nothing.
     */
    private static void dig(ServerPlayer player, ServerLevel level, HiveHeart heart, List<Integer> ids, BlockPos pos, boolean confirmed) {
        List<Mob> workers = commandable(player, level, ids, true);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getDestroySpeed(level, pos) < 0.0F) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.cannot_dig"), true);
            return;
        }
        if (!confirmed && !toolsCanHarvest(heart, state)) {
            PacketDistributor.sendToPlayer(player, new WeakToolPayload(ids, pos));
            return;
        }
        for (Mob worker : workers) {
            worker.getNavigation().stop();
            ((HiveUnit) worker).setAction(new UnitAction(UnitAction.Kind.DIG, pos));
        }
    }

    /** Workers go and right-click the block once. Like digging, this is worker-only. */
    private static void interact(ServerPlayer player, ServerLevel level, List<Integer> ids, BlockPos pos) {
        List<Mob> workers = commandable(player, level, ids, true);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        for (Mob worker : workers) {
            worker.getNavigation().stop();
            ((HiveUnit) worker).setAction(new UnitAction(UnitAction.Kind.INTERACT, pos));
        }
    }

    /** Stop every one of the player's units that is doing something to this block, selected or not. */
    private static void cancel(ServerPlayer player, ServerLevel level, HiveHeart heart, BlockPos pos) {
        for (UUID id : HivemindManager.get(player).allUnits()) {
            if (level.getEntity(id) instanceof Mob mob && mob instanceof HiveUnit unit
                    && unit.action() != null && unit.action().pos().equals(pos)) {
                unit.setAction(null);
                mob.getNavigation().stop();
            }
        }
        heart.clearDigProgress(pos);
    }

    // ---- helpers ----

    /** True if any tool in the hive's slots would make this block drop its items (or none is needed). */
    public static boolean toolsCanHarvest(HiveHeart heart, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int i = 0; i < HiveEquipment.TOOL_SLOTS; i++) {
            ItemStack tool = heart.getToolGear().getItem(i);
            if (!tool.isEmpty() && tool.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /** The player's own units among these entity ids that can take orders: never collectors, and optionally only workers. */
    private static List<Mob> commandable(ServerPlayer player, ServerLevel level, List<Integer> ids, boolean workersOnly) {
        List<Mob> units = new ArrayList<>();
        for (int id : ids) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                    && player.getUUID().equals(unit.ownerId()) && unit.kind() != UnitKind.COLLECTOR
                    && (!workersOnly || unit.kind() == UnitKind.WORKER)) {
                units.add(mob);
            }
        }
        return units;
    }

    // ---- telling the client where units are working ----

    /** The blocks any of the player's units currently have an action on. */
    private static Set<BlockPos> actionPositions(ServerPlayer player, ServerLevel level) {
        Set<BlockPos> positions = new HashSet<>();
        for (UUID id : HivemindManager.get(player).allUnits()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof HiveUnit unit && unit.action() != null && positions.size() < SyncActionsPayload.MAX_POSITIONS) {
                positions.add(unit.action().pos().immutable());
            }
        }
        return positions;
    }

    /** Tell the player which blocks have work on them, but only when that has changed since last time. */
    public static void syncActions(ServerPlayer player, HiveHeart heart) {
        Set<BlockPos> positions = actionPositions(player, player.serverLevel());
        if (!positions.equals(heart.syncedActions())) {
            heart.setSyncedActions(positions);
            PacketDistributor.sendToPlayer(player, new SyncActionsPayload(List.copyOf(positions)));
        }
    }
}
