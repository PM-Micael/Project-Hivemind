package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.projecthivemind.build.TowerBuild;
import com.projecthivemind.build.TowerDirection;
import com.projecthivemind.build.TowerMaterial;
import com.projecthivemind.build.TowerPlan;
import com.projecthivemind.build.TowerSet;
import com.projecthivemind.build.TowerShape;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.BlockActionPayload;
import com.projecthivemind.network.BuildTowerPayload;
import com.projecthivemind.network.DigStaircasePayload;
import com.projecthivemind.build.StairDig;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.network.DropItemPayload;
import com.projecthivemind.network.ScoutUsePayload;
import com.projecthivemind.network.MobActionPayload;
import com.projecthivemind.network.SyncActionsPayload;
import com.projecthivemind.network.WeakToolPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
            case REPEAT_DIG -> repeatDig(player, level, request.unitIds(), pos);
            case TILL -> till(player, level, heart, request.unitIds(), pos);
        }
        syncActions(player, heart);
    }

    /**
     * A unit goes back to the hive: it drops whatever it was doing and walks to the nearest point inside the border (a unit that is
     * already inside just stops). Any of the player's own units, whatever its kind.
     */
    public static void returnToBase(ServerPlayer player, int unitId) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !(HivemindManager.findById(player, unitId) instanceof Mob mob) || !mob.isAlive()
                || !(mob instanceof HiveUnit unit) || !player.getUUID().equals(unit.ownerId())) {
            return;
        }
        if (isTeamFollower(player, mob)) {
            return;
        }
        unit.setAction(null);
        mob.getNavigation().stop();
        if (HiveArea.containsXZ(heart, mob.getX(), mob.getZ())) {
            return;
        }
        Vec3 inside = HiveArea.nearestInside(heart, mob.getX(), mob.getZ());
        if (mob.getNavigation().moveTo(inside.x, inside.y, inside.z, 1.2D)) {
            unit.setAction(new UnitAction(UnitAction.Kind.WALK, BlockPos.containing(inside).below()));
        }
    }

    /**
     * A team's scout was ordered to do something: the rest of the team does the same, if it can. A dig order is taken up by the
     * team's workers, an attack order by its soldiers. (Anything else the scout does is for the scout alone.) Followers cannot be
     * selected, so this is how the player commands them.
     */
    private static void mirrorToTeam(ServerPlayer player, ServerLevel level, Mob scout, UnitAction action) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !(scout instanceof HiveUnit scoutUnit) || scoutUnit.kind() != UnitKind.SCOUT) {
            return;
        }
        int team = heart.teams().teamOf(scout.getUUID());
        if (team < 0) {
            return;
        }
        UnitKind capable = action.kind() == UnitAction.Kind.DIG ? UnitKind.WORKER : action.kind() == UnitAction.Kind.ATTACK ? UnitKind.SOLDIER : null;
        if (capable == null) {
            return;
        }
        for (java.util.UUID id : heart.teams().members(team)) {
            if (level.getEntity(id) instanceof Mob member && member.isAlive() && member != scout && member instanceof HiveUnit unit
                    && unit.kind() == capable && player.getUUID().equals(unit.ownerId())) {
                member.getNavigation().stop();
                unit.setAction(action);
            }
        }
    }

    /** True for a worker or a soldier in a team that has a scout: those follow the scout, and the player cannot move them. */
    private static boolean isTeamFollower(ServerPlayer player, Mob mob) {
        HiveHeart heart = HivemindManager.findHeart(player);
        return heart != null && mob instanceof HiveUnit unit && (unit.kind() == UnitKind.WORKER || unit.kind() == UnitKind.SOLDIER)
                && heart.teamLeader(mob) != null;
    }

    /** Each unit walks to stand on top of the block. A group fans out on a ring around the spot. */
    private static void walkTo(ServerPlayer player, ServerLevel level, List<Integer> ids, BlockPos pos) {
        List<Mob> units = commandable(player, level, ids, null);
        // Followers in a team cannot be walked about by the player: they stay with the team's scout.
        units.removeIf(unit -> isTeamFollower(player, unit));
        Vec3 target = Vec3.atBottomCenterOf(pos.above());
        for (int i = 0; i < units.size(); i++) {
            double angle = units.size() == 1 ? 0.0D : i * (2.0D * Math.PI / units.size());
            double radius = units.size() == 1 ? 0.0D : FORMATION_SPACING * Math.max(1.0D, units.size() / 4.0D);
            Mob unit = units.get(i);

            boolean pathFound = unit.getNavigation().moveTo(target.x + Math.cos(angle) * radius, target.y, target.z + Math.sin(angle) * radius, 1.0D);
            if (pathFound) {
                ((HiveUnit) unit).setAction(new UnitAction(UnitAction.Kind.WALK, pos));
            }
        }
    }

    /**
     * Workers build a bridge from where the nearest of them stands to the block clicked. Checked here: the items are a plain full block
     * (and a fence, if one is asked for), the block is loaded and not too far, and the slope can be walked.
     */
    public static void buildBridge(ServerPlayer player, com.projecthivemind.network.BuildBridgePayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos dest = request.dest();
        if (HivemindManager.findHeart(player) == null || !level.isInWorldBounds(dest) || !level.isLoaded(dest)) {
            return;
        }
        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        net.minecraft.world.item.Item deck = itemOf(request.deck());
        net.minecraft.world.item.Item fence = request.fence().isEmpty() ? null : itemOf(request.fence());
        if (deck == null || HiveWorker.fillBlock(deck) == null || (!request.fence().isEmpty() && (fence == null || HiveWorker.fenceBlock(fence) == null))) {
            return;
        }
        // It starts from the block under the nearest worker.
        Vec3 target = Vec3.atCenterOf(dest);
        Mob nearest = workers.stream().min(java.util.Comparator.comparingDouble(worker -> worker.distanceToSqr(target))).orElseThrow();
        BlockPos start = BlockPos.containing(nearest.getX(), Math.floor(nearest.getY()) - 1.0D, nearest.getZ());
        com.projecthivemind.build.BridgeJob job = new com.projecthivemind.build.BridgeJob(start, dest.immutable(), deck, fence, request.torches(), request.width());
        if (job.length() > com.projecthivemind.build.BridgeJob.MAX_LENGTH) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.bridge_too_long", com.projecthivemind.build.BridgeJob.MAX_LENGTH), true);
            return;
        }
        if (!job.walkable()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.bridge_too_steep"), true);
            return;
        }
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart.constructions().all().size() >= com.projecthivemind.build.Constructions.MAX) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_limit", com.projecthivemind.build.Constructions.MAX), true);
            return;
        }
        // The block that marks the bridge goes behind where it starts, off the deck.
        java.util.Set<Long> reserved = new java.util.HashSet<>();
        for (com.projecthivemind.build.BridgeJob.Placement placement : job.placements()) {
            reserved.add(HiveConstructions.columnKey(placement.pos().getX(), placement.pos().getZ()));
        }
        BlockPos anchor = HiveConstructions.findAnchor(level, heart, start.getX() - Integer.signum(dest.getX() - start.getX()) * 3,
                start.getZ() - Integer.signum(dest.getZ() - start.getZ()) * 3, reserved);
        com.projecthivemind.build.Construction construction = com.projecthivemind.build.Construction.ofBridge(level.dimension(), anchor, job);
        HiveConstructions.place(level, heart, construction);
        HiveConstructions.assign(player, heart, construction, nearest(workers, Vec3.atCenterOf(dest), construction.maxWorkers()));
        player.displayClientMessage(Component.translatable("message.projecthivemind.bridge_started"), true);
    }

    /**
     * Workers build a small cobblestone generator on top of the block clicked: a lava source over a spot of stone, a water source beside it, walled in
     * with cobblestone (see BridgeJob#generator). The site must be solid ground with room for it; the lava, water and cobblestone come from the hive.
     */
    public static void buildGenerator(ServerPlayer player, com.projecthivemind.network.BuildGeneratorPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos site = request.site();
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !level.isInWorldBounds(site) || !level.isLoaded(site)) {
            return;
        }
        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        // The row of four cells runs from the block clicked in the first direction where it fits.
        Direction along = null;
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (generatorFits(level, site, candidate)) {
                along = candidate;
                break;
            }
        }
        if (along == null) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.generator_bad_site"), true);
            return;
        }
        if (heart.constructions().all().size() >= com.projecthivemind.build.Constructions.MAX) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_limit", com.projecthivemind.build.Constructions.MAX), true);
            return;
        }
        com.projecthivemind.build.BridgeJob job = com.projecthivemind.build.BridgeJob.generator(site.immutable(), net.minecraft.world.item.Items.COBBLESTONE, along);
        java.util.Set<Long> reserved = new java.util.HashSet<>();
        for (com.projecthivemind.build.BridgeJob.Placement placement : job.placements()) {
            reserved.add(HiveConstructions.columnKey(placement.pos().getX(), placement.pos().getZ()));
        }
        BlockPos anchor = HiveConstructions.findAnchor(level, heart, site.getX() + 5, site.getZ() + 4, reserved);
        com.projecthivemind.build.Construction construction = com.projecthivemind.build.Construction.ofBridge(level.dimension(), anchor, job);
        HiveConstructions.place(level, heart, construction);
        HiveConstructions.assign(player, heart, construction, nearest(workers, Vec3.atCenterOf(site), construction.maxWorkers()));
        player.displayClientMessage(Component.translatable("message.projecthivemind.generator_started"), true);
    }

    /**
     * Workers build the smallest nether portal there is on the block clicked (a frame of ten obsidian round an opening two wide and three high, see
     * BridgeJob#portal) and light it with a flint and steel. The obsidian and the flint and steel come from the hive. Only where a portal can be lit:
     * the Overworld and the Nether.
     */
    public static void buildNetherPortal(ServerPlayer player, com.projecthivemind.network.BuildNetherPortalPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos site = request.site();
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !level.isInWorldBounds(site) || !level.isLoaded(site)) {
            return;
        }
        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD && level.dimension() != net.minecraft.world.level.Level.NETHER) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.nether_portal_bad_dimension"), true);
            return;
        }
        // The frame runs along the first axis where it fits.
        Direction along = null;
        for (Direction candidate : new Direction[] {Direction.EAST, Direction.SOUTH}) {
            if (netherPortalFits(level, site, candidate)) {
                along = candidate;
                break;
            }
        }
        if (along == null) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.nether_portal_bad_site"), true);
            return;
        }
        if (heart.constructions().all().size() >= com.projecthivemind.build.Constructions.MAX) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_limit", com.projecthivemind.build.Constructions.MAX), true);
            return;
        }
        com.projecthivemind.build.BridgeJob job = com.projecthivemind.build.BridgeJob.portal(site.immutable(), along);
        java.util.Set<Long> reserved = new java.util.HashSet<>();
        for (com.projecthivemind.build.BridgeJob.Placement placement : job.placements()) {
            reserved.add(HiveConstructions.columnKey(placement.pos().getX(), placement.pos().getZ()));
        }
        Direction facing = along.getClockWise();
        BlockPos anchor = HiveConstructions.findAnchor(level, heart, site.getX() + facing.getStepX() * 4, site.getZ() + facing.getStepZ() * 4, reserved);
        com.projecthivemind.build.Construction construction = com.projecthivemind.build.Construction.ofBridge(level.dimension(), anchor, job);
        HiveConstructions.place(level, heart, construction);
        HiveConstructions.assign(player, heart, construction, nearest(workers, Vec3.atCenterOf(site), 1));
        player.displayClientMessage(Component.translatable("message.projecthivemind.nether_portal_started"), true);
    }

    /**
     * True if the portal's frame and opening can go on this block, running in this direction: the block is solid and so is the one beside it (what
     * the bottom edge rests on), and each place for the frame and the opening is loaded, in the world, free of liquid, and either empty or something
     * a worker can dig out.
     */
    private static boolean netherPortalFits(ServerLevel level, BlockPos site, Direction along) {
        com.projecthivemind.build.BridgeJob job = com.projecthivemind.build.BridgeJob.portal(site, along);
        for (BlockPos ground : new BlockPos[] {site, site.relative(along)}) {
            if (!level.isLoaded(ground) || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
                return false;
            }
        }
        java.util.List<BlockPos> cells = new ArrayList<>(job.portalFrame());
        cells.addAll(job.portalOpening());
        for (BlockPos cell : cells) {
            if (!level.isLoaded(cell) || !level.isInWorldBounds(cell)) {
                return false;
            }
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cell);
            if (!state.getFluidState().isEmpty()
                    || !(state.canBeReplaced() || (state.getDestroySpeed(level, cell) >= 0.0F && !state.hasBlockEntity()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Workers dig a tunnel from the block clicked, in the direction and of the size ordered, for its length; where the floor is missing along the
     * way, the chosen block is laid. Checked here: the block is plain and full (it comes from the hive), the size and length are in range, and the
     * start is loaded.
     */
    public static void buildTunnel(ServerPlayer player, com.projecthivemind.network.BuildTunnelPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos site = request.site();
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || !level.isInWorldBounds(site) || !level.isLoaded(site)
                || request.size() < 1 || request.size() > com.projecthivemind.build.BridgeJob.TUNNEL_SIZE_COUNT
                || request.length() < com.projecthivemind.build.BridgeJob.TUNNEL_MIN_LENGTH || request.length() > com.projecthivemind.build.BridgeJob.TUNNEL_MAX_LENGTH) {
            return;
        }
        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        net.minecraft.world.item.Item block = itemOf(request.block());
        if (block == null || HiveWorker.fillBlock(block) == null) {
            return;
        }
        if (heart.constructions().all().size() >= com.projecthivemind.build.Constructions.MAX) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_limit", com.projecthivemind.build.Constructions.MAX), true);
            return;
        }
        Direction along = Direction.from2DDataValue(request.direction() & 3);
        BlockPos end = site.relative(along, request.length() - 1);
        if (!level.isInWorldBounds(end)) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.tunnel_out_of_world"), true);
            return;
        }
        com.projecthivemind.build.BridgeJob job = com.projecthivemind.build.BridgeJob.tunnel(site.immutable(), along, request.length(), block, request.size());
        java.util.Set<Long> reserved = new java.util.HashSet<>();
        for (com.projecthivemind.build.BridgeJob.Placement placement : job.placements()) {
            reserved.add(HiveConstructions.columnKey(placement.pos().getX(), placement.pos().getZ()));
        }
        // The construction block goes behind where the tunnel starts, off its path.
        BlockPos behind = site.relative(along.getOpposite(), 3);
        BlockPos anchor = HiveConstructions.findAnchor(level, heart, behind.getX(), behind.getZ(), reserved);
        com.projecthivemind.build.Construction construction = com.projecthivemind.build.Construction.ofBridge(level.dimension(), anchor, job);
        HiveConstructions.place(level, heart, construction);
        HiveConstructions.assign(player, heart, construction, nearest(workers, Vec3.atCenterOf(site), construction.maxWorkers()));
        player.displayClientMessage(Component.translatable("message.projecthivemind.tunnel_started"), true);
    }

    /** True if a generator's row of four cells can go from this block in this direction: each is solid and diggable, with free air above and solid ground under it. */
    private static boolean generatorFits(ServerLevel level, BlockPos site, Direction along) {
        for (int i = 0; i < 4; i++) {
            BlockPos cell = site.relative(along, i);
            if (!level.isLoaded(cell) || !level.isInWorldBounds(cell.below(2)) || !level.isInWorldBounds(cell.above(2))) {
                return false;
            }
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cell);
            if (!state.isSolid() || state.getDestroySpeed(level, cell) < 0.0F || !state.getFluidState().isEmpty()
                    || !level.getBlockState(cell.above()).canBeReplaced() || !level.getBlockState(cell.below()).isSolid()) {
                return false;
            }
        }
        return true;
    }

    /** The workers nearest to the point, no more than the limit. */
    private static List<Mob> nearest(List<Mob> workers, Vec3 point, int limit) {
        return workers.stream().sorted(java.util.Comparator.comparingDouble(worker -> worker.distanceToSqr(point))).limit(limit).toList();
    }

    @Nullable
    private static net.minecraft.world.item.Item itemOf(String name) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(name);
        return id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** Selected workers place a torch from the hive against the face of the block that was clicked (not the underside). */
    public static void placeTorch(ServerPlayer player, com.projecthivemind.network.PlaceTorchPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        HiveHeart heart = HivemindManager.findHeart(player);
        BlockPos pos = request.pos();
        if (heart == null || request.face() == Direction.DOWN || !level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            return;
        }
        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        if (heart.getStorage().countItem(net.minecraft.world.item.Items.TORCH) <= 0) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_torches"), true);
            return;
        }
        // One worker is enough for one torch: the nearest.
        Vec3 spot = Vec3.atCenterOf(pos.relative(request.face()));
        Mob nearest = workers.stream().min(java.util.Comparator.comparingDouble(worker -> worker.distanceToSqr(spot))).orElseThrow();
        nearest.getNavigation().stop();
        ((HiveUnit) nearest).setAction(UnitAction.torch(pos, request.face()));
    }

    /**
     * Workers and scouts dig the block (scouts use the hive's tools too). If none of the hive's tools can harvest it, the player is asked first (once); digging
     * anyway still breaks the block, it just drops nothing.
     */
    private static void dig(ServerPlayer player, ServerLevel level, HiveHeart heart, List<Integer> ids, BlockPos pos, boolean confirmed) {
        List<Mob> workers = commandable(player, level, ids, UnitKind.WORKER);
        // Scouts dig too, with whatever they hold: they do not use the hive's tools.
        List<Mob> diggers = new ArrayList<>(workers);
        diggers.addAll(commandable(player, level, ids, UnitKind.SCOUT));
        if (diggers.isEmpty()) {
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
        for (Mob worker : diggers) {
            worker.getNavigation().stop();
            ((HiveUnit) worker).setAction(new UnitAction(UnitAction.Kind.DIG, pos));
            mirrorToTeam(player, level, worker, new UnitAction(UnitAction.Kind.DIG, pos));
        }
    }

    /**
     * Workers go and right-click the block once. Scouts go and open its inventory for the player, if it has one.
     * Anything else selected is not asked to do anything.
     */
    /** A feeder hoes the block, inside the hive border only: the same as a right click on its top with a hoe. */
    private static void till(ServerPlayer player, ServerLevel level, HiveHeart heart, List<Integer> ids, BlockPos pos) {
        com.projecthivemind.entity.HiveFeeder feeder = null;
        for (int id : ids) {
            if (HivemindManager.findById(player, id) instanceof com.projecthivemind.entity.HiveFeeder found && found.isAlive()
                    && player.getUUID().equals(found.ownerId())) {
                feeder = found;
                break;
            }
        }
        if (feeder == null) {
            return;
        }
        if (!HiveArea.containsXZ(heart, pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.plant_outside"), true);
            return;
        }
        // The feeder flies over and hoes the block when it gets there (see FeederTillGoal).
        feeder.orderTill(pos);
    }

    /**
     * Hoe the block now, as a player's right click on its top would. Returns null if it worked, otherwise what to tell the player.
     * Short grass, ferns and the like on top of a grass block stop a hoe (it needs air above), and clicking the plant itself is clicking the
     * plant: the ground is the block below it, and the plant is cleared off it, as a player would do before hoeing.
     */
    @Nullable
    public static Component tillBlock(ServerLevel level, BlockPos pos) {
        if (hoeClick(level, pos)) {
            return null;
        }
        BlockPos ground = pos;
        for (int i = 0; i < 2 && isGroundCover(level, ground); i++) {
            ground = ground.below();
        }
        boolean cleared = false;
        net.minecraft.world.level.block.state.BlockState soil = level.getBlockState(ground);
        boolean tillable = soil.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || soil.is(net.minecraft.world.level.block.Blocks.DIRT)
                || soil.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT) || soil.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                || soil.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT);
        // (Only off soil a hoe can work: plants are not cleared for nothing.)
        for (BlockPos cover = ground.above(); tillable && isGroundCover(level, cover); cover = cover.above()) {
            level.destroyBlock(cover, false);
            cleared = true;
        }
        if ((!ground.equals(pos) || cleared) && hoeClick(level, ground)) {
            return null;
        }
        return Component.translatable("message.projecthivemind.cannot_till_detail",
                level.getBlockState(ground).getBlock().getName(), level.getBlockState(ground.above()).getBlock().getName());
    }

    /**
     * A right click on the top of the block with a (netherite) hoe, made by the hive's fake player. (The player's own entity cannot do it: it is a
     * spectator, and the game does not let one use an item on a block.) True if the hoe did something.
     */
    private static boolean hoeClick(ServerLevel level, BlockPos pos) {
        net.neoforged.neoforge.common.util.FakePlayer fake = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, FAKE_PROFILE);
        fake.moveTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 0.0F, 90.0F);
        fake.setShiftKeyDown(false);
        ItemStack hoe = new ItemStack(net.minecraft.world.item.Items.NETHERITE_HOE);
        fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, hoe);
        net.minecraft.world.phys.Vec3 click = net.minecraft.world.phys.Vec3.atCenterOf(pos).add(0.0D, 0.5D, 0.0D);
        net.minecraft.world.InteractionResult result = fake.gameMode.useItemOn(fake, level, hoe, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(click, Direction.UP, pos, false));
        fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return result.consumesAction();
    }
    /** Plants that give way to a hoe once cleared: short and tall grass, ferns, dead bushes, snow layers and the like. Not water. */
    private static boolean isGroundCover(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.is(net.minecraft.tags.BlockTags.REPLACEABLE) && state.getFluidState().isEmpty();
    }

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

    // ---- building a tower ----

    /**
     * Workers dig a classic staircase down from the block, one block down for every block forward, until it reaches the stop height.
     * They carry on with it whenever they are idle and not selected, and it is the same staircase for every worker given it.
     */
    public static void digStaircase(ServerPlayer player, DigStaircasePayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos start = request.pos();
        if (!level.isInWorldBounds(start)) {
            return;
        }
        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        // A staircase goes through whatever is there, mostly stone: warn once if no tool of the hive can collect it.
        HiveHeart heart = HivemindManager.findHeart(player);
        if (!request.confirmed() && heart != null && !toolsCanHarvest(heart, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())) {
            PacketDistributor.sendToPlayer(player, new com.projecthivemind.network.WeakStairsPayload(request));
            return;
        }
        int stopY = Math.max(level.getMinBuildHeight() + 1, Math.min(request.stopY(), start.getY()));
        StairDig stairs = new StairDig(start.immutable(), Direction.from2DDataValue(request.direction() & 3), stopY, request.torches());
        if (heart == null || heart.constructions().all().size() >= com.projecthivemind.build.Constructions.MAX) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_limit", com.projecthivemind.build.Constructions.MAX), true);
            return;
        }
        // The block that marks the staircase goes behind where it starts.
        Direction dir = stairs.direction();
        BlockPos anchor = HiveConstructions.findAnchor(level, heart, start.getX() - dir.getStepX() * 2, start.getZ() - dir.getStepZ() * 2, java.util.Set.of());
        com.projecthivemind.build.Construction construction = com.projecthivemind.build.Construction.ofStairs(level.dimension(), anchor, stairs);
        HiveConstructions.place(level, heart, construction);
        HiveConstructions.assign(player, heart, construction, nearest(workers, Vec3.atCenterOf(start), construction.maxWorkers()));
    }

    /**
     * Two or more workers build a tower on the block. The order is checked here: enough workers, a real height, a site
     * that is loaded, and some of what the tower is made of in the hive. Ordering again replaces the tower in progress.
     */
    public static void buildTower(ServerPlayer player, BuildTowerPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        HiveHeart heart = HivemindManager.findHeart(player);
        int height = request.height();
        // Any height from the least a build can have up to what the world has room for (the site is checked against the world below).
        if (heart == null || height < TowerPlan.MIN_HEIGHT || height > level.getHeight()) {
            return;
        }
        BlockPos clicked = request.pos();
        // A tower must fit under the top of the world, and a shaft above the bottom of it.
        if (!level.isInWorldBounds(clicked) || (request.direction() == TowerDirection.UP.ordinal() && clicked.getY() + height + 2 >= level.getMaxBuildHeight())
                || (request.direction() == TowerDirection.DOWN.ordinal() && clicked.getY() - height < level.getMinBuildHeight())) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.tower_bad_site"), true);
            return;
        }
        // The whole 7 by 7 has to be loaded: the workers would otherwise build into nothing.
        for (int dx = -3; dx <= 3; dx += 6) {
            for (int dz = -3; dz <= 3; dz += 6) {
                if (!level.isLoaded(clicked.offset(dx, 0, dz))) {
                    player.displayClientMessage(Component.translatable("message.projecthivemind.tower_bad_site"), true);
                    return;
                }
            }
        }

        List<Mob> workers = commandable(player, level, request.unitIds(), UnitKind.WORKER);
        TowerShape shape = TowerShape.byIndex(request.shape());
        if (workers.size() < shape.minWorkers()) {
            player.displayClientMessage(Component.translatable(shape.minWorkers() > 1 ? "message.projecthivemind.tower_needs_workers" : "message.projecthivemind.no_workers"), true);
            return;
        }
        TowerDirection direction = TowerDirection.byIndex(request.direction());
        // A shaft must have room below it, above the bottom of the world.
        if (direction == TowerDirection.DOWN && clicked.getY() - height < level.getMinBuildHeight()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.tower_bad_site"), true);
            return;
        }
        // One worker to a staircase and no more: of the workers selected, the ones nearest the site take the job, and
        // the rest are left alone to do whatever they were doing.
        Vec3 site = Vec3.atCenterOf(clicked);
        workers = workers.stream().sorted(java.util.Comparator.comparingDouble(worker -> worker.distanceToSqr(site)))
                .limit(shape.maxWorkers()).toList();
        TowerPlan plan = new TowerPlan(clicked, shape, direction, height, true, request.torches());
        // Only the bits of the materials that exist count; with none left there is nothing to build from.
        int materials = request.materials() & ((1 << TowerMaterial.values().length) - 1);
        Optional<TowerSet> set = materials == 0 ? Optional.empty()
                : TowerSet.choose(materials, heart.getStorage(), plan.blockCount(), plan.stairCount());
        if (set.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.tower_no_material"), true);
            return;
        }

        if (heart.constructions().all().size() >= com.projecthivemind.build.Constructions.MAX) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_limit", com.projecthivemind.build.Constructions.MAX), true);
            return;
        }
        // The block that marks the tower goes beside it, outside everything the workers will build or dig.
        java.util.Set<Long> reserved = new java.util.HashSet<>();
        for (TowerPlan.Placement placement : plan.placements()) {
            reserved.add(HiveConstructions.columnKey(placement.pos().getX(), placement.pos().getZ()));
        }
        BlockPos anchor = HiveConstructions.findAnchor(level, heart, clicked.getX() + shape.radius() + 3, clicked.getZ(), reserved);
        com.projecthivemind.build.Construction construction = com.projecthivemind.build.Construction.ofTower(level.dimension(), anchor,
                new TowerBuild(plan, set.get()), materials);
        HiveConstructions.place(level, heart, construction);
        HiveConstructions.assign(player, heart, construction, workers);
        syncActions(player, heart);
    }

    /** Stop every one of the player's units that is doing something to this block, selected or not. */
    private static void cancelBlock(ServerPlayer player, ServerLevel level, HiveHeart heart, BlockPos pos) {
        for (UUID id : HivemindManager.get(player).allUnits()) {
            if (level.getEntity(id) instanceof HiveWorker repeater && pos.equals(repeater.repeatDig())) {
                repeater.setRepeatDig(null);
            }
            if (level.getEntity(id) instanceof Mob mob && mob instanceof HiveUnit unit
                    && unit.action() != null && pos.equals(unit.action().pos())) {
                unit.setAction(null);
                mob.getNavigation().stop();
            }
        }
        heart.clearDigProgress(pos);
    }

    /** Selected workers are set to keep mining this block: each time there is a block there they dig it, and carry on after anything else they are told. */
    private static void repeatDig(ServerPlayer player, ServerLevel level, List<Integer> ids, BlockPos pos) {
        List<Mob> workers = commandable(player, level, ids, UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        for (Mob worker : workers) {
            if (worker instanceof HiveWorker hiveWorker) {
                hiveWorker.setRepeatDig(pos.immutable());
            }
        }
        player.displayClientMessage(Component.translatable("message.projecthivemind.repeat_dig_started"), true);
    }

    // ---- constructions ----

    /** The player's construction whose block is here, in the dimension the camera is in. */
    @Nullable
    private static com.projecthivemind.build.Construction constructionAt(ServerPlayer player, HiveHeart heart, BlockPos pos) {
        return heart.constructions().at(player.serverLevel().dimension(), pos);
    }

    /** "Work": put these workers on the construction whose block was clicked. Workers on another construction move to this one. */
    public static void assignConstruction(ServerPlayer player, com.projecthivemind.network.AssignConstructionPayload request) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        com.projecthivemind.build.Construction construction = constructionAt(player, heart, request.pos());
        if (construction == null) {
            return;
        }
        List<Mob> workers = commandable(player, player.serverLevel(), request.unitIds(), UnitKind.WORKER);
        if (workers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_workers"), true);
            return;
        }
        if (construction.done()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.construction_is_done"), true);
            return;
        }
        HiveConstructions.assign(player, heart, construction, workers);
        syncActions(player, heart);
    }

    /** "Options": new settings for the construction whose block was clicked, checked as the first order was. */
    public static void updateConstruction(ServerPlayer player, com.projecthivemind.network.UpdateConstructionPayload request) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        com.projecthivemind.build.Construction construction = constructionAt(player, heart, request.pos());
        if (construction == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        java.util.Optional<String> problem = construction.applyConfig(request.config(), heart.getStorage(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (problem.isPresent()) {
            player.displayClientMessage(Component.translatable(problem.get()), true);
            return;
        }
        player.displayClientMessage(Component.translatable("message.projecthivemind.construction_updated"), true);
        HiveConstructions.reconcile(player, heart, construction);
    }

    /** "Finish", confirmed: the construction block goes and the workers on it are taken off. This can be done before it is done: what is built stays. */
    public static void finishConstruction(ServerPlayer player, com.projecthivemind.network.FinishConstructionPayload request) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        com.projecthivemind.build.Construction construction = constructionAt(player, heart, request.pos());
        if (construction == null) {
            return;
        }
        HiveConstructions.finish(heart, construction);
        syncActions(player, heart);
    }

    // ---- the scout's hand ----

    /**
     * The player's scout uses the item in its hand on this face of this block. The order is checked here: the sender's
     * own scout, something in its hand slot that can be used, and a block that is loaded.
     */
    public static void scoutUse(ServerPlayer player, ScoutUsePayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        HiveHeart heart = HivemindManager.findHeart(player);
        BlockPos pos = request.pos();
        if (heart == null || !level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            return;
        }
        List<Mob> scouts = commandable(player, level, request.unitIds(), UnitKind.SCOUT);
        if (scouts.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_scouts_use"), true);
            return;
        }
        if (!ScoutItems.usable(heart.scoutHeld())) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.scout_hand_empty"), true);
            return;
        }
        Mob scout = scouts.get(0);
        scout.getNavigation().stop();
        ((HiveUnit) scout).setAction(UnitAction.useItem(pos, request.face()));
        syncActions(player, heart);
    }

    /**
     * A scout places a hive portal (hive level 2 and up). With the portal limit reached, the player is asked first: placing another takes
     * the oldest down.
     */
    public static void placePortal(ServerPlayer player, com.projecthivemind.network.PlacePortalPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        HiveHeart heart = HivemindManager.findHeart(player);
        BlockPos pos = request.pos();
        if (heart == null || !level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            return;
        }
        if (HivePortals.max(heart) <= 0) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.portal_locked"), true);
            return;
        }
        List<Mob> scouts = commandable(player, level, request.unitIds(), UnitKind.SCOUT);
        if (scouts.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_scouts_use"), true);
            return;
        }
        if (!request.confirmed() && heart.portals().portals().size() >= HivePortals.max(heart)) {
            PacketDistributor.sendToPlayer(player, new com.projecthivemind.network.ConfirmPortalPayload(request));
            return;
        }
        Mob scout = scouts.get(0);
        scout.getNavigation().stop();
        ((HiveUnit) scout).setAction(UnitAction.portal(pos, request.face()));
        syncActions(player, heart);
    }

    /**
     * The selected scouts each drop one item from the stack in their hand, in the order of their numbers (Scout 1 first).
     * The hand is one stack, so with several scouts and a short stack the first ones drop and the rest have nothing left:
     * a single item is dropped by Scout 1 alone. Each item is thrown a little way in front of the scout, and cannot be
     * picked up again for two seconds.
     */
    public static void scoutDrop(ServerPlayer player, DropItemPayload request) {
        if (HivemindManager.get(player).stage() != HivemindStage.HIVE || request.unitIds().size() > BlockActionPayload.MAX_UNITS) {
            return;
        }
        ServerLevel level = player.serverLevel();
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null) {
            return;
        }
        ServerPlayer numberOwner = player;
        List<Mob> scouts = new ArrayList<>(commandable(player, level, request.unitIds(), UnitKind.SCOUT));
        scouts.sort(java.util.Comparator.comparingInt(scout -> HivemindManager.slotNumber(numberOwner, UnitKind.SCOUT, scout.getUUID())));
        for (Mob scout : scouts) {
            ItemStack hand = heart.scoutHeld();
            if (hand.isEmpty()) {
                break;
            }
            ItemStack one = hand.split(1);
            if (hand.isEmpty()) {
                heart.setScoutHeld(ItemStack.EMPTY);
            }
            heart.scoutHand().setChanged();
            Vec3 look = scout.getLookAngle();
            net.minecraft.world.entity.item.ItemEntity dropped = new net.minecraft.world.entity.item.ItemEntity(level,
                    scout.getX(), scout.getEyeY() - 0.3D, scout.getZ(), one);
            dropped.setDeltaMovement(look.x * 0.3D, 0.1D, look.z * 0.3D);
            dropped.setPickUpDelay(40);
            level.addFreshEntity(dropped);
            scout.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
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
            case GUARD -> guard(player, level, request.unitIds(), target);
            case CANCEL -> cancelMob(player, level, target);
        }
        syncActions(player, heart);
    }

    /**
     * Soldiers become the bodyguard of one of the player's own units that is not a soldier: they stay by it and fight whatever goes
     * for it, until it dies or the player cancels. A unit is not its own bodyguard.
     */
    private static void guard(ServerPlayer player, ServerLevel level, List<Integer> ids, Mob ward) {
        if (!ward.isAlive() || !(ward instanceof HiveUnit unit) || !player.getUUID().equals(unit.ownerId()) || unit.kind() == UnitKind.SOLDIER) {
            return;
        }
        List<Mob> soldiers = commandable(player, level, ids, UnitKind.SOLDIER);
        if (soldiers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_soldiers"), true);
            return;
        }
        for (Mob soldier : soldiers) {
            soldier.getNavigation().stop();
            ((HiveUnit) soldier).setAction(UnitAction.guard(ward.getUUID()));
        }
    }

    /**
     * Soldiers and scouts attack the mob and keep at it until it dies or the order is cancelled. Your own units and Hive Hearts
     * are not valid targets.
     */
    private static void attack(ServerPlayer player, ServerLevel level, List<Integer> ids, Mob target) {
        if (!target.isAlive() || target instanceof HiveHeart
                || (target instanceof HiveUnit unit && player.getUUID().equals(unit.ownerId()))) {
            return;
        }
        List<Mob> soldiers = commandable(player, level, ids, UnitKind.SOLDIER);
        soldiers.addAll(commandable(player, level, ids, UnitKind.SCOUT));
        if (soldiers.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_soldiers"), true);
            return;
        }
        for (Mob soldier : soldiers) {
            soldier.getNavigation().stop();
            ((HiveUnit) soldier).setAction(UnitAction.attack(target.getUUID()));
            mirrorToTeam(player, level, soldier, UnitAction.attack(target.getUUID()));
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
                    && player.getUUID().equals(unit.ownerId()) && !unit.kind().passive()
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
            // A construction block has its own highlight: a build order is not a red block.
            if (action.pos() != null && action.kind() != UnitAction.Kind.BUILD && blocks.size() < SyncActionsPayload.MAX_ENTRIES) {
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
