package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * An idle worker, not selected, set to flatten the ground: fills the gaps in the hive area's ground with the block it was given,
 * taken from the hive's inventory, up to the level of the Hive Heart's own floor. It only ever fills gaps. It never digs, and it
 * never places anything on ground that is already at that level or higher, so bumps and hills are left alone.
 *
 * <p>A gap is a column of open air above the ground (the sky is open above it, so caves and the insides of buildings are left
 * alone) whose ground is below the Heart's floor. The lowest missing block of the column is filled first, and the next scan
 * finds the one above it.
 */
public class WorkerFillGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int SCAN_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    private static final int PLACE_INTERVAL = 5;
    /** How far below the Heart's floor a column of ground may be and still be filled: a cliff or a pit this deep is not a gap. */
    private static final int MAX_DEPTH = 16;
    /** The hive area is only filled where the ground is one block under the Heart's floor: the Heart's own floor block and the one under it are all it cares about. */
    private static final int HIVE_AREA_MAX_DEPTH = 1;
    /** How long a worker may spend unable to get to a gap before it gives up on it for a while, and for how long. */
    private static final int GIVE_UP_TICKS = 200;
    private static final int IGNORE_TICKS = 600;

    private final HiveWorker worker;
    @Nullable
    private BlockPos target;
    private int nextScan;
    /** How many looks in a row found nothing to fill, and whether the last one found a gap it could not reach (another may be reachable: look again soon). */
    private int idleScans;
    private boolean retrySoon;
    private int repathCooldown;
    private int placeCooldown;
    private int stuckTicks;
    /** The nearest the worker has been to the gap it is walking to, for telling a long walk from being stuck. */
    private double nearest = Double.MAX_VALUE;
    private final Map<BlockPos, Integer> ignored = new HashMap<>();

    public WorkerFillGoal(HiveWorker worker) {
        this.worker = worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Set to, with a block chosen, with nothing else to do, and not selected. */
    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = worker.findLocalHeart();
        if (heart == null || !(worker.behavior().flattenGround() || worker.behavior().flattenTeam()) || worker.fillItem() == null || worker.action() != null
                || heart.isUnitSelected(worker.getId())) {
            return null;
        }
        return heart;
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = allowedHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        if (heart == null || worker.tickCount < nextScan) {
            return false;
        }
        nextScan = worker.tickCount + SCAN_INTERVAL;
        if (!hasBlock(heart)) {
            return false;
        }
        target = findGap(heart);
        if (target != null || retrySoon) {
            idleScans = 0;
        } else {
            // Nothing to fill: the whole area is not looked over again at once. Each empty look waits longer (up to 16 times as long), a little differently for each worker.
            idleScans = Math.min(idleScans + 1, 4);
            nextScan = worker.tickCount + SCAN_INTERVAL * (1 << idleScans) + worker.getRandom().nextInt(SCAN_INTERVAL);
        }
        retrySoon = false;
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = allowedHeart();
        return heart != null && target != null && stillGap(target) && hasBlock(heart);
    }

    @Override
    public void start() {
        nearest = Double.MAX_VALUE;
        repathCooldown = 0;
        stuckTicks = 0;
        placeCooldown = 0;
    }

    @Override
    public void stop() {
        if (worker.action() == null) {
            worker.getNavigation().stop();
        }
        target = null;
    }

    @Override
    public void tick() {
        HiveHeart heart = allowedHeart();
        if (heart == null || target == null) {
            return;
        }
        if (!WorkerDigGoal.inDigReach(worker, target)) {
            // A long walk across the area is not stuck: the count starts again whenever the worker has got a few blocks nearer.
            double distance = worker.position().distanceTo(Vec3.atCenterOf(target));
            if (distance < nearest - 2.0D) {
                nearest = distance;
                stuckTicks = 0;
            }
            if (++stuckTicks > GIVE_UP_TICKS) {
                ignored.put(target, worker.tickCount + IGNORE_TICKS);
                target = null;
            } else if (--repathCooldown <= 0) {
                Vec3 center = Vec3.atCenterOf(target);
                worker.getNavigation().moveTo(center.x, center.y, center.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        stuckTicks = 0;
        worker.getNavigation().stop();
        worker.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (--placeCooldown > 0) {
            return;
        }
        place(heart, target);
        placeCooldown = PLACE_INTERVAL;
    }

    /** True while the position is still empty, replaceable, and nothing is standing in it. */
    private boolean stillGap(BlockPos pos) {
        ServerLevel level = (ServerLevel) worker.level();
        BlockState state = level.getBlockState(pos);
        Item item = worker.fillItem();
        Block block = item == null ? null : HiveWorker.fillBlock(item);
        return block != null && state.canBeReplaced() && state.getFluidState().isEmpty()
                && level.isUnobstructed(block.defaultBlockState(), pos, CollisionContext.empty());
    }

    private boolean hasBlock(HiveHeart heart) {
        Item item = worker.fillItem();
        if (item == null) {
            return false;
        }
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            if (heart.getStorage().getItem(i).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** Take one of the block from the hive and put it in the gap. */
    private void place(HiveHeart heart, BlockPos pos) {
        Item item = worker.fillItem();
        Block block = item == null ? null : HiveWorker.fillBlock(item);
        if (block == null || !stillGap(pos)) {
            target = null;
            return;
        }
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (stack.is(item)) {
                stack.shrink(1);
                heart.getStorage().setChanged();
                ServerLevel level = (ServerLevel) worker.level();
                BlockState state = block.defaultBlockState();
                level.setBlock(pos, state, Block.UPDATE_ALL);
                level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
                worker.swing(InteractionHand.MAIN_HAND);
                target = null;
                // Straight on to the next one, which is most often right next to this one.
                nextScan = 0;
                return;
            }
        }
        target = null;
    }

    /** The nearest gap the worker can get to, or null: the lowest missing block of a column, in the regions it is set to flatten. */
    @Nullable
    private BlockPos findGap(HiveHeart heart) {
        ServerLevel level = (ServerLevel) worker.level();
        ignored.values().removeIf(until -> until <= worker.tickCount);
        BlockPos origin = worker.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (FlattenRegion region : FlattenRegion.of(worker, heart)) {
            int floorTop = region.floorTop();
            for (int x = region.minX(); x < region.maxX(); x++) {
                for (int z = region.minZ(); z < region.maxZ(); z++) {
                    if (!region.contains(x, z)) {
                        continue;
                    }
                    // Reading a block in an unloaded chunk would make the game load it, so never touch those.
                    if (!level.hasChunkAt(pos.set(x, floorTop, z))) {
                        continue;
                    }
                    // The hive area is levelled one layer only, the Heart's floor: a block goes in a column that has none there, if there is something
                    // to put it against (ground under it, or the floor beside it, so a platform in the air is extended from its edge).
                    if (region.circle() == null) {
                        BlockPos layerGap = hiveFloorGap(level, x, floorTop, z);
                        if (layerGap != null && !ignored.containsKey(layerGap) && stillGap(layerGap)) {
                            double layerDistance = layerGap.distSqr(origin);
                            if (layerDistance < bestDistance) {
                                bestDistance = layerDistance;
                                best = layerGap;
                            }
                        }
                        continue;
                    }
                    int groundTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    if (groundTop >= floorTop || floorTop - groundTop > (region.circle() == null ? HIVE_AREA_MAX_DEPTH : MAX_DEPTH)) {
                        continue;
                    }
                    BlockPos ground = new BlockPos(x, groundTop, z);
                    BlockState groundState = level.getBlockState(ground);
                    // On solid ground, or on the surface of water: over water it fills from the surface up, one block at a time from the shore, so
                    // a lake becomes a causeway of blocks at the floor's level. Not on lava, and not on something a block cannot stand on.
                    boolean onWater = groundState.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                    // The hive area is only filled on solid ground one block under the Heart's floor: a pond or river there is a pit, not a gap, and the
                    // water's surface being at that level does not make it ground. (Only the team area bridges water.)
                    if (onWater && region.circle() == null) {
                        continue;
                    }
                    if (!onWater && (!groundState.getFluidState().isEmpty() || !groundState.isFaceSturdy(level, ground, Direction.UP))) {
                        continue;
                    }
                    BlockPos gap = ground.above();
                    if (ignored.containsKey(gap) || !stillGap(gap)) {
                        continue;
                    }
                    double distance = gap.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = gap;
                    }
                }
            }
        }
        if (best != null && !reachable(best)) {
            ignored.put(best, worker.tickCount + IGNORE_TICKS);
            retrySoon = true;
            return null;
        }
        return best;
    }

    /**
     * The spot at the Heart's floor layer in this column, if a block belongs there: nothing solid is at or above the layer in the column (no floor
     * yet, no hill, no roof), and the block can be put against something: solid ground right under it, or the floor of the next column over (its
     * top exactly at this layer), so a platform with nothing under it is extended sideways from its edge.
     */
    @Nullable
    private BlockPos hiveFloorGap(ServerLevel level, int x, int floorTop, int z) {
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) > floorTop) {
            return null;
        }
        BlockPos spot = new BlockPos(x, floorTop, z);
        BlockPos below = spot.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.getFluidState().isEmpty() && belowState.isFaceSturdy(level, below, Direction.UP)) {
            return spot;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int nx = x + side.getStepX();
            int nz = z + side.getStepZ();
            if (!level.hasChunkAt(new BlockPos(nx, floorTop, nz)) || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1 != floorTop) {
                continue;
            }
            BlockPos neighbor = new BlockPos(nx, floorTop, nz);
            BlockState neighborState = level.getBlockState(neighbor);
            if (neighborState.getFluidState().isEmpty() && neighborState.isFaceSturdy(level, neighbor, side.getOpposite())) {
                return spot;
            }
        }
        return null;
    }

    private boolean reachable(BlockPos pos) {
        return WorkerAutoJobs.canWalkToward(worker, pos);
    }
}
