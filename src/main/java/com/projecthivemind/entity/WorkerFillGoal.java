package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;

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
import net.minecraft.world.phys.AABB;
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
    /** How long a worker may spend unable to get to a gap before it gives up on it for a while, and for how long. */
    private static final int GIVE_UP_TICKS = 200;
    private static final int IGNORE_TICKS = 600;

    private final HiveWorker worker;
    @Nullable
    private BlockPos target;
    private int nextScan;
    private int repathCooldown;
    private int placeCooldown;
    private int stuckTicks;
    private final Map<BlockPos, Integer> ignored = new HashMap<>();

    public WorkerFillGoal(HiveWorker worker) {
        this.worker = worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Set to, with a block chosen, with nothing else to do, and not selected. */
    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = worker.findHeart();
        if (heart == null || !worker.behavior().flattenGround() || worker.fillItem() == null || worker.action() != null
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
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = allowedHeart();
        return heart != null && target != null && stillGap(target) && hasBlock(heart);
    }

    @Override
    public void start() {
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

    /** The nearest gap the worker can get to, or null: the lowest missing block of a column, in the hive area. */
    @Nullable
    private BlockPos findGap(HiveHeart heart) {
        ServerLevel level = (ServerLevel) worker.level();
        ignored.values().removeIf(until -> until <= worker.tickCount);
        int floorTop = (int) Math.floor(heart.getY()) - 1;
        AABB area = HiveArea.areaBox(level, heart);
        BlockPos origin = worker.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) area.minX; x < (int) area.maxX; x++) {
            for (int z = (int) area.minZ; z < (int) area.maxZ; z++) {
                // Reading a block in an unloaded chunk would make the game load it, so never touch those.
                if (!level.hasChunkAt(pos.set(x, floorTop, z))) {
                    continue;
                }
                int groundTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (groundTop >= floorTop || floorTop - groundTop > MAX_DEPTH) {
                    continue;
                }
                BlockPos ground = new BlockPos(x, groundTop, z);
                BlockState groundState = level.getBlockState(ground);
                // Only on solid ground: not on water, not on something a block cannot stand on.
                if (!groundState.getFluidState().isEmpty() || !groundState.isFaceSturdy(level, ground, Direction.UP)) {
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
        if (best != null && !reachable(best)) {
            ignored.put(best, worker.tickCount + IGNORE_TICKS);
            return null;
        }
        return best;
    }

    private boolean reachable(BlockPos pos) {
        if (WorkerDigGoal.inDigReach(worker, pos)) {
            return true;
        }
        net.minecraft.world.level.pathfinder.Path path = worker.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }
}
