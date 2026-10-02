package com.projecthivemind.entity;

import com.projecthivemind.WorkerBehavior;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.monster.Enemy;

/**
 * A worker set to avoid hostile mobs runs away from them. It uses the game's own "avoid" behaviour: it picks the nearest hostile mob it can
 * see and heads for a spot away from it. This is the highest priority a worker has, above any job, so it drops what it is doing and runs;
 * the job is picked up again when the danger has gone. A worker the player has selected obeys the player instead.
 */
public class WorkerFleeGoal extends AvoidEntityGoal<Mob> {
    /** The furthest the game's avoid behaviour is asked to look; how close a mob has to be before the worker runs is the setting. */
    private static final float FLEE_DISTANCE = WorkerBehavior.MAX_RADIUS;

    private final HiveWorker worker;

    public WorkerFleeGoal(HiveWorker worker) {
        // A little faster than walking, so it can get away from what is after it.
        super(worker, Mob.class, FLEE_DISTANCE, 1.2D, 1.3D,
                mob -> mob instanceof Enemy && !(mob instanceof HiveUnit) && !(mob instanceof HiveHeart));
        this.worker = worker;
    }

    private boolean allowed() {
        HiveHeart heart = worker.findHeart();
        return heart != null && worker.behavior().fleeHostiles() && !heart.isUnitSelected(worker.getId());
    }

    @Override
    public boolean canUse() {
        if (!allowed() || !super.canUse()) {
            return false;
        }
        // super picked the closest hostile mob in the longest range; it only counts inside the setting.
        double radius = worker.behavior().fleeRadius();
        return toAvoid != null && worker.distanceToSqr(toAvoid) <= radius * radius;
    }

    @Override
    public boolean canContinueToUse() {
        return allowed() && super.canContinueToUse();
    }
}
