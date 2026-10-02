package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;
import com.projecthivemind.HiveSight;
import com.projecthivemind.HiveArea;
import com.projecthivemind.UnitAction;
import com.projecthivemind.WorkerBehavior;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * Finds work for an idle worker according to the hive's worker settings. It only ever picks a block that is in the
 * worker's own range, that the hive can actually see (Hive Sight, so buried ore is off limits), and that the hive's
 * tools can harvest. The job it returns is an ordinary dig order, carried out by the same code as one the player gives.
 */
public final class WorkerAutoJobs {
    /** Only the nearest few matches are looked at closely, because seeing and path-finding cost more than scanning. */
    private static final int MAX_CANDIDATES = 24;
    /** How far above and below itself a worker looks for crops to harvest. */
    private static final int HARVEST_HEIGHT = 6;

    private WorkerAutoJobs() {
    }

    @Nullable
    public static UnitAction findJob(HiveWorker worker, HiveHeart heart) {
        WorkerBehavior behavior = worker.behavior();
        ServerLevel level = (ServerLevel) worker.level();
        BlockPos origin = worker.blockPosition();

        List<BlockPos> wanted = scan(level, origin, behavior);
        // A worker set to stay inside the hive area does not look for work outside it.
        if (behavior.stayInside()) {
            wanted.removeIf(pos -> !HiveArea.containsXZ(heart, pos.getX() + 0.5D, pos.getZ() + 0.5D));
        }
        wanted.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));

        int checked = 0;
        for (BlockPos pos : wanted) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            // The hive's tools must be able to harvest it, or the worker would only destroy it for nothing.
            if (!HiveActions.toolsCanHarvest(heart, state) || !HiveSight.canSeeBlock(level, heart.sightEyes(), pos)) {
                continue;
            }
            if (reachable(worker, pos)) {
                return new UnitAction(UnitAction.Kind.DIG, pos);
            }
            if (behavior.digThrough()) {
                BlockPos obstacle = obstacleToward(worker, level, pos);
                if (obstacle != null) {
                    return new UnitAction(UnitAction.Kind.DIG, obstacle);
                }
            }
        }
        return null;
    }

    /**
     * The nearest fully grown crop (or wild plant, if the worker is set to clear them) inside the hive area that the worker can get to, as a dig order, or null if none is ready.
     * Only crops near the worker's height are looked at, as farms lie flat; the worker looks again every few seconds.
     */
    @Nullable
    public static UnitAction findHarvest(HiveWorker worker, HiveHeart heart) {
        if (!(worker.level() instanceof ServerLevel level)) {
            return null;
        }
        boolean crops = worker.behavior().harvestCrops();
        boolean plants = worker.behavior().clearPlants();
        net.minecraft.world.phys.AABB area = HiveArea.areaBox(level, heart);
        BlockPos origin = worker.blockPosition();
        List<BlockPos> ready = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) area.minX; x < (int) area.maxX; x++) {
            for (int z = (int) area.minZ; z < (int) area.maxZ; z++) {
                // Reading a block in an unloaded chunk would make the game load it, so never touch those.
                if (!level.hasChunkAt(pos.set(x, origin.getY(), z))) {
                    continue;
                }
                for (int y = origin.getY() - HARVEST_HEIGHT; y <= origin.getY() + HARVEST_HEIGHT; y++) {
                    pos.set(x, y, z);
                    BlockState found = level.getBlockState(pos);
                    if ((crops && isGrown(found)) || (plants && isWildPlant(found))) {
                        ready.add(pos.immutable());
                    }
                }
            }
        }
        ready.sort(Comparator.comparingDouble(crop -> crop.distSqr(origin)));
        int checked = 0;
        for (BlockPos crop : ready) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            if (reachable(worker, crop)) {
                return new UnitAction(UnitAction.Kind.DIG, crop);
            }
        }
        return null;
    }


    /** True for a crop that is fully grown and ready to harvest: wheat, carrots, potatoes, beetroot, nether wart... */
    public static boolean isGrown(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return state.getBlock() instanceof net.minecraft.world.level.block.NetherWartBlock
                && state.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) >= net.minecraft.world.level.block.NetherWartBlock.MAX_AGE;
    }

    /** True for a crop that is still growing: wheat, carrots, potatoes, beetroot, nether wart... that is not at its full age yet. */
    public static boolean isGrowing(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
            return !crop.isMaxAge(state);
        }
        return state.getBlock() instanceof net.minecraft.world.level.block.NetherWartBlock
                && state.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) < net.minecraft.world.level.block.NetherWartBlock.MAX_AGE;
    }

    /**
     * The nearest crop inside the hive area that is not fully grown and that the worker can get to, other than the ones to leave
     * alone for now, or null if there is none. Only crops near the worker's height are looked at.
     */
    @Nullable
    public static BlockPos findGrowing(HiveWorker worker, HiveHeart heart, java.util.Set<BlockPos> leaveAlone) {
        if (!(worker.level() instanceof ServerLevel level)) {
            return null;
        }
        net.minecraft.world.phys.AABB area = HiveArea.areaBox(level, heart);
        BlockPos origin = worker.blockPosition();
        List<BlockPos> growing = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) area.minX; x < (int) area.maxX; x++) {
            for (int z = (int) area.minZ; z < (int) area.maxZ; z++) {
                if (!level.hasChunkAt(pos.set(x, origin.getY(), z))) {
                    continue;
                }
                for (int y = origin.getY() - HARVEST_HEIGHT; y <= origin.getY() + HARVEST_HEIGHT; y++) {
                    pos.set(x, y, z);
                    if (isGrowing(level.getBlockState(pos)) && !leaveAlone.contains(pos)) {
                        growing.add(pos.immutable());
                    }
                }
            }
        }
        growing.sort(Comparator.comparingDouble(crop -> crop.distSqr(origin)));
        int checked = 0;
        for (BlockPos crop : growing) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            if (reachable(worker, crop)) {
                return crop;
            }
        }
        return null;
    }

    /** True for plants that grow wild and can be cleared: grass, ferns, and every kind of flower (tall ones too). */
    public static boolean isWildPlant(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.FLOWERS)
                || state.getBlock() instanceof net.minecraft.world.level.block.TallGrassBlock
                || state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS) || state.is(net.minecraft.world.level.block.Blocks.LARGE_FERN);
    }

    /** True for what a worker's harvest settings can send it after: a grown crop, or a wild plant. */
    public static boolean isHarvestable(BlockState state) {
        return isGrown(state) || isWildPlant(state);
    }

    /** Every block of a wanted kind inside the worker's range. Columns in chunks that are not loaded are skipped. */
    private static List<BlockPos> scan(ServerLevel level, BlockPos origin, WorkerBehavior behavior) {
        int radius = behavior.maxRadius();
        double radiusSqr = (double) radius * radius;
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                // Reading a block in an unloaded chunk would make the game load it, so never touch those.
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = Math.max(level.getMinBuildHeight(), origin.getY() - radius);
                     y <= Math.min(level.getMaxBuildHeight() - 1, origin.getY() + radius); y++) {
                    pos.set(x, y, z);
                    if (pos.distSqr(origin) <= radiusSqr && isWanted(behavior, level.getBlockState(pos), pos.distSqr(origin))) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        return found;
    }

    private static boolean isWanted(WorkerBehavior behavior, BlockState state, double distanceSqr) {
        return (behavior.mineOre() && state.is(Tags.Blocks.ORES) && distanceSqr <= (double) behavior.oreRadius() * behavior.oreRadius())
                // Natural logs only, so a worker never takes apart something the player built out of logs.
                || (behavior.chopLogs() && state.is(BlockTags.OVERWORLD_NATURAL_LOGS) && distanceSqr <= (double) behavior.logRadius() * behavior.logRadius());
    }

    /** True if the worker is already within digging reach, or can walk to within a block or two of the target. */
    private static boolean reachable(HiveWorker worker, BlockPos pos) {
        if (WorkerDigGoal.inDigReach(worker, pos)) {
            return true;
        }
        Path path = worker.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }

    /**
     * The first block in the way on a straight line from the worker to the target, if it can be dug. Digging that
     * is a step toward the target; the worker looks again afterwards and takes the next step.
     */
    @Nullable
    private static BlockPos obstacleToward(HiveWorker worker, ServerLevel level, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(worker.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) {
            return null;
        }
        BlockState blocking = level.getBlockState(hit.getBlockPos());
        boolean diggable = !blocking.isAir() && !(blocking.getBlock() instanceof LiquidBlock)
                && blocking.getDestroySpeed(level, hit.getBlockPos()) >= 0.0F;
        return diggable ? hit.getBlockPos() : null;
    }
}
