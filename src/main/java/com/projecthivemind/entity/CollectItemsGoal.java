package com.projecthivemind.entity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.projecthivemind.HiveInfection;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Pick up a dropped item near the Hive Heart, carry it back, and put it in the hive's inventory.
 * Only items the hive has room for are fetched, so a full hive makes collectors idle.
 */
public class CollectItemsGoal extends Goal {
    private static final double SPEED = 1.1D;
    private static final double PICKUP_DISTANCE_SQR = 1.5D * 1.5D;
    private static final double DELIVER_DISTANCE_SQR = 2.5D * 2.5D;
    /** Ticks between scans for items while idle. */
    private static final int SEARCH_INTERVAL = 20;
    /** Ticks between re-plans of the walking path. */
    private static final int REPATH_INTERVAL = 10;

    // The failsafe. A collector that stops making progress, or spends too long on one trip, resets what it is doing.
    /** How often progress is checked, in ticks. */
    private static final int PROGRESS_INTERVAL = 20;
    /** It must have moved at least this far (squared) between checks to count as making progress: half a block. */
    private static final double MIN_PROGRESS_SQR = 0.5D * 0.5D;
    /** This many checks in a row without progress (3 seconds) and it is stuck. */
    private static final int STUCK_CHECKS = 3;
    /** No single trip should take longer than this (30 seconds), however it is going. */
    private static final int MAX_TRIP_TICKS = 600;
    /** How long an item is ignored after the collector gave up on it (20 seconds). */
    private static final int IGNORE_TICKS = 400;

    private final HiveCollector collector;
    @Nullable
    private ItemEntity target;
    private int repathCooldown;
    /** The earliest tick (by the collector's tickCount) the next scan for items may happen. */
    private int nextSearchTick;

    private int tripTicks;
    private int stuckChecks;
    private Vec3 checkpoint = Vec3.ZERO;
    private int checkpointTick;
    /** Items the collector gave up on, by entity id, and the tick each stops being ignored. Not saved. */
    private final Map<Integer, Integer> ignored = new HashMap<>();

    public CollectItemsGoal(HiveCollector collector) {
        this.collector = collector;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = collector.findHeart();
        if (heart == null) {
            return false;
        }
        if (!collector.carried().isEmpty()) {
            return true;
        }
        // Do not gate on tickCount % N: the game only evaluates goals on ticks where tickCount + entityId is even, and
        // entity ids change every time a world loads, so a modulo check can silently never line up for some entities.
        if (collector.tickCount < nextSearchTick) {
            return false;
        }
        nextSearchTick = collector.tickCount + SEARCH_INTERVAL;
        target = findItem(heart);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (collector.findHeart() == null) {
            return false;
        }
        return !collector.carried().isEmpty() || (target != null && target.isAlive());
    }

    @Override
    public void start() {
        tripTicks = 0;
        stuckChecks = 0;
        checkpoint = collector.position();
        checkpointTick = collector.tickCount;
    }

    @Override
    public void tick() {
        HiveHeart heart = collector.findHeart();
        if (heart == null) {
            return;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        }

        // The failsafe: reset if it has gone nowhere for a while, or this trip has simply taken too long.
        if (++tripTicks > MAX_TRIP_TICKS || notMakingProgress()) {
            giveUp();
            return;
        }

        if (!collector.carried().isEmpty()) {
            deliverTo(heart);
        } else if (target != null) {
            fetch(heart, target);
        }
    }

    @Override
    public void stop() {
        collector.getNavigation().stop();
        target = null;
        repathCooldown = 0;
    }

    /**
     * True once the collector has barely moved for several checks in a row. Every goal tick is spent travelling
     * (picking up and delivering end the trip at once), so standing still means it is wedged or its path is blocked.
     */
    private boolean notMakingProgress() {
        if (collector.tickCount - checkpointTick < PROGRESS_INTERVAL) {
            return false;
        }
        boolean moved = collector.position().distanceToSqr(checkpoint) >= MIN_PROGRESS_SQR;
        checkpoint = collector.position();
        checkpointTick = collector.tickCount;
        stuckChecks = moved ? 0 : stuckChecks + 1;
        return stuckChecks >= STUCK_CHECKS;
    }

    /**
     * Reset the current action. The item it was after is ignored for a while so it does not go straight back to it. An
     * item it was carrying and could not deliver is put down, also ignored for a while, instead of being held forever.
     */
    private void giveUp() {
        collector.getNavigation().stop();
        if (!collector.carried().isEmpty()) {
            ItemEntity dropped = collector.spawnAtLocation(collector.carried());
            collector.setCarried(ItemStack.EMPTY);
            if (dropped != null) {
                ignore(dropped);
            }
        } else if (target != null) {
            ignore(target);
        }
        target = null;
    }

    private void ignore(ItemEntity item) {
        ignored.put(item.getId(), collector.tickCount + IGNORE_TICKS);
    }

    private boolean isIgnored(ItemEntity item) {
        Integer until = ignored.get(item.getId());
        return until != null && collector.tickCount < until;
    }

    private void fetch(HiveHeart heart, ItemEntity item) {
        if (collector.distanceToSqr(item) <= PICKUP_DISTANCE_SQR) {
            ItemStack stack = item.getItem();
            if (heart.getStorage().canAddItem(stack)) {
                collector.setCarried(stack.copy());
                item.discard();
            }
            target = null;
        } else if (repathCooldown == 0) {
            collector.getNavigation().moveTo(item, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    private void deliverTo(HiveHeart heart) {
        if (collector.distanceToSqr(heart) <= DELIVER_DISTANCE_SQR) {
            ItemStack leftover = heart.getStorage().addItem(collector.carried());
            collector.setCarried(ItemStack.EMPTY);
            if (!leftover.isEmpty()) {
                // The hive filled up while we were walking; do not lose the items.
                collector.spawnAtLocation(leftover);
            }
        } else if (repathCooldown == 0) {
            collector.getNavigation().moveTo(heart.getX(), heart.getY(), heart.getZ(), SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    @Nullable
    private ItemEntity findItem(HiveHeart heart) {
        // The collector's job is the hive area, whether or not the creep is still there, plus however far past its edge
        // the player has allowed. The extra range only reaches sideways: the area already covers every height.
        int extra = collector.behavior().extraRange();
        ignored.values().removeIf(until -> until <= collector.tickCount);
        List<ItemEntity> items = collector.level().getEntitiesOfClass(ItemEntity.class,
                HiveInfection.areaBox((ServerLevel) collector.level(), heart).inflate(extra, 0.0D, extra),
                item -> item.isAlive() && !item.getItem().isEmpty() && !isIgnored(item)
                        && heart.getStorage().canAddItem(item.getItem()));
        return items.stream()
                .sorted(Comparator.comparingDouble(collector::distanceToSqr))
                .filter(item -> collector.getNavigation().createPath(item, 0) != null)
                .findFirst()
                .orElse(null);
    }
}
