package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;
import com.projecthivemind.entity.HiveCollector.PlantKind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The collector's planting tasks, crops and saplings alike. For each kind it has an item chosen and soil blocks to plant it
 * on. Whenever one of those spots is bare, it goes to the Hive Heart, takes one of the item from the hive's storage,
 * carries it to the spot, and plants it. When the plant is gone, it does it all again. Only ever inside the hive area.
 *
 * <p>If the item it carries is no longer wanted (another was chosen, or the spots were cleared), it takes it back to the Heart
 * and puts it back in the storage.
 */
public class CollectorPlantGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int CHECK_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    /** A leg longer than this (20 seconds) is given up on, and not tried again for a while. */
    private static final int MAX_TRIP_TICKS = 400;
    private static final int RETRY_DELAY = 200;
    private static final double PLANT_REACH_SQR = 2.0D * 2.0D;
    private static final double HEART_REACH_SQR = 3.0D * 3.0D;

    /** What the collector is doing: going for an item, taking it to a spot, or taking an unwanted one back. */
    private enum Phase {
        FETCH, PLANT, RETURN
    }

    /** What to do now, and for which kind of planting (null for taking an item back). */
    private record Plan(Phase phase, @Nullable PlantKind kind) {
    }

    private final HiveCollector collector;
    private int nextCheckTick;
    private int tripTicks;
    private int repathCooldown;
    private boolean finished;
    private Plan plan = new Plan(Phase.FETCH, PlantKind.CROP);

    public CollectorPlantGoal(HiveCollector collector) {
        this.collector = collector;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /** The block that would be planted at this spot, if it is bare, in the hive area and fit for the plant; otherwise null. */
    @Nullable
    private BlockState plantableState(HiveHeart heart, PlantKind kind, BlockPos soil) {
        Item item = collector.task(kind).item();
        if (item == null || !(collector.level() instanceof ServerLevel level)) {
            return null;
        }
        if (!HiveArea.containsXZ(heart, soil.getX() + 0.5D, soil.getZ() + 0.5D) || !level.isLoaded(soil)) {
            return null;
        }
        Block plant = HiveCollector.plantBlock(kind, item);
        if (plant == null) {
            return null;
        }
        BlockPos above = soil.above();
        BlockState there = level.getBlockState(above);
        if (!there.isAir() && !there.canBeReplaced()) {
            return null;
        }
        BlockState state = plant.defaultBlockState();
        return state.canSurvive(level, above) ? state : null;
    }

    /** Of this kind's spots that are ready to be planted, the one nearest to the collector; null if there are none. */
    @Nullable
    private BlockPos nearestPlantable(HiveHeart heart, PlantKind kind) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos spot : collector.task(kind).spots()) {
            if (plantableState(heart, kind, spot) != null) {
                double distance = collector.distanceToSqr(Vec3.atBottomCenterOf(spot.above()));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = spot;
                }
            }
        }
        return best;
    }

    /** Which kind of planting wants the item the collector is carrying, or null if none does (any more). */
    @Nullable
    private PlantKind kindOfCarried() {
        ItemStack carried = collector.plantCarried();
        for (PlantKind kind : PlantKind.values()) {
            Item wanted = collector.task(kind).item();
            if (wanted != null && carried.is(wanted) && !collector.task(kind).spots().isEmpty()) {
                return kind;
            }
        }
        return null;
    }

    @Nullable
    private Plan nextPlan(@Nullable HiveHeart heart) {
        if (heart == null) {
            return null;
        }
        if (!collector.plantCarried().isEmpty()) {
            PlantKind kind = kindOfCarried();
            if (kind == null) {
                return new Plan(Phase.RETURN, null);
            }
            // Carrying the right item: plant it when a spot is ready (and wait with it until then).
            return nearestPlantable(heart, kind) != null ? new Plan(Phase.PLANT, kind) : null;
        }
        for (PlantKind kind : PlantKind.values()) {
            Item item = collector.task(kind).item();
            if (item != null && nearestPlantable(heart, kind) != null && heart.getStorage().countItem(item) > 0) {
                return new Plan(Phase.FETCH, kind);
            }
        }
        return null;
    }

    @Override
    public boolean canUse() {
        // Not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        // An item on its way to the Heart is delivered before anything is planted.
        if (!collector.carried().isEmpty() || collector.tickCount < nextCheckTick) {
            return false;
        }
        nextCheckTick = collector.tickCount + CHECK_INTERVAL;
        Plan next = nextPlan(collector.findLocalHeart());
        if (next == null) {
            return false;
        }
        plan = next;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && tripTicks <= MAX_TRIP_TICKS && plan.equals(nextPlan(collector.findLocalHeart()));
    }

    @Override
    public void start() {
        tripTicks = 0;
        repathCooldown = 0;
        finished = false;
    }

    @Override
    public void stop() {
        if (tripTicks > MAX_TRIP_TICKS) {
            nextCheckTick = collector.tickCount + RETRY_DELAY;
        }
        collector.getNavigation().stop();
    }

    private void walkTo(Vec3 spot) {
        if (--repathCooldown <= 0) {
            collector.getNavigation().moveTo(spot.x, spot.y, spot.z, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    @Override
    public void tick() {
        tripTicks++;
        HiveHeart heart = collector.findLocalHeart();
        if (heart == null || !(collector.level() instanceof ServerLevel level)) {
            return;
        }
        PlantKind kind = plan.kind();
        switch (plan.phase()) {
            case FETCH -> {
                if (collector.distanceToSqr(heart) > HEART_REACH_SQR) {
                    walkTo(heart.position());
                    return;
                }
                // At the Heart: take one of the item out of the hive's storage.
                Item item = kind == null ? null : collector.task(kind).item();
                if (item != null && heart.getStorage().countItem(item) > 0) {
                    collector.setPlantCarried(heart.getStorage().removeItemType(item, 1));
                    level.playSound(null, collector.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F, 1.2F);
                }
                finished = true;
            }
            case PLANT -> {
                BlockPos soil = kind == null ? null : nearestPlantable(heart, kind);
                BlockState state = soil == null ? null : plantableState(heart, kind, soil);
                if (soil == null || state == null) {
                    return;
                }
                BlockPos above = soil.above();
                Vec3 spot = Vec3.atBottomCenterOf(above);
                if (collector.distanceToSqr(spot) > PLANT_REACH_SQR) {
                    walkTo(spot);
                    return;
                }
                // There: plant what it carries.
                level.setBlock(above, state, 3);
                collector.setPlantCarried(ItemStack.EMPTY);
                level.playSound(null, above, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
                finished = true;
            }
            case RETURN -> {
                if (collector.distanceToSqr(heart) > HEART_REACH_SQR) {
                    walkTo(heart.position());
                    return;
                }
                // Back at the Heart with something nobody wants any more: it goes back in the storage.
                ItemStack rest = heart.getStorage().addItem(collector.plantCarried());
                collector.setPlantCarried(ItemStack.EMPTY);
                if (!rest.isEmpty()) {
                    heart.spawnAtLocation(rest);
                }
                finished = true;
            }
        }
    }
}
