package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.projecthivemind.FeederBehavior;
import com.projecthivemind.HiveArea;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A feeder set to channel flies up to a crop (or a sapling, if set to) that is not fully grown and hovers over it, channelling until it
 * is: while it does, a crop grows 300 times as fast and a sapling 50 times as fast. A crop that is fully grown by it is broken, so that
 * the harvest drops on the ground; a sapling that grows is a tree, and that is left alone.
 *
 * <p>The growth is the game's own random ticks, given in extra helpings: the rate is worked out exactly each tick, not rolled for. So the
 * plant still needs what it always needs to grow (light, moist ground), and one that cannot grow is left alone after a while.
 */
public class FeederChannelGoal extends Goal {
    private static final double SPEED = 1.0D;
    /** How many times faster than normal a channelled crop grows, when it is able to grow at all. */
    private static final int GROWTH_MULTIPLIER = 300;
    /** The same for a sapling: 50 times faster. */
    private static final int SAPLING_MULTIPLIER = 50;
    /** A plant that has not grown for this long (10 seconds) while channelled cannot (too dark, too dry): the feeder leaves it. */
    private static final int STALL_TICKS = 200;
    private static final int SCAN_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    /** How high above the plant the feeder hovers. */
    private static final double HOVER_HEIGHT = 1.2D;
    /** How close, squared, to the spot above the plant the feeder has to be to channel. */
    private static final double CHANNEL_DISTANCE_SQR = 1.5D * 1.5D;
    private static final int GIVE_UP_TICKS = 200;
    private static final int IGNORE_TICKS = 600;
    /** How far above and below itself a feeder looks for plants. */
    private static final int HEIGHT = 8;

    private final HiveFeeder feeder;
    @Nullable
    private BlockPos target;
    private int nextScan;
    private int repathCooldown;
    private int stuckTicks;
    /** The tick of the feeder the extra growth was last given on, so that it is the same however often the goal gets to tick. */
    private int lastGrowTick;
    /** The part of a random tick the plant is owed but has not had yet: ticks are whole, the rate is not. */
    private double growthCarry;
    /** The last tick the channelled plant got older, for telling a plant that grows from one that cannot. */
    private int lastAdvanceTick;
    private final Map<BlockPos, Integer> ignored = new HashMap<>();

