package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.BlockActionPayload;
import com.projecthivemind.network.MobActionPayload;
import com.projecthivemind.network.SyncActionsPayload;
import com.projecthivemind.network.WeakToolPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the player can tell units to do. To a block: walk to it (any unit), dig it or interact with it (workers only).
 * To a mob: attack it (soldiers only). Either can be cancelled. Every request is checked here; the client's list of
 * units is never trusted.
 */
public final class HiveActions {
    /** The identity the fake player uses when a unit right-clicks a block. */
    public static final GameProfile FAKE_PROFILE = new GameProfile(UUID.fromString("5b9fd6b8-3f0c-4a46-8d58-0f6e3a3f9c11"), "[Hive Unit]");

    /** Spacing between units sent to the same spot, so a group fans out instead of piling up. */
    private static final double FORMATION_SPACING = 1.1D;

    /** What the owner's client was last told: the blocks with work on them and the mobs under attack. */
    public record Snapshot(Set<BlockPos> blocks, Set<Integer> mobs) {
        public static final Snapshot EMPTY = new Snapshot(Set.of(), Set.of());
    }

    private HiveActions() {
    }

    // ---- orders about a block ----

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
            case CANCEL -> cancelBlock(player, level, heart, pos);
        }
        syncActions(player, heart);
    }

    /** Each unit walks to stand on top of the block. A group fans out on a ring around the spot. */
    private static void walkTo(ServerPlayer player, ServerLevel level, List<Integer> ids, BlockPos pos) {
        List<Mob> units = commandable(player, level, ids, null);
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
        List<Mob> workers = commandable(player, level, ids, UnitKind.WORKER);
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

    /**
     * Workers go and right-click the block once. Scouts go and open its inventory for the player, if it has one.
     * Anything else selected is not asked to do anything.
     */
    private static void interact(ServerPlayer player, ServerLevel level, List<Integer> ids, BlockPos pos) {
        List<Mob> workers = commandable(player, level, ids, UnitKind.WORKER);
        List<Mob> scouts = commandable(player, level, ids, UnitKind.SCOUT);
        if (workers.isEmpty() && scouts.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_interactors"), true);
            return;
        }
        for (Mob worker : workers) {
            worker.getNavigation().stop();
            ((HiveUnit) worker).setAction(new UnitAction(UnitAction.Kind.INTERACT, pos));
        }
        if (!scouts.isEmpty() && !HiveAccess.canOpen(level, pos)) {
            // Only worth a message when there is no worker to do something with the block instead.
            if (workers.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.projecthivemind.cannot_open"), true);
            }
            return;
        }
        // One scout is enough: two would only fight over the one screen.
        for (Mob scout : scouts) {
            scout.getNavigation().stop();
            ((HiveUnit) scout).setAction(new UnitAction(UnitAction.Kind.INTERACT, pos));
            break;
        }
    }

    /** Stop every one of the player's units that is doing something to this block, selected or not. */
    private static void cancelBlock(ServerPlayer player, ServerLevel level, HiveHeart heart, BlockPos pos) {
        for (UUID id : HivemindManager.get(player).allUnits()) {
            if (level.getEntity(id) instanceof Mob mob && mob instanceof HiveUnit unit
                    && unit.action() != null && pos.equals(unit.action().pos())) {
                unit.setAction(null);
                mob.getNavigation().stop();
            }
        }
        heart.clearDigProgress(pos);
    }

    // ---- orders about a mob ----

    public static void handleMob(ServerPlayer player, MobActionPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !(level.getEntity(request.targetId()) instanceof Mob target)) {
            return;
        }

        switch (request.action()) {
            case ATTACK -> attack(player, level, request.unitIds(), target);
            case TRADE -> trade(player, level, request.unitIds(), target);
            case CANCEL -> cancelMob(player, level, target);
        }
        syncActions(player, heart);
    }

    /**
     * Soldiers attack the mob and keep at it until it dies or the order is cancelled. Your own units and Hive Hearts
     * are not valid targets.
     */
    private static void attack(ServerPlayer player, ServerLevel level, List<Integer> ids, Mob target) {
        if (!target.isAlive() || target instanceof HiveHeart
                || (target instanceof HiveUnit unit && player.getUUID().equals(unit.ownerId()))) {
            return;
        }
        List<Mob> soldiers = commandable(player, level, ids, UnitKind.SOLDIER);
        if (soldiers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_soldiers"), true);
            return;
        }
        for (Mob soldier : soldiers) {
            soldier.getNavigation().stop();
            ((HiveUnit) soldier).setAction(UnitAction.attack(target.getUUID()));
        }
    }

    /** A scout walks up to the villager and opens its trades. */
    private static void trade(ServerPlayer player, ServerLevel level, List<Integer> ids, Mob target) {
        if (!(target instanceof AbstractVillager villager) || !villager.isAlive()) {
            return;
        }
        List<Mob> scouts = commandable(player, level, ids, UnitKind.SCOUT);
        if (scouts.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_scouts"), true);
            return;
        }
        if (!HiveAccess.canTrade(villager)) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.cannot_trade"), true);
            return;
        }
        // One scout is enough: two would only fight over the one screen.
        for (Mob scout : scouts) {
            scout.getNavigation().stop();
            ((HiveUnit) scout).setAction(UnitAction.trade(villager.getUUID()));
            break;
        }
    }

    /** Stop every one of the player's units that is attacking this mob, selected or not. */
    private static void cancelMob(ServerPlayer player, ServerLevel level, Mob target) {
        for (UUID id : HivemindManager.get(player).allUnits()) {
            if (level.getEntity(id) instanceof Mob mob && mob instanceof HiveUnit unit
                    && unit.action() != null && target.getUUID().equals(unit.action().target())) {
                unit.setAction(null);
                mob.getNavigation().stop();
            }
        }
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

    /** The player's own units among these entity ids that can take orders: never collectors, and optionally only one kind. */
    private static List<Mob> commandable(ServerPlayer player, ServerLevel level, List<Integer> ids, @Nullable UnitKind only) {
        List<Mob> units = new ArrayList<>();
        for (int id : ids) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                    && player.getUUID().equals(unit.ownerId()) && unit.kind() != UnitKind.COLLECTOR
                    && (only == null || unit.kind() == only)) {
                units.add(mob);
            }
        }
        return units;
    }

    // ---- telling the client what units are working on ----

    private static Snapshot snapshot(ServerPlayer player, ServerLevel level) {
        Set<BlockPos> blocks = new HashSet<>();
        Set<Integer> mobs = new HashSet<>();
        for (UUID id : HivemindManager.get(player).allUnits()) {
            if (!(level.getEntity(id) instanceof HiveUnit unit) || unit.action() == null) {
                continue;
            }
            UnitAction action = unit.action();
            if (action.pos() != null && blocks.size() < SyncActionsPayload.MAX_ENTRIES) {
                blocks.add(action.pos().immutable());
            }
            if (action.target() != null && mobs.size() < SyncActionsPayload.MAX_ENTRIES
                    && level.getEntity(action.target()) instanceof Mob target) {
                mobs.add(target.getId());
            }
        }
        return new Snapshot(blocks, mobs);
    }

    /** Tell the player what is being worked on, but only when that has changed since last time. */
    public static void syncActions(ServerPlayer player, HiveHeart heart) {
        Snapshot now = snapshot(player, player.serverLevel());
        if (!now.equals(heart.syncedActions())) {
            heart.setSyncedActions(now);
            PacketDistributor.sendToPlayer(player, new SyncActionsPayload(List.copyOf(now.blocks()), List.copyOf(now.mobs())));
        }
    }
}
