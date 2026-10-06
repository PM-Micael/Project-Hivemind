package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import com.projecthivemind.HiveArea;

/**
 * An idle unit inside the hive border that is not set to wander walks to the Hive Heart and stands in it (units ignore the Heart's collision).
 * It is the last thing a unit does. A unit in a team whose scout is outside the border is left to the team's rules instead.
 *
 * <p>When many units want the same middle they push each other about. A unit that is not getting any closer settles for the next best:
 * the distance it counts as "there" grows a little each time, until it is standing where it is. A unit that has settled stays put, unless
 * it is pushed well away. (The count starts again whenever the unit has something else to do.) Feeders hover a few blocks above the
 * Heart's ground instead, which takes less room.
 */
public class GatherAtHeartGoal extends Goal {
    private static final double SPEED = 1.0D;
    /** Close enough to the Heart's middle to count as standing in it, in blocks, for a unit that has not had to settle. */
    private static final double ARRIVED = 0.6D;
    /** How much further out a unit settles each time it is not getting closer, and how much further than that it must be pushed to walk back. */
    private static final double SETTLE_STEP = 0.75D;
    private static final double RESTART_MARGIN = 0.6D;
    /** How often progress is looked at, in ticks, and how much closer it has to have got in that time. */
    private static final int CHECK_INTERVAL = 20;
    private static final double MIN_PROGRESS = 0.25D;
    /** The pause before trying again when there is no way to the Heart, in ticks. */
    private static final int RETRY = 40;
    /** How high above the Heart's ground a feeder hovers. */
    private static final double FEEDER_HEIGHT = 3.0D;

    private final PathfinderMob mob;
    private final HiveUnit unit;
    private final BooleanSupplier enabled;
    private int nextTry;
    /** How near the middle counts as there, for this unit now: it grows as the unit has to settle. */
    private double settledRadius = ARRIVED;
    private double lastDistance;
    private int checkTimer;

    public GatherAtHeartGoal(PathfinderMob mob, BooleanSupplier enabled) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.enabled = enabled;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean idleInside(HiveHeart heart) {
        return heart != null && enabled.getAsBoolean() && unit.action() == null && mob.getTarget() == null
                && !(mob instanceof HiveScout scout && scout.isControlled())
                && !heart.isUnitSelected(mob.getId())
                && heart.teamLeader(mob) == null
                && HiveArea.containsXZ(heart, mob.getX(), mob.getZ());
    }

    private double height() {
        return mob instanceof HiveFeeder ? FEEDER_HEIGHT : 0.0D;
    }

    /** How far the unit is from its place in the Heart: sideways, and for a feeder up or down from its hovering height too. */
    private double distanceToHeart(HiveHeart heart) {
        double dx = heart.getX() - mob.getX();
        double dz = heart.getZ() - mob.getZ();
        double dy = mob instanceof HiveFeeder ? heart.getY() + height() - mob.getY() : 0.0D;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = unit.findLocalHeart();
        if (!idleInside(heart)) {
            // Something else to do: the next time is a fresh start.
            settledRadius = ARRIVED;
            return false;
        }
        // A unit that has settled stays where it is, unless it has been pushed well away.
        return mob.tickCount >= nextTry && distanceToHeart(heart) > settledRadius + (settledRadius > ARRIVED ? RESTART_MARGIN : 0.0D);
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = unit.findLocalHeart();
        return idleInside(heart) && distanceToHeart(heart) > settledRadius && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        HiveHeart heart = unit.findLocalHeart();
        if (heart == null) {
            return;
        }
        lastDistance = distanceToHeart(heart);
        checkTimer = CHECK_INTERVAL;
        if (!mob.getNavigation().moveTo(heart.getX(), heart.getY() + height(), heart.getZ(), SPEED)) {
            nextTry = mob.tickCount + RETRY;
        }
    }

    @Override
    public void tick() {
        if (--checkTimer > 0) {
            return;
        }
        checkTimer = CHECK_INTERVAL;
        HiveHeart heart = unit.findLocalHeart();
        if (heart == null) {
            return;
        }
        double distance = distanceToHeart(heart);
        // Walking, but not getting any closer (pushed about by the others): settle for somewhere a little further out.
        if (lastDistance - distance < MIN_PROGRESS) {
            settledRadius += SETTLE_STEP;
        }
        lastDistance = distance;
    }

    @Override
    public void stop() {
        HiveHeart heart = unit.findLocalHeart();
        // The path ran out before the middle (the others are in the way): that is as near as it gets, so the unit settles a little further out.
        if (heart != null && mob.getNavigation().isDone() && distanceToHeart(heart) > settledRadius) {
            settledRadius += SETTLE_STEP;
        }
        mob.getNavigation().stop();
        nextTry = mob.tickCount + RETRY / 2;
    }
}
