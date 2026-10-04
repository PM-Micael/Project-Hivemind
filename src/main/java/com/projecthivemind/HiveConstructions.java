package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.build.Construction;
import com.projecthivemind.build.Constructions;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.network.SyncConstructionsPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive's constructions at work: they are placed (a construction block where the work starts), workers are put on them and taken off, they are
 * finished, and every second the Heart sees to them (see {@link #tick}). The building itself is done by the workers, with the recipes of BridgeJob,
 * StairDig and TowerBuild: this decides which of those a worker is on.
 */
public final class HiveConstructions {
    private HiveConstructions() {
    }

    // ---- placing ----

    /** A key for a column, to keep a set of them. */
    public static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Where the construction block goes: the free spot on the ground nearest to the wanted column, outside the columns the construction itself will
     * use (the workers must not build or dig it away), and not on another construction's block. Falls back to the wanted column's surface.
     */
    public static BlockPos findAnchor(ServerLevel level, HiveHeart heart, int wantedX, int wantedZ, Set<Long> reserved) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = wantedX + dx;
                    int z = wantedZ + dz;
                    if (reserved.contains(columnKey(x, z)) || !level.isLoaded(new BlockPos(x, level.getMinBuildHeight(), z))) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    BlockState here = level.getBlockState(pos);
                    BlockState under = level.getBlockState(pos.below());
                    if (here.canBeReplaced() && here.getFluidState().isEmpty() && under.getFluidState().isEmpty()
                            && under.isFaceSturdy(level, pos.below(), Direction.UP) && heart.constructions().at(level.dimension(), pos) == null) {
                        return pos;
                    }
                }
            }
        }
        return new BlockPos(wantedX, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wantedX, wantedZ), wantedZ);
    }

    /** Put the construction block down and add the construction to the hive's. */
    public static void place(ServerLevel level, HiveHeart heart, Construction construction) {
        level.setBlock(construction.anchor(), ModBlocks.CONSTRUCTION.get().defaultBlockState(), Block.UPDATE_ALL);
        heart.constructions().add(construction);
    }

    // ---- workers on constructions ----

    @Nullable
    private static HiveWorker findWorker(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof HiveWorker worker && worker.isAlive()) {
                return worker;
            }
        }
        return null;
    }

    /**
     * Put these workers on the construction, up to the most it takes; a worker that was on another construction leaves it. Returns how many
     * are on it from these.
     */
    public static int assign(ServerPlayer player, HiveHeart heart, Construction construction, List<Mob> workers) {
        int added = 0;
        for (Mob mob : workers) {
            if (!(mob instanceof HiveWorker worker) || construction.hasWorker(worker.getUUID())) {
                continue;
            }
            if (construction.workers().size() >= construction.maxWorkers()) {
                player.displayClientMessage(Component.translatable("message.projecthivemind.construction_full", construction.maxWorkers()), true);
                break;
            }
            Construction before = heart.constructions().of(worker.getUUID());
            if (before != null) {
                before.workers().remove(worker.getUUID());
            }
            worker.clearConstructionWork();
            worker.getNavigation().stop();
            construction.workers().add(worker.getUUID());
            added++;
        }
        reconcile(player, heart, construction);
        return added;
    }

    /** Take a worker off its construction (the worker's Cancel job button does this). */
    public static void leave(HiveHeart heart, UUID worker) {
        heart.constructions().unassign(worker);
    }

    /** A worker found its construction complete: it is done, and the owner is told, once. */
    public static void markDone(HiveHeart heart, UUID worker) {
        Construction construction = heart.constructions().of(worker);
        if (construction != null) {
            complete(heart, construction);
        }
    }

    /** The construction is whole: its block shows green, its workers stop, and the owner is told. */
    public static void complete(HiveHeart heart, Construction construction) {
        if (construction.done()) {
            return;
        }
        construction.setDone(true);
        ServerPlayer owner = owner(heart);
        if (owner != null) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.construction_done"), false);
        }
    }

    /** The player confirmed Finish: the block goes, every worker on it is taken off, and the construction is forgotten. */
    public static void finish(HiveHeart heart, Construction construction) {
        MinecraftServer server = heart.getServer();
        if (server == null) {
            return;
        }
        for (UUID id : construction.workerList()) {
            HiveWorker worker = findWorker(server, id);
            if (worker != null) {
                worker.clearConstructionWork();
                worker.getNavigation().stop();
            }
        }
        construction.workers().clear();
        ServerLevel level = server.getLevel(construction.dimension());
        if (level != null && level.isLoaded(construction.anchor()) && level.getBlockState(construction.anchor()).is(ModBlocks.CONSTRUCTION.get())) {
            level.setBlock(construction.anchor(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        heart.constructions().remove(construction);
    }

    @Nullable
    private static ServerPlayer owner(HiveHeart heart) {
        return heart.getServer() == null || heart.ownerId() == null ? null : heart.getServer().getPlayerList().getPlayer(heart.ownerId());
    }

    // ---- every second ----

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (heart.tickCount % 20 != 0) {
            return;
        }
        ServerPlayer owner = owner(heart);
        if (owner == null) {
            return;
        }
        for (Construction construction : new ArrayList<>(heart.constructions().all())) {
            ServerLevel level = heart.getServer().getLevel(construction.dimension());
            if (level == null) {
                continue;
            }
            // A construction block that was broken ends the construction.
            if (level.isLoaded(construction.anchor()) && !level.getBlockState(construction.anchor()).is(ModBlocks.CONSTRUCTION.get())) {
                finish(heart, construction);
                continue;
            }
            // A tower is done when the world says so, whoever notices.
            if (construction.kind() == Construction.Kind.TOWER && !construction.done() && construction.tower() != null && towerLoaded(level, construction)
                    && construction.tower().isComplete(level)) {
                complete(heart, construction);
            }
            reconcile(owner, heart, construction);
        }
        sync(owner, heart);
    }

    /** True if the corners of the tower's site are loaded, so that looking at it does not load them. */
    private static boolean towerLoaded(ServerLevel level, Construction construction) {
        BlockPos base = construction.tower().plan().base();
        for (int dx = -3; dx <= 3; dx += 6) {
            for (int dz = -3; dz <= 3; dz += 6) {
                if (!level.isLoaded(base.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Make sure every worker on the construction is on its job (and gone from the workers that are not any more). */
    public static void reconcile(ServerPlayer owner, HiveHeart heart, Construction construction) {
        Set<UUID> units = new HashSet<>(HivemindManager.get(owner).allUnits());
        construction.workers().removeIf(id -> !units.contains(id));
        if (construction.done()) {
            for (UUID id : construction.workerList()) {
                HiveWorker worker = findWorker(heart.getServer(), id);
                if (worker != null && worker.hasConstructionTower()) {
                    worker.clearConstructionWork();
                }
            }
            return;
        }
        boolean active = construction.workers().size() >= construction.minWorkers();
        for (UUID id : construction.workerList()) {
            HiveWorker worker = findWorker(heart.getServer(), id);
            if (worker == null) {
                continue;
            }
            worker.putOnConstruction(construction, active, heart.isUnitSelected(worker.getId()));
        }
    }

    // ---- telling the owner ----

    /** Tell the owner's client about the constructions, when they have changed (and now and then regardless, so a client that has just joined catches up). */
    private static void sync(ServerPlayer owner, HiveHeart heart) {
        Constructions constructions = heart.constructions();
        List<SyncConstructionsPayload.Info> infos = new ArrayList<>();
        for (Construction construction : constructions.all()) {
            if (infos.size() >= SyncConstructionsPayload.MAX_ENTRIES) {
                break;
            }
            CompoundTag config = construction.configTag();
            infos.add(new SyncConstructionsPayload.Info(construction.dimension().location().toString(), construction.anchor(),
                    SyncConstructionsPayload.Info.pack(construction.kind().ordinal(), construction.state(construction.workers().size()).ordinal(),
                            construction.workers().size(), construction.minWorkers(), construction.maxWorkers()), config));
        }
        if (!infos.equals(constructions.synced()) || heart.tickCount % 200 == 0) {
            constructions.setSynced(infos);
            PacketDistributor.sendToPlayer(owner, new SyncConstructionsPayload(infos));
        }
    }
}
