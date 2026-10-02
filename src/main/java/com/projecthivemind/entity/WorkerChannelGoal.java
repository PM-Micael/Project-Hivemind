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
 * A worker set to channel walks up to a crop (or a sapling, if set to) that is not fully grown and channels on it until it is: while it does, it
 * grows 300 times as fast. Only a worker that is not selected does this, and nothing but staying inside the border comes before it.
 *
 * <p>That is done by giving the crop extra helpings of the game's own random ticks, the ones that make crops grow: the extra rate is
 * worked out exactly each tick (see {@link #GROWTH_MULTIPLIER}), not rolled for. So the crop still needs what it always needs to grow
 * (light, moist ground), and a crop that cannot grow is left alone after a while and the worker moves on.
 */
public class WorkerChannelGoal extends Goal {
    private static final double SPEED = 1.0D;
    /** How many times faster than normal a channelled crop grows, when it is able to grow at all. */
    private static final int GROWTH_MULTIPLIER = 300;
    /** The same for a sapling: 50 times faster. */
    private static final int SAPLING_MULTIPLIER = 50;
    /** A crop that has not grown for this long (10 seconds) while channelled cannot (too dark, too dry): the worker leaves it. */
    private static final int STALL_TICKS = 200;
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
    /** The next time a sapling being channelled is checked against there being a tree to fell instead. */
    private int nextFellCheck;
    private int repathCooldown;
    private int stuckTicks;
    /** The tick of the worker the extra growth was last given on, so that it is the same however often the goal gets to tick. */
    private int lastGrowTick;
    /** The part of a random tick the crop is owed but has not had yet: ticks are whole, the rate is not. */
    private double growthCarry;
    /** The last tick the channelled crop got older, for telling a crop that grows from one that cannot. */
    private int lastAdvanceTick;
    private final Map<BlockPos, Integer> ignored = new HashMap<>();

    public WorkerChannelGoal(HiveWorker worker) {
        this.worker = worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = worker.findLocalHeart();
        return heart != null && (worker.behavior().channelCrops() || worker.behavior().channelSaplings()) && !heart.isUnitSelected(worker.getId()) ? heart : null;
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
        target = WorkerAutoJobs.findGrowing(worker, heart, ignored.keySet(), !worker.hasTreeToFell(heart));
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = allowedHeart();
        if (heart == null || target == null || !WorkerAutoJobs.channelable(worker, worker.level().getBlockState(target))) {
            return false;
        }
        // A sapling gives way to a tree to fell: every so often, see if one has come up.
        if (WorkerAutoJobs.isSapling(worker.level().getBlockState(target)) && worker.tickCount >= nextFellCheck) {
            nextFellCheck = worker.tickCount + 20;
            return !worker.hasTreeToFell(heart);
        }
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        lastGrowTick = worker.tickCount;
        lastAdvanceTick = worker.tickCount;
        growthCarry = 0.0D;
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

        // The extra growth, worked out exactly: the extra random ticks a block gets per tick at this multiplier, with the fraction
        // carried over. Each goes through the crop's own growth code, so light and soil still decide whether it grows.
        int elapsed = Math.max(1, Math.min(worker.tickCount - lastGrowTick, 10));
        lastGrowTick = worker.tickCount;
        int ageBefore = ageOf(level.getBlockState(target));
        growthCarry += (WorkerAutoJobs.isSapling(level.getBlockState(target)) ? SAPLING_MULTIPLIER : GROWTH_MULTIPLIER) * level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING) / 4096.0D * elapsed;
        int extraTicks = (int) growthCarry;
        growthCarry -= extraTicks;
        for (int i = 0; i < extraTicks; i++) {
            BlockState state = level.getBlockState(target);
            if (!WorkerAutoJobs.channelable(worker, state)) {
                break;
            }
            state.randomTick(level, target, worker.getRandom());
        }
        BlockState after = level.getBlockState(target);
        if (ageOf(after) > ageBefore) {
            // It grew: a burst of green, so growth is seen when it happens.
            lastAdvanceTick = worker.tickCount;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y, center.z, 6, 0.3D, 0.3D, 0.3D, 0.0D);
        } else if (WorkerAutoJobs.channelable(worker, after)) {
            if (worker.tickCount - lastAdvanceTick > STALL_TICKS) {
                // Ten seconds and not a stage: it cannot grow here (dark, dry), so go and find another.
                ignored.put(target, worker.tickCount + IGNORE_TICKS);
                target = null;
                return;
            }
            if (worker.tickCount - lastAdvanceTick > 40 && worker.tickCount % 10 == 0) {
                // Nothing for two seconds: grey puffs, so it is clear the crop is refusing and not just slow.
                level.sendParticles(ParticleTypes.SMOKE, center.x, center.y + 0.2D, center.z, 3, 0.2D, 0.1D, 0.2D, 0.0D);
            }
        }
        // Bone meal from the hive, if the worker is set to use it: one every half second until the crop is grown.
        if (worker.behavior().useBoneMeal() && worker.tickCount % 10 == 0) {
            HiveHeart heart = worker.findLocalHeart();
            if (heart != null) {
                useBoneMeal(level, heart);
            }
        }
        // The worker's arm moves now and then while it channels.
        if (worker.tickCount % 20 == 0) {
            worker.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** How old a growing crop is (its growth stage), or -1 if the block is not a growing crop. */
    private static int ageOf(BlockState state) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
            return crop.getAge(state);
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
            return state.getValue(net.minecraft.world.level.block.SaplingBlock.STAGE);
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.NetherWartBlock) {
            return state.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE);
        }
        return -1;
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
