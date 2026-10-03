package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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

/**
 * A feeder set to use composters looks after the composters inside the hive area: it flies up to each one that has room and feeds it, one
 * of the item it was given at a time from the hive's inventory, the way a player does; and when one is ready it takes the bone meal out
 * and puts it in the hive's inventory. A ready composter comes first.
 */
public class FeederCompostGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int SCAN_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    /** The ticks between one item going in and the next. */
    private static final int FEED_INTERVAL = 6;
    private static final int GIVE_UP_TICKS = 200;
    private static final int HEIGHT = 8;
    /** How high above the composter the feeder hovers, and how close, squared, to that spot it has to be to work. */
    private static final double HOVER_HEIGHT = 1.4D;
    private static final double WORK_DISTANCE_SQR = 1.5D * 1.5D;

    private final HiveFeeder feeder;
    @Nullable
    private BlockPos target;
    private int nextScan;
    private int repathCooldown;
    private int stuckTicks;
    private int feedCooldown;

    public FeederCompostGoal(HiveFeeder feeder) {
        this.feeder = feeder;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = feeder.findLocalHeart();
        return heart != null && feeder.behavior().useComposter() && feeder.compostItem() != null ? heart : null;
    }

    private static boolean ready(BlockState state) {
        return state.is(Blocks.COMPOSTER) && state.getValue(ComposterBlock.LEVEL) >= ComposterBlock.READY;
    }

    private boolean hungry(HiveHeart heart, BlockState state) {
        Item item = feeder.compostItem();
        return item != null && state.is(Blocks.COMPOSTER) && state.getValue(ComposterBlock.LEVEL) < ComposterBlock.MAX_LEVEL
                && heart.getStorage().countItem(item) > 0;
    }

    /** True while the composter still needs this feeder: it is ready to empty, or has room and the hive has the item. */
    private boolean needsWork(HiveHeart heart, BlockPos pos) {
        BlockState state = feeder.level().getBlockState(pos);
        return ready(state) || hungry(heart, state);
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = allowedHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        if (heart == null || feeder.tickCount < nextScan) {
            return false;
        }
        nextScan = feeder.tickCount + SCAN_INTERVAL;
        target = findComposter(heart);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = allowedHeart();
        return heart != null && target != null && needsWork(heart, target);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
        feedCooldown = 0;
    }

    @Override
    public void stop() {
        feeder.getNavigation().stop();
        target = null;
    }

    /** The nearest composter in the hive area that needs work: one that is ready first, otherwise one that has room. */
    @Nullable
    private BlockPos findComposter(HiveHeart heart) {
        ServerLevel level = (ServerLevel) feeder.level();
        AABB area = HiveArea.areaBox(level, heart);
        BlockPos origin = feeder.blockPosition();
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
        return bestReady != null ? bestReady : bestHungry;
    }

    @Override
    public void tick() {
        HiveHeart heart = allowedHeart();
        if (heart == null || target == null) {
            return;
        }
        ServerLevel level = (ServerLevel) feeder.level();
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 hover = new Vec3(center.x, target.getY() + HOVER_HEIGHT, center.z);
        if (feeder.position().distanceToSqr(hover) > WORK_DISTANCE_SQR) {
            if (++stuckTicks > GIVE_UP_TICKS) {
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
        BlockState state = level.getBlockState(target);

        if (ready(state)) {
            // Take the bone meal out, and into the hive's inventory.
            ComposterBlock.extractProduce(feeder, state, level, target);
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2.0D), item -> item.getItem().is(Items.BONE_MEAL))) {
                ItemStack left = heart.getStorage().addItem(drop.getItem());
                if (left.isEmpty()) {
                    drop.discard();
                } else {
                    drop.setItem(left);
                }
            }
            return;
        }
        if (--feedCooldown > 0 || !hungry(heart, state)) {
            return;
        }
        // One item in, as a player does it; what is put in is gone from the hive whether or not it made the pile rise.
        Item item = feeder.compostItem();
        ComposterBlock.insertItem(feeder, state, level, new ItemStack(item), target);
        heart.getStorage().removeItemType(item, 1);
        feedCooldown = FEED_INTERVAL;
    }
}
