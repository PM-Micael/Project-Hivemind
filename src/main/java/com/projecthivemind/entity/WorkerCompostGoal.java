package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.projecthivemind.HiveArea;

/**
 * A worker set to use composters looks after the composters inside the hive area: it feeds each one that has room, one of the item it
 * was given at a time from the hive's inventory, the way a player does; and when one is ready it takes the bone meal out and puts it in
 * the hive's inventory. A ready composter comes first. Only while the worker is idle and not selected.
 */
public class WorkerCompostGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int SCAN_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    /** The ticks between one item going in and the next. */
    private static final int FEED_INTERVAL = 6;
    private static final int GIVE_UP_TICKS = 200;
    private static final int HEIGHT = 6;

    private final HiveWorker worker;
    @Nullable
    private BlockPos target;
    private int nextScan;
    private int repathCooldown;
    private int stuckTicks;
    private int feedCooldown;

    public WorkerCompostGoal(HiveWorker worker) {
        this.worker = worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = worker.findHeart();
        return heart != null && worker.behavior().useComposter() && worker.compostItem() != null && worker.action() == null
                && !heart.isUnitSelected(worker.getId()) ? heart : null;
    }

    private static boolean ready(BlockState state) {
        return state.is(Blocks.COMPOSTER) && state.getValue(ComposterBlock.LEVEL) >= ComposterBlock.READY;
    }

    private boolean hungry(HiveHeart heart, BlockState state) {
        Item item = worker.compostItem();
        return item != null && state.is(Blocks.COMPOSTER) && state.getValue(ComposterBlock.LEVEL) < ComposterBlock.MAX_LEVEL
                && heart.getStorage().countItem(item) > 0;
    }

    /** True while the composter still needs this worker: it is ready to empty, or has room and the hive has the item. */
    private boolean needsWork(HiveHeart heart, BlockPos pos) {
        BlockState state = worker.level().getBlockState(pos);
        return ready(state) || hungry(heart, state);
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = allowedHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        if (heart == null || worker.tickCount < nextScan) {
            return false;
        }
        nextScan = worker.tickCount + SCAN_INTERVAL;
        target = findComposter(heart);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = allowedHeart();
        return heart != null && target != null && needsWork(heart, target);
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
        feedCooldown = 0;
    }

    @Override
    public void stop() {
        worker.getNavigation().stop();
        target = null;
    }

    /** The nearest composter in the hive area that needs work: one that is ready first, otherwise one that has room. */
    @Nullable
    private BlockPos findComposter(HiveHeart heart) {
        ServerLevel level = (ServerLevel) worker.level();
        AABB area = HiveArea.areaBox(level, heart);
        BlockPos origin = worker.blockPosition();
        BlockPos bestReady = null;
        BlockPos bestHungry = null;
        double readyDistance = Double.MAX_VALUE;
        double hungryDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) area.minX; x < (int) area.maxX; x++) {
            for (int z = (int) area.minZ; z < (int) area.maxZ; z++) {
                if (!level.hasChunkAt(pos.set(x, origin.getY(), z))) {
                    continue;
                }
                for (int y = origin.getY() - HEIGHT; y <= origin.getY() + HEIGHT; y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(Blocks.COMPOSTER)) {
                        continue;
                    }
                    double distance = pos.distSqr(origin);
                    if (ready(state) && distance < readyDistance) {
                        readyDistance = distance;
                        bestReady = pos.immutable();
                    } else if (hungry(heart, state) && distance < hungryDistance) {
                        hungryDistance = distance;
                        bestHungry = pos.immutable();
                    }
                }
            }
        }
        BlockPos best = bestReady != null ? bestReady : bestHungry;
        return best != null && reachable(best) ? best : null;
    }

    private boolean reachable(BlockPos pos) {
        if (WorkerDigGoal.inDigReach(worker, pos)) {
            return true;
        }
        net.minecraft.world.level.pathfinder.Path path = worker.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }

    @Override
    public void tick() {
        HiveHeart heart = allowedHeart();
        if (heart == null || target == null) {
            return;
        }
        ServerLevel level = (ServerLevel) worker.level();
        if (!WorkerDigGoal.inDigReach(worker, target)) {
            if (++stuckTicks > GIVE_UP_TICKS) {
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
        BlockState state = level.getBlockState(target);

        if (ready(state)) {
            // Take the bone meal out, and into the hive's inventory.
            ComposterBlock.extractProduce(worker, state, level, target);
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2.0D), item -> item.getItem().is(Items.BONE_MEAL))) {
                ItemStack left = heart.getStorage().addItem(drop.getItem());
                if (left.isEmpty()) {
                    drop.discard();
                } else {
                    drop.setItem(left);
                }
            }
            worker.swing(InteractionHand.MAIN_HAND);
            return;
        }
        if (--feedCooldown > 0 || !hungry(heart, state)) {
            return;
        }
        // One item in, as a player does it; what is put in is gone from the hive whether or not it made the pile rise.
        Item item = worker.compostItem();
        ItemStack one = new ItemStack(item);
        ComposterBlock.insertItem(worker, state, level, one, target);
        heart.getStorage().removeItemType(item, 1);
        worker.swing(InteractionHand.MAIN_HAND);
        feedCooldown = FEED_INTERVAL;
    }
}
