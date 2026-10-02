package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import com.projecthivemind.HiveArea;

/**
 * An idle unit, not selected, walks about at random, if its settings say so. It only ever picks spots inside the hive area,
 * so a wandering unit never leaves the border. It has the lowest priority: anything else a unit has to do comes first.
 */
public class WanderInsideGoal extends Goal {
    private static final double SPEED = 0.8D;
    /** How far from itself a unit looks for its next spot, and how many random spots it tries before giving up for now. */
    private static final int RANGE = 8;
    private static final int TRIES = 10;
    /** The pause between walks, in ticks, at least and at most (3 to 8 seconds). */
    private static final int MIN_PAUSE = 60;
    private static final int MAX_PAUSE = 160;

    private final PathfinderMob mob;
    private final HiveUnit unit;
    private final BooleanSupplier enabled;
    private int nextWalk;
    private Vec3 target;

    public WanderInsideGoal(PathfinderMob mob, BooleanSupplier enabled) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.enabled = enabled;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /** On, with nothing to do, and not selected: a selected unit follows orders only. */
    private boolean allowed(HiveHeart heart) {
        return heart != null && enabled.getAsBoolean() && unit.action() == null && !heart.isUnitSelected(mob.getId());
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = unit.findHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can never line up.
        if (!allowed(heart) || mob.tickCount < nextWalk) {
            return false;
        }
        nextWalk = mob.tickCount + MIN_PAUSE + mob.getRandom().nextInt(MAX_PAUSE - MIN_PAUSE);
        for (int i = 0; i < TRIES; i++) {
            Vec3 spot = LandRandomPos.getPos(mob, RANGE, 4);
            if (spot != null && HiveArea.containsXZ(heart, spot.x, spot.z)) {
                target = spot;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return allowed(unit.findHeart()) && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(target.x, target.y, target.z, SPEED);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
