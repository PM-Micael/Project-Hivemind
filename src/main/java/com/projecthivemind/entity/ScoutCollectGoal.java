package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * A scout that is not selected walks to dropped items, if the hive's scout settings say so. It only goes to them; the
 * scout's own pickup (see {@link HiveScout}) takes the item into the hive's inventory when it gets there.
 *
 * <p>It is a lower priority than fleeing, so it never walks toward an item while it is running from something.
 */
public class ScoutCollectGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int SCAN_INTERVAL = 20;
    private static final int REPATH_INTERVAL = 10;
    /** A trip that takes longer than this (20 seconds) is given up on, so it can never get stuck on an item. */
    private static final int MAX_TRIP_TICKS = 400;
    /** How long an item is ignored after the scout gave up on it (20 seconds). */
    private static final int IGNORE_TICKS = 400;

    private final HiveScout scout;
    @Nullable
    private Entity target;
    private int nextScanTick;
    private int repathCooldown;
    private int tripTicks;
    private final Map<Integer, Integer> ignored = new HashMap<>();

    public ScoutCollectGoal(HiveScout scout) {
        this.scout = scout;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Setting on, no orders, and not selected. */
    private boolean allowed(@Nullable HiveHeart heart) {
        return heart != null && scout.behavior().collectItems() && scout.action() == null
                && !heart.isUnitSelected(scout.getId());
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = scout.findHeart();
        // Not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        if (!allowed(heart) || scout.tickCount < nextScanTick) {
            return false;
        }
        nextScanTick = scout.tickCount + SCAN_INTERVAL;
        target = findItem(heart);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return allowed(scout.findHeart()) && target != null && target.isAlive() && tripTicks <= MAX_TRIP_TICKS;
    }

    @Override
    public void start() {
        tripTicks = 0;
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        // A trip that ran out of time: leave that item alone for a while instead of going straight back to it.
        if (target != null && target.isAlive() && tripTicks > MAX_TRIP_TICKS) {
            ignored.put(target.getId(), scout.tickCount + IGNORE_TICKS);
        }
        target = null;
        // Only halt the scout if it has nothing else to do; a new order has already set its own path.
        if (scout.action() == null) {
            scout.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        tripTicks++;
        if (target != null && --repathCooldown <= 0) {
            scout.getNavigation().moveTo(target, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    /** The nearest dropped item the hive can take, or experience orb, that the scout can walk to. */
    @Nullable
    private Entity findItem(HiveHeart heart) {
        ignored.values().removeIf(until -> until <= scout.tickCount);
        double radius = scout.behavior().collectRadius();
        List<Entity> candidates = new ArrayList<>(scout.level().getEntitiesOfClass(ItemEntity.class, scout.getBoundingBox().inflate(radius),
                item -> item.isAlive() && !item.getItem().isEmpty() && !isIgnored(item)
                        && item.distanceToSqr(scout) <= radius * radius
                        && heart.getStorage().canAddItem(item.getItem())));
        // Experience is picked up like a player would: it goes to the hivemind's experience bar.
        candidates.addAll(scout.level().getEntitiesOfClass(ExperienceOrb.class, scout.getBoundingBox().inflate(radius),
                orb -> orb.isAlive() && !isIgnored(orb) && orb.distanceToSqr(scout) <= radius * radius));
        return candidates.stream()
                .sorted(Comparator.comparingDouble(scout::distanceToSqr))
                .filter(candidate -> scout.getNavigation().createPath(candidate, 0) != null)
                .findFirst()
                .orElse(null);
    }

    private boolean isIgnored(Entity entity) {
        Integer until = ignored.get(entity.getId());
        return until != null && scout.tickCount < until;
    }
}
