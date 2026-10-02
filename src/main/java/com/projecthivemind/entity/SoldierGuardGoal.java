package com.projecthivemind.entity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;

/**
 * A soldier told to guard one of its owner's units: it keeps close to that unit, and goes for whatever threatens it: anything that has the
 * unit as its target or has just hurt it, and any hostile mob that comes near it. It does not chase far from the unit it guards. The job
 * ends only when the unit it guards dies (or the player cancels it).
 */
public class SoldierGuardGoal extends Goal {
    private static final double FOLLOW_SPEED = 1.2D;
    /** The soldier comes to stay within this many blocks of what it guards (and starts to follow beyond the other). */
    private static final double STAY_DISTANCE = 3.0D;
    private static final double FOLLOW_DISTANCE = 6.0D;
    /** How close to the ward a hostile mob has to come to be fought, and how far from it the soldier will go to fight. */
    private static final double THREAT_RADIUS = 9.0D;
    private static final double LEASH = 18.0D;
    private static final int SCAN_INTERVAL = 10;
    private static final int REPATH_INTERVAL = 10;

    private final HiveSoldier soldier;
    @Nullable
    private Mob threat;
    private int nextScan;
    private int repathCooldown;

    public SoldierGuardGoal(HiveSoldier soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private UnitAction order() {
        UnitAction action = soldier.action();
        return action != null && action.kind() == UnitAction.Kind.GUARD && action.target() != null ? action : null;
    }

    /** The unit being guarded, or null if it is gone: then the job is over. */
    @Nullable
    private LivingEntity ward() {
        UnitAction order = order();
        if (order == null || !(soldier.level() instanceof ServerLevel level)) {
            return null;
        }
        if (level.getEntity(order.target()) instanceof LivingEntity ward && ward.isAlive()) {
            return ward;
        }
        // It has died: the job ends.
        soldier.setAction(null);
        return null;
    }

    @Override
    public boolean canUse() {
        return ward() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return ward() != null;
    }

    @Override
    public void start() {
        soldier.resetCombat();
        repathCooldown = 0;
        threat = null;
    }

    @Override
    public void stop() {
        soldier.setAggressive(false);
        threat = null;
        if (soldier.action() == null) {
            soldier.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        LivingEntity ward = ward();
        if (ward == null) {
            return;
        }
        if (threat != null && (!threat.isAlive() || threat.distanceToSqr(ward) > LEASH * LEASH)) {
            threat = null;
        }
        if (soldier.tickCount >= nextScan) {
            nextScan = soldier.tickCount + SCAN_INTERVAL;
            if (threat == null) {
                threat = findThreat(ward);
            }
        }
        if (threat != null) {
            soldier.setAggressive(true);
            soldier.pursue(threat);
            return;
        }
        soldier.setAggressive(false);
        // No danger: stay by the ward.
        soldier.getLookControl().setLookAt(ward, 30.0F, 30.0F);
        double distance = soldier.distanceTo(ward);
        if (distance > FOLLOW_DISTANCE || (distance > STAY_DISTANCE && !soldier.getNavigation().isDone())) {
            if (--repathCooldown <= 0) {
                soldier.getNavigation().moveTo(ward, FOLLOW_SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
        } else {
            soldier.getNavigation().stop();
        }
    }

    /** The nearest mob going for the ward or near it: one that has it as its target, one that just hurt it, or a hostile one close by. */
    @Nullable
    private Mob findThreat(LivingEntity ward) {
        List<Mob> candidates = soldier.level().getEntitiesOfClass(Mob.class, ward.getBoundingBox().inflate(THREAT_RADIUS * 1.5D),
                mob -> mob.isAlive() && !(mob instanceof HiveUnit) && !(mob instanceof HiveHeart)
                        && (mob.getTarget() == ward || ward.getLastHurtByMob() == mob
                        || (mob instanceof Enemy && mob.distanceToSqr(ward) <= THREAT_RADIUS * THREAT_RADIUS)));
        return candidates.stream().min(Comparator.comparingDouble(ward::distanceToSqr)).orElse(null);
    }
}