    public FeederChannelGoal(HiveFeeder feeder) {
        this.feeder = feeder;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private HiveHeart allowedHeart() {
        FeederBehavior behavior = feeder.behavior();
        return behavior.channelCrops() || behavior.channelSaplings() ? feeder.findLocalHeart() : null;
    }

    /** True for what this feeder is set to channel on: young crops, saplings, or both, according to its settings. */
    private boolean channelable(BlockState state) {
        FeederBehavior behavior = feeder.behavior();
        return (behavior.channelCrops() && WorkerAutoJobs.isGrowing(state)) || (behavior.channelSaplings() && WorkerAutoJobs.isSapling(state));
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = allowedHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        if (heart == null || feeder.tickCount < nextScan) {
            return false;
        }
        nextScan = feeder.tickCount + SCAN_INTERVAL;
        ignored.values().removeIf(until -> until <= feeder.tickCount);
        target = findGrowing(heart);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return allowedHeart() != null && target != null && channelable(feeder.level().getBlockState(target));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        lastGrowTick = feeder.tickCount;
        lastAdvanceTick = feeder.tickCount;
        growthCarry = 0.0D;
        repathCooldown = 0;
        stuckTicks = 0;
    }

    @Override
    public void stop() {
        feeder.getNavigation().stop();
        target = null;
    }

    /** The nearest plant inside the hive area that this feeder is set to channel on, other than those to leave alone for now, or null. */
    @Nullable
    private BlockPos findGrowing(HiveHeart heart) {
        if (!(feeder.level() instanceof ServerLevel level)) {
            return null;
        }
        AABB area = HiveArea.areaBox(level, heart);
        BlockPos origin = feeder.blockPosition();
        List<BlockPos> growing = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) area.minX; x < (int) area.maxX; x++) {
            for (int z = (int) area.minZ; z < (int) area.maxZ; z++) {
                if (!level.hasChunkAt(pos.set(x, origin.getY(), z))) {
                    continue;
                }
                for (int y = origin.getY() - HEIGHT; y <= origin.getY() + HEIGHT; y++) {
                    pos.set(x, y, z);
                    if (channelable(level.getBlockState(pos)) && !ignored.containsKey(pos)) {
                        growing.add(pos.immutable());
                    }
                }
            }
        }
        growing.sort(Comparator.comparingDouble(plant -> plant.distSqr(origin)));
        return growing.isEmpty() ? null : growing.get(0);
    }

    @Override
    public void tick() {
        if (target == null || !(feeder.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 hover = new Vec3(center.x, target.getY() + HOVER_HEIGHT, center.z);
        if (feeder.position().distanceToSqr(hover) > CHANNEL_DISTANCE_SQR) {
            if (++stuckTicks > GIVE_UP_TICKS) {
                ignored.put(target, feeder.tickCount + IGNORE_TICKS);
                target = null;
            } else if (--repathCooldown <= 0) {
                feeder.getNavigation().moveTo(hover.x, hover.y, hover.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        stuckTicks = 0;
        feeder.getNavigation().stop();
        feeder.getLookControl().setLookAt(center);

        // The extra growth, worked out exactly: the extra random ticks a block gets per tick at this multiplier, with the fraction
        // carried over. Each goes through the plant's own growth code, so light and soil still decide whether it grows.
        int elapsed = Math.max(1, Math.min(feeder.tickCount - lastGrowTick, 10));
        lastGrowTick = feeder.tickCount;
        int ageBefore = ageOf(level.getBlockState(target));
        growthCarry += (WorkerAutoJobs.isSapling(level.getBlockState(target)) ? SAPLING_MULTIPLIER : GROWTH_MULTIPLIER)
                * level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING) / 4096.0D * elapsed;
        int extraTicks = (int) growthCarry;
        growthCarry -= extraTicks;
        for (int i = 0; i < extraTicks; i++) {
            BlockState state = level.getBlockState(target);
            if (!channelable(state)) {
                break;
            }
            state.randomTick(level, target, feeder.getRandom());
        }
        // Bone meal from the hive, if the feeder is set to use it: one every half second until the plant is grown.
        if (feeder.behavior().useBoneMeal() && feeder.tickCount % 10 == 0 && channelable(level.getBlockState(target))) {
            HiveHeart heart = feeder.findLocalHeart();
            if (heart != null) {
                useBoneMeal(level, heart);
            }
        }
        BlockState after = level.getBlockState(target);
        if (ageOf(after) > ageBefore) {
            // It grew: a burst of green, so growth is seen when it happens.
            lastAdvanceTick = feeder.tickCount;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y, center.z, 6, 0.3D, 0.3D, 0.3D, 0.0D);
        }
        if (WorkerAutoJobs.isGrown(after)) {
            // Fully grown while being channelled on: the feeder breaks it, and the harvest drops on the ground.
            level.destroyBlock(target, true, feeder);
            target = null;
            return;
        }
        if (channelable(after) && ageOf(after) <= ageBefore) {
            if (feeder.tickCount - lastAdvanceTick > STALL_TICKS) {
                // Ten seconds and not a stage: it cannot grow here (dark, dry), so go and find another.
                ignored.put(target, feeder.tickCount + IGNORE_TICKS);
                target = null;
                return;
            }
            if (feeder.tickCount - lastAdvanceTick > 40 && feeder.tickCount % 10 == 0) {
                // Nothing for two seconds: grey puffs, so it is clear the plant is refusing and not just slow.
                level.sendParticles(ParticleTypes.SMOKE, center.x, center.y + 0.2D, center.z, 3, 0.2D, 0.1D, 0.2D, 0.0D);
            }
        }
    }

    /** How old a growing plant is (its growth stage), or -1 if the block is not one. */
    private static int ageOf(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.getAge(state);
        }
        if (state.getBlock() instanceof SaplingBlock) {
            return state.getValue(SaplingBlock.STAGE);
        }
        if (state.getBlock() instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE);
        }
        return -1;
    }

    /** Take one bone meal from the hive and use it on the plant, as a player does: it grows a stage or more. */
    private void useBoneMeal(ServerLevel level, HiveHeart heart) {
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (stack.is(Items.BONE_MEAL)) {
                if (BoneMealItem.growCrop(stack, level, target)) {
                    heart.getStorage().setChanged();
                    level.levelEvent(1505, target, 15);
                }
                return;
            }
        }
    }
}
