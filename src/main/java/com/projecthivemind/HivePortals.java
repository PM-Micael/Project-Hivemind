package com.projecthivemind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.entity.PortalNetwork;
import com.projecthivemind.network.SyncPortalsPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive portal network (from hive level 2): scouts place portals, the Hive Heart keeps the list ({@link PortalNetwork}), and units
 * are summoned through a portal or the Heart: they die where they are and come back one at a time, scouts first, then soldiers, then workers.
 */
public final class HivePortals {
    /** Keeps the chunk of the portal being summoned at loaded for as long as the summoning goes on. */
    private static final TicketType<ChunkPos> SUMMON_TICKET =
            TicketType.create("projecthivemind_summon", java.util.Comparator.comparingLong(ChunkPos::toLong), 60);

    /** Units that are being killed to be summoned: their deaths do not cost the hive anything. */
    private static final Set<java.util.UUID> SUMMONED = new java.util.HashSet<>();

    /** True (once) for a unit that was killed to be summoned. */
    public static boolean consumeSummoned(java.util.UUID unit) {
        return SUMMONED.remove(unit);
    }

    private HivePortals() {
    }

    public static int max(HiveHeart heart) {
        // The level gives the portal (from level 2).
        int fromLevel = HiveLevels.get(heart.hiveLevel()).maxPortals();
        return fromLevel;
    }

    // ---- placing ----

    /**
     * A scout has come to place a portal on this face of this block. At the limit, the oldest portal is taken down first (the player
     * has already agreed to that). Returns false if there is no room for it.
     */
    public static boolean place(ServerLevel level, HiveHeart heart, BlockPos clicked, Direction face) {
        BlockPos target = clicked.relative(face);
        BlockState existing = level.getBlockState(target);
        if (max(heart) <= 0 || !existing.canBeReplaced() || !existing.getFluidState().isEmpty()) {
            return false;
        }
        PortalNetwork network = heart.portals();
        while (network.portals().size() >= Math.max(1, max(heart))) {
            remove(level.getServer(), network.portals().remove(0));
        }
        level.setBlock(target, ModBlocks.HIVE_PORTAL.get().defaultBlockState(), 3);
        network.portals().add(GlobalPos.of(level.dimension(), target));
        level.playSound(null, target, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 1.0F, 0.6F);
        return true;
    }

