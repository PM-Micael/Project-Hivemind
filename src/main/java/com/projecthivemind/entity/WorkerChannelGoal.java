package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A worker set to channel walks up to a crop that is not fully grown and channels on it until it is: while it does, the crop
 * grows twice as fast. Only a worker that is not selected does this, and nothing but staying inside the border comes before it.
 *
 * <p>Twice as fast is done by giving the crop a second share of the game's own random ticks, the ones that make crops grow, at
 * exactly the rate the game gives every block. So the crop still needs what it always needs to grow (light, moist ground).
 */
public class WorkerChannelGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int SCAN_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    /** How close, squared, the worker stands to channel: about two blocks. */
    private static final double CHANNEL_DISTANCE_SQR = 2.5D * 2.5D;
    private static final int GIVE_UP_TICKS = 200;
    private static final int IGNORE_TICKS = 600;

    private final HiveWorker worker;
    @Nullable
    private BlockPos target;
    private int nextScan;
    private int repathCooldown;
    private int stuckTicks;
    private final Map<BlockPos, Integer> ignored = new HashMap<>();

    public WorkerChannelGoal(HiveWorker worker) {
        this.worker = worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = worker.findHeart();
        return heart != null && worker.behavior().channelCrops() && !heart.isUnitSelected(worker.getId()) ? heart : null;
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = allowedHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        if (heart == null || worker.tickCount < nextScan) {
            return false;
        }
        nextScan = worker.tickCount + SCAN_INTERVAL;
        ignored.values().removeIf(until -> until <= worker.tickCount);
        target = WorkerAutoJobs.findGrowing(worker, heart, ignored.keySet());
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return allowedHeart() != null && target != null && WorkerAutoJobs.isGrowing(worker.level().getBlockState(target));
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
    }

    @Override
    public void stop() {
        worker.getNavigation().stop();
        target = null;
    }

    @Override
    public void tick() {
        if (target == null || !(worker.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
        if (worker.position().distanceToSqr(center.x, worker.getY(), center.z) > CHANNEL_DISTANCE_SQR
                || Math.abs(worker.getY() - target.getY()) > 2.0D) {
            if (++stuckTicks > GIVE_UP_TICKS) {
                ignored.put(target, worker.tickCount + IGNORE_TICKS);
                target = null;
            } else if (--repathCooldown <= 0) {
                worker.getNavigation().moveTo(center.x, target.getY(), center.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        stuckTicks = 0;
        worker.getNavigation().stop();
        worker.getLookControl().setLookAt(center);

        // The extra growth: one more share of random ticks, at the rate the game gives every block.
        BlockState state = level.getBlockState(target);
        if (worker.getRandom().nextInt(4096) < level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING)) {
            state.randomTick(level, target, worker.getRandom());
        }
        // Bone meal from the hive, if the worker is set to use it: one every half second until the crop is grown.
        if (worker.behavior().useBoneMeal() && worker.tickCount % 10 == 0) {
            HiveHeart heart = worker.findHeart();
            if (heart != null) {
                useBoneMeal(level, heart);
            }
        }
        // What channelling looks like: sparkles rising from the crop, and the worker's arm moving now and then.
        if (worker.tickCount % 5 == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y, center.z, 2, 0.25D, 0.25D, 0.25D, 0.0D);
        }
        if (worker.tickCount % 20 == 0) {
            worker.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Take one bone meal from the hive and use it on the crop, as a player does: the crop grows a stage or more. */
    private void useBoneMeal(ServerLevel level, HiveHeart heart) {
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (stack.is(Items.BONE_MEAL)) {
                if (BoneMealItem.growCrop(stack, level, target)) {
                    heart.getStorage().setChanged();
                    level.levelEvent(1505, target, 15);
                    worker.swing(InteractionHand.MAIN_HAND);
                }
                return;
            }
        }
    }
}
