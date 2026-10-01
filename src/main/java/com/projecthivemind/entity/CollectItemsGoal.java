package com.projecthivemind.entity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.HiveInfection;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

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

    private final HiveCollector collector;
    @Nullable
    private ItemEntity target;
    private int repathCooldown;
    /** The earliest tick (by the collector's tickCount) the next scan for items may happen. */
    private int nextSearchTick;

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
    public void tick() {
        HiveHeart heart = collector.findHeart();
        if (heart == null) {
            return;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
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
        // The collector's job is the hive area: items lying anywhere in it, whether or not the creep is still there.
        List<ItemEntity> items = collector.level().getEntitiesOfClass(ItemEntity.class,
                HiveInfection.areaBox((ServerLevel) collector.level(), heart),
                item -> item.isAlive() && !item.getItem().isEmpty() && heart.getStorage().canAddItem(item.getItem()));
        return items.stream()
                .sorted(Comparator.comparingDouble(collector::distanceToSqr))
                .filter(item -> collector.getNavigation().createPath(item, 0) != null)
                .findFirst()
                .orElse(null);
    }
}
