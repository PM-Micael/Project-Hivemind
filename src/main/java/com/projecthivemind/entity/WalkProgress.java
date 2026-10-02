package com.projecthivemind.entity;

import com.projecthivemind.UnitAction;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Carries a walk order a long way. A mob's path finder only looks so far (its follow range), so for a spot further off it finds a
 * path to the nearest place it can reach and stops there. The unit asks this each time its path runs out: if it is not there yet, it
 * finds a new path from where it stands, and so on until it arrives. A walk that stops making progress is given up.
 */
public final class WalkProgress {
    /** Close enough to count as arrived, in blocks. */
    private static final double ARRIVED = 2.0D;
    /** How many new paths in a row may bring the unit no nearer before the walk is given up. */
    private static final int MAX_STUCK = 4;
    private static final double SPEED = 1.2D;

    private double lastDistance = Double.MAX_VALUE;
    private int stuck;

    /**
     * Called when the unit's path has run out during a walk order. True if the walk goes on (a new path was started); false if it is
     * over, because the unit is there, has no way on, or is not getting anywhere.
     */
    public boolean keepWalking(Mob mob, UnitAction action) {
        if (action.pos() == null) {
            return false;
        }
        Vec3 target = Vec3.atBottomCenterOf(action.pos().above());
        double distance = mob.position().distanceTo(target);
        if (distance <= ARRIVED) {
            return finish();
        }
        stuck = distance > lastDistance - 1.0D ? stuck + 1 : 0;
        lastDistance = distance;
        if (stuck >= MAX_STUCK || !mob.getNavigation().moveTo(target.x, target.y, target.z, SPEED)) {
            return finish();
        }
        return true;
    }

    private boolean finish() {
        lastDistance = Double.MAX_VALUE;
        stuck = 0;
        return false;
    }
}