    /** The player confirmed deleting a portal (an index of the list): its block is taken down. */
    public static void delete(ServerPlayer player, int index) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        PortalNetwork network = heart.portals();
        if (index < 0 || index >= network.portals().size()) {
            return;
        }
        remove(player.server, network.portals().remove(index));
        sync(player, heart);
    }

    /** The player asked for the camera at a portal (an index of the list): it goes above and behind the portal, in whatever dimension it is. */
    public static void goTo(ServerPlayer player, int index) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        PortalNetwork network = heart.portals();
        if (index < 0 || index >= network.portals().size()) {
            return;
        }
        net.minecraft.core.GlobalPos portal = network.portals().get(index);
        net.minecraft.server.level.ServerLevel level = player.server.getLevel(portal.dimension());
        if (level != null) {
            HivemindManager.snapCamera(player, level, portal.pos());
        }
    }

    /** The player switched "resummon team units" on or off for the portal at this index of the list. */
    public static void toggleResummon(ServerPlayer player, int index) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        PortalNetwork network = heart.portals();
        if (index < 0 || index >= network.portals().size()) {
            return;
        }
        GlobalPos portal = network.portals().get(index);
        if (!network.resummon().remove(portal)) {
            network.resummon().add(portal);
        }
        sync(player, heart);
    }

    /**
     * A unit of this team died. If the team's portal is set to resummon, the unit is queued to come back through that portal, with the
     * countdown ticking, instead of the Heart making a replacement. Returns false when it does not apply (no such portal, not set, or the
     * network is busy summoning somewhere else), and the Heart replaces the unit as usual.
     */
    public static boolean resummonDeath(ServerPlayer owner, HiveHeart heart, UnitKind kind, int team, int slot) {
        PortalNetwork network = heart.portals();
        network.useInterval(com.projecthivemind.EvolveTask.spawnIntervalTicks(heart.evolveMask()));
        if (team < 0 || team >= network.portals().size() || !java.util.Arrays.asList(PortalNetwork.SUMMON_ORDER).contains(kind)) {
            return false;
        }
        GlobalPos portal = network.portals().get(team);
        if (!network.resummon().contains(portal) || (network.summoning() && !portal.equals(network.target()))) {
            return false;
        }
        PortalNetwork.Pending pending = new PortalNetwork.Pending(kind, slot < 0 ? null : heart.slotConfigs().get(kind, slot), slot);
        if (network.summoning()) {
            network.addToSummoning(pending);
        } else {
            network.startSummoning(portal, List.of(pending));
        }
        sync(owner, heart);
        return true;
    }

    /** Take a portal's block down, if it is still there to be taken. */
    private static void remove(net.minecraft.server.MinecraftServer server, GlobalPos portal) {
        ServerLevel level = server.getLevel(portal.dimension());
        if (level != null && level.isLoaded(portal.pos()) && level.getBlockState(portal.pos()).is(ModBlocks.HIVE_PORTAL.get())) {
            level.removeBlock(portal.pos(), false);
        }
    }

    // ---- summoning ----


    /**
     * The player confirmed summoning these units to a portal (an index of the list) or the Heart (-1). They die, and are queued to come
     * back through it, one every ten seconds. The camera goes to the place they will come back at.
     */
    public static void summon(ServerPlayer player, int targetIndex, List<Integer> unitIds) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE || unitIds.size() > com.projecthivemind.network.BlockActionPayload.MAX_UNITS) {
            return;
        }
        PortalNetwork network = heart.portals();
        network.useInterval(com.projecthivemind.EvolveTask.spawnIntervalTicks(heart.evolveMask()));
        if (network.summoning()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.summon_busy"), true);
            return;
        }
        GlobalPos target = null;
        if (targetIndex >= 0) {
            if (targetIndex >= network.portals().size()) {
                return;
            }
            target = network.portals().get(targetIndex);
        }
        Set<Mob> units = new LinkedHashSet<>();
        for (int id : unitIds) {
            if (HivemindManager.findById(player, id) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                    && player.getUUID().equals(unit.ownerId()) && !unit.kind().passive()) {
                units.add(mob);
            }
        }
        if (units.isEmpty()) {
            return;
        }
        // A summoned unit comes back with every behaviour option unticked (the radii are the kind's defaults).
        List<PortalNetwork.Pending> kinds = new ArrayList<>();
        for (Mob mob : units) {
            HiveUnit unit = (HiveUnit) mob;
            kinds.add(new PortalNetwork.Pending(unit.kind(), HivemindManager.captureConfig(unit), HivemindManager.slotNumber(player, unit.kind(), mob.getUUID())));
            SUMMONED.add(mob.getUUID());
        }
        // The queue first, so that the hive does not make its own replacements for the units that are about to die.
        network.startSummoning(target, kinds);
        for (Mob mob : units) {
            mob.kill();
        }
        // The camera goes to where they will come through: one block above the Heart's top, or above the portal.
        if (target == null) {
            if (heart.level() instanceof net.minecraft.server.level.ServerLevel heartLevel) {
                HivemindManager.cameraAbove(player, heartLevel, heart.getX(), heart.getY() + heart.getBbHeight() + 1.0D, heart.getZ());
            }
        } else if (player.server.getLevel(target.dimension()) != null) {
            HivemindManager.cameraAbove(player, player.server.getLevel(target.dimension()), target.pos().getX() + 0.5D, target.pos().getY() + 2.0D, target.pos().getZ() + 0.5D);
        }
        sync(player, heart);
    }

    /**
     * Instant reinforcements (the Eye of Ender task): every soldier the player has, in any dimension, is teleported to the Heart and
     * stands in a ring around it. Needs the hive stage and the task done.
     */
    public static void reinforce(ServerPlayer player) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE
                || !com.projecthivemind.EvolveTask.ENDER_EYE.doneIn(heart.evolveMask()) || !(heart.level() instanceof ServerLevel heartLevel)) {
            return;
        }
        double reach = HiveHeart.widthAt(heart.visualLevel()) / 2.0D + 1.5D;
        int moved = 0;
        for (java.util.UUID id : HivemindManager.get(player).allUnits()) {
            for (ServerLevel level : player.server.getAllLevels()) {
                if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                        && unit.kind() == UnitKind.SOLDIER && player.getUUID().equals(unit.ownerId())) {
                    // A ring around the Heart, a wider one for every eight soldiers.
                    double angle = moved * (Math.PI * 2.0D / 8.0D);
                    double radius = reach + moved / 8;
                    double x = heart.getX() + Math.cos(angle) * radius;
                    double z = heart.getZ() + Math.sin(angle) * radius;
                    mob.getNavigation().stop();
                    if (level == heartLevel) {
                        mob.teleportTo(x, heart.getY(), z);
                    } else {
                        mob.teleportTo(heartLevel, x, heart.getY(), z, java.util.Set.of(), mob.getYRot(), 0.0F);
                    }
                    heartLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL, x, heart.getY() + 1.0D, z, 12, 0.3D, 0.5D, 0.3D, 0.1D);
                    moved++;
                    break;
                }
            }
        }
        heartLevel.playSound(null, heart.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("message.projecthivemind.reinforced", moved), true);
    }

    /** Where a unit comes through: the portal's top, or null if the summoning is at the Heart (or the portal is gone). */
    @Nullable
    private static GlobalPos portalSpot(net.minecraft.server.MinecraftServer server, PortalNetwork network) {
        GlobalPos target = network.target();
        if (target == null) {
            return null;
        }
        ServerLevel level = server.getLevel(target.dimension());
        return level != null && network.portals().contains(target) ? target : null;
    }

    /**
     * Every tick, from the Heart: forget portals that have been taken down, and bring the next summoned unit through when its time comes.
     * The owner's client is told about the network once a second, and at once when something changes.
     */
    public static void tick(HiveHeart heart) {
        if (heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null || HivemindManager.get(owner).stage() != HivemindStage.HIVE) {
            return;
        }
        PortalNetwork network = heart.portals();
        network.useInterval(com.projecthivemind.EvolveTask.spawnIntervalTicks(heart.evolveMask()));
        boolean changed = false;
        if (heart.tickCount % 20 == 0) {
            var iterator = network.portals().iterator();
            while (iterator.hasNext()) {
                GlobalPos portal = iterator.next();
                ServerLevel level = heart.getServer().getLevel(portal.dimension());
                if (level == null || (level.isLoaded(portal.pos()) && !level.getBlockState(portal.pos()).is(ModBlocks.HIVE_PORTAL.get()))) {
                    iterator.remove();
                    changed = true;
                }
            }
            network.resummon().retainAll(network.portals());
        }
        if (network.summoning()) {
            GlobalPos spot = portalSpot(heart.getServer(), network);
            if (spot != null && heart.tickCount % 20 == 0) {
                ChunkPos chunk = new ChunkPos(spot.pos());
                heart.getServer().getLevel(spot.dimension()).getChunkSource().addRegionTicket(SUMMON_TICKET, chunk, 3, chunk);
            }
            network.setTicksToNext(network.ticksToNext() - 1);
            if (network.ticksToNext() <= 0) {
                PortalNetwork.Pending next = network.queue().remove(0);
                if (spot != null && heart.getServer().getLevel(spot.dimension()).isLoaded(spot.pos())) {
                    ServerLevel level = heart.getServer().getLevel(spot.dimension());
                    HivemindManager.createUnitAt(owner, heart, next.kind(), level, spot.pos().getX() + 0.5D, spot.pos().getY() + 1.0D, spot.pos().getZ() + 0.5D, next.config(), network.portals().indexOf(spot), next.slot());
                } else {
                    // At the Heart, or the portal has been taken down: they come through the Heart.
                    HivemindManager.createUnitAtHeart(owner, heart, next.kind(), next.config(), next.slot());
                }
                network.setTicksToNext(network.interval());
                if (network.queue().isEmpty()) {
                    network.stopSummoning();
                }
                changed = true;
            }
        }
        if (changed || heart.tickCount % 20 == 0) {
            sync(owner, heart);
        }
    }

    /** Tell the owner's client the state of the network. */
    public static void sync(ServerPlayer owner, HiveHeart heart) {
        PortalNetwork network = heart.portals();
        List<SyncPortalsPayload.Portal> portals = new ArrayList<>();
        for (GlobalPos portal : network.portals()) {
            if (portals.size() < SyncPortalsPayload.MAX_ENTRIES) {
                portals.add(new SyncPortalsPayload.Portal(portal.dimension().location().toString(), portal.pos(), network.resummon().contains(portal)));
            }
        }
        int target = SyncPortalsPayload.NONE;
        if (network.summoning()) {
            target = network.target() == null ? -1 : Math.max(-1, network.portals().indexOf(network.target()));
        }
        List<Integer> queue = new ArrayList<>();
        for (PortalNetwork.Pending pending : network.queue()) {
            if (queue.size() < SyncPortalsPayload.MAX_ENTRIES * 4) {
                queue.add(pending.kind().ordinal());
            }
        }
        PacketDistributor.sendToPlayer(owner, new SyncPortalsPayload(portals, max(heart), target, queue,
                (network.ticksToNext() + 19) / 20));
    }
}
