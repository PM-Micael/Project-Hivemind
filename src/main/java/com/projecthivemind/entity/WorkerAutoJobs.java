package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;
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
            if (!HiveActions.toolsCanHarvest(heart, state) || !exposed(level, pos)) {
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

    /** A block with at least one face open to air or something see-through: ore buried in solid rock is not looked for, but ore showing in a cave wall is. */
    private static boolean exposed(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos next = pos.relative(direction);
            if (level.isLoaded(next) && !level.getBlockState(next).isSolidRender(level, next)) {
                return true;
            }
        }
        return false;
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
        // The list the last look made comes first: what is still there, nearest to the worker now. Only when it runs dry is the whole area looked over again.
        List<BlockPos> ready = worker.harvestQueue();
        ready.removeIf(crop -> !wanted(level, crop, crops, plants));
        if (ready.isEmpty()) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            java.util.function.Predicate<BlockState> kinds = state -> (crops && isGrown(state)) || (plants && isWildPlant(state));
            it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap chunkHas = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
            for (int x = (int) area.minX; x < (int) area.maxX; x++) {
                for (int z = (int) area.minZ; z < (int) area.maxZ; z++) {
                    // A chunk with none of it, or one that is not loaded (reading it would make the game load it), is passed over whole.
                    int cx = x >> 4;
                    int cz = z >> 4;
                    if (!chunkHas.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                            key -> chunkMayHave(level, cx, cz, origin.getY() - HARVEST_HEIGHT, origin.getY() + HARVEST_HEIGHT, kinds))) {
                        continue;
                    }
                    for (int y = origin.getY() - HARVEST_HEIGHT; y <= origin.getY() + HARVEST_HEIGHT; y++) {
                        pos.set(x, y, z);
                        if (wanted(level, pos, crops, plants)) {
                            ready.add(pos.immutable());
                        }
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


    private static boolean wanted(ServerLevel level, BlockPos pos, boolean crops, boolean plants) {
        BlockState found = level.getBlockState(pos);
        return (crops && isGrown(found)) || (plants && isWildPlant(found));
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
     * The nearest tree this worker can start on, as a dig order for its bottom log, or null if there is none. Only natural trees whose foot
     * is inside the hive area, near the worker's height, and that it can walk up to. When the log breaks the whole tree comes down (see
     * {@link TreeFelling}); the worker is told which log that is.
     */
    @Nullable
    public static UnitAction findFelling(HiveWorker worker, HiveHeart heart) {
        if (!(worker.level() instanceof ServerLevel level)) {
            return null;
        }
        WorkerBehavior behavior = worker.behavior();
        // Where trees are looked for: the hive area if it fells trees there, and the area round the worker if it chops by range. Both can be on.
        net.minecraft.world.phys.AABB search = null;
        if (behavior.fellTrees()) {
            search = HiveArea.areaBox(level, heart);
        }
        if (behavior.chopLogs()) {
            net.minecraft.world.phys.AABB range = worker.getBoundingBox().inflate(behavior.logRadius());
            search = search == null ? range : search.minmax(range);
        }
        if (search == null) {
            return null;
        }
        BlockPos origin = worker.blockPosition();
        int height = Math.max(HARVEST_HEIGHT, Math.min(behavior.chopLogs() ? behavior.logRadius() : 0, 12));
        List<BlockPos> logs = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap chunkHas = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        for (int x = (int) Math.floor(search.minX); x < (int) Math.ceil(search.maxX); x++) {
            for (int z = (int) Math.floor(search.minZ); z < (int) Math.ceil(search.maxZ); z++) {
                int cx = x >> 4;
                int cz = z >> 4;
                if (!chunkHas.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                        key -> chunkMayHave(level, cx, cz, origin.getY() - height, origin.getY() + height, TreeFelling::isNaturalLog))) {
                    continue;
                }
                for (int y = origin.getY() - height; y <= origin.getY() + height; y++) {
                    pos.set(x, y, z);
                    if (TreeFelling.isNaturalLog(level.getBlockState(pos)) && !TreeFelling.isNaturalLog(level.getBlockState(pos.below()))
                            && fellingScope(worker, heart, pos)) {
                        logs.add(pos.immutable());
                    }
                }
            }
        }
        logs.sort(Comparator.comparingDouble(log -> log.distSqr(origin)));
        int checked = 0;
        for (BlockPos log : logs) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            if (TreeFelling.isTreeFoot(level, log) && reachable(worker, log)) {
                return new UnitAction(UnitAction.Kind.DIG, log);
            }
        }
        return null;
    }

    /**
     * Whether a tree with its foot here is one this worker fells: inside the hive area if it is set to fell trees there, or within its
     * range of itself if it is set to chop by range.
     */
    public static boolean fellingScope(HiveWorker worker, HiveHeart heart, BlockPos foot) {
        WorkerBehavior behavior = worker.behavior();
        return (behavior.fellTrees() && HiveArea.containsCube(heart, foot.getX() + 0.5D, foot.getY() + 0.5D, foot.getZ() + 0.5D))
                || (behavior.chopLogs() && foot.distSqr(worker.blockPosition()) <= (double) behavior.logRadius() * behavior.logRadius());
    }

    /**
     * Whether a worker can take this block and get its drops with what the hive has: a block that needs no particular tool (dirt, grass) can be
     * broken by hand (the worker then goes empty-handed if no tool is in the hive), and one that does (stone) needs a tool in the hive's
     * tool slots that is right for it. A block that fails both is left alone.
     */
    private static boolean canBreakProperly(ServerLevel level, HiveHeart heart, BlockState state, BlockPos pos) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        int slot = com.projecthivemind.HiveEquipment.bestToolSlot(heart, level.registryAccess(), state);
        return slot >= 0 && heart.getToolGear().getItem(slot).isCorrectToolForDrops(state);
    }

    /** How far above the floor the Heart stands on a worker flattening the ground cuts the hills away: this many blocks. */
    public static final int FLATTEN_CUT_HEIGHT = 4;

    /**
     * Whether a chunk could hold a block of a kind, between two heights: asked of each of its sections' palettes, so a chunk with none of it (a
     * flat world, a stretch of sky) is passed over without looking at a single block. A chunk that is not loaded says no.
     */
    public static boolean chunkMayHave(ServerLevel level, int chunkX, int chunkZ, int minY, int maxY, java.util.function.Predicate<BlockState> test) {
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            int bottom = level.getMinBuildHeight() + s * 16;
            if (bottom <= maxY && bottom + 15 >= minY && sections[s].maybeHas(test)) {
                return true;
            }
        }
        return false;
    }

    /** True for what flattening digs off a rise: stone and the like, dirt and grass, sand and gravel. */
    public static boolean isFlattenCut(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.world.level.block.Blocks.STONE)
                || state.is(BlockTags.SAND) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL);
    }

    /**
     * The next block a worker flattening the ground digs off: in the hive area (or the team area, if set to), from one block above the floor up to
     * {@value #FLATTEN_CUT_HEIGHT} blocks above it, only stone, dirt and grass; the highest block of the nearest column that has any, so a rise is
     * taken down from the top. Null when nothing is left to cut that the worker can get to.
     */
    @Nullable
    public static UnitAction findFlattenDig(HiveWorker worker, HiveHeart heart) {
        if (!(worker.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos origin = worker.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (FlattenRegion region : FlattenRegion.of(worker, heart)) {
            int floorTop = region.floorTop();
            it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap chunkHas = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
            for (int x = region.minX(); x < region.maxX(); x++) {
                for (int z = region.minZ(); z < region.maxZ(); z++) {
                    int cx = x >> 4;
                    int cz = z >> 4;
                    if (!region.contains(x, z) || !chunkHas.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                            key -> chunkMayHave(level, cx, cz, floorTop + 1, floorTop + FLATTEN_CUT_HEIGHT, WorkerAutoJobs::isFlattenCut))) {
                        continue;
                    }
                    for (int y = floorTop + FLATTEN_CUT_HEIGHT; y > floorTop; y--) {
                        pos.set(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (isFlattenCut(state) && state.getFluidState().isEmpty() && canBreakProperly(level, heart, state, pos)) {
                            candidates.add(pos.immutable());
                            break;
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.distSqr(origin)));
        int checked = 0;
        for (BlockPos candidate : candidates) {
            if (checked++ >= MAX_CANDIDATES) {
                break;
            }
            if (canWalkToward(worker, candidate)) {
                return new UnitAction(UnitAction.Kind.DIG, candidate);
            }
        }
        return null;
    }

    /** True for a sapling (or a propagule) that has not grown into a tree yet. */
    public static boolean isSapling(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock;
    }

    /** True for plants that grow wild and can be cleared: grass, ferns, and every kind of flower (tall ones too). */
    public static boolean isWildPlant(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.FLOWERS)
                || state.getBlock() instanceof net.minecraft.world.level.block.TallGrassBlock
                || state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS) || state.is(net.minecraft.world.level.block.Blocks.LARGE_FERN);
    }

    /** True for blocks a worker takes with an empty hand, not a tool: what it harvests (crops, grass, flowers) and the leaves of a tree. */
    public static boolean bareHandBlock(BlockState state) {
        return isHarvestable(state) || state.is(BlockTags.LEAVES);
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
        it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap chunkHas = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                // Reading a block in an unloaded chunk would make the game load it, so never touch those.
                int cx = x >> 4;
                int cz = z >> 4;
                if (!chunkHas.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                        key -> chunkMayHave(level, cx, cz, origin.getY() - radius, origin.getY() + radius, state -> behavior.mineOre() && state.is(Tags.Blocks.ORES)))) {
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
        return behavior.mineOre() && state.is(Tags.Blocks.ORES) && distanceSqr <= (double) behavior.oreRadius() * behavior.oreRadius();
    }

    /**
     * True if the worker is in reach of the block, or a path toward it exists that brings it nearer. A path only looks so far (the follow range), so
     * a block on the far side of the hive area has no complete path from here, but the worker walks toward it in legs and looks again from each place
     * it gets to (the goals that walk repath every half second). Flattening the ground uses this so a worker works the whole area, not only what is near.
     */
    public static boolean canWalkToward(HiveWorker worker, BlockPos pos) {
        if (WorkerDigGoal.inDigReach(worker, pos)) {
            return true;
        }
        Path path = worker.getNavigation().createPath(pos, 1);
        if (path == null) {
            return false;
        }
        if (path.canReach()) {
            return true;
        }
        net.minecraft.world.level.pathfinder.Node end = path.getEndNode();
        if (end == null) {
            return false;
        }
        Vec3 target = Vec3.atCenterOf(pos);
        return Math.sqrt(Vec3.atBottomCenterOf(end.asBlockPos()).distanceToSqr(target)) < Math.sqrt(worker.position().distanceToSqr(target)) - 3.0D;
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
