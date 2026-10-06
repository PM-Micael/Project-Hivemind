package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The team rule for workers and soldiers: stay inside the team's area, the circle around its scout that the ring of flames shows,
 * and follow the scout when outside it.
 *
 * <p>The ring is the whole rule. A unit anywhere inside it is free to do what it likes. The moment it is outside (further from the
 * scout than the ring's radius sideways, or more than that above or below it) it drops everything and heads back in: this goal comes
 * before any order, job or fight, and the order is taken up again once the unit is back inside. It does this whether or not the
 * player has the unit selected.
 */
public class TeamFollowGoal extends Goal {
    private static final double SPEED = 1.2D;
    private static final int REPATH_INTERVAL = 10;
    /** A unit that has come back keeps going until it is this far inside the ring, so that it does not stand on the edge and drift out again. */
    private static final double MARGIN = 1.5D;

    private final PathfinderMob mob;
    private final HiveUnit unit;
    @Nullable
    private Mob leader;
    private int repathCooldown;

    public TeamFollowGoal(PathfinderMob mob) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    /** How far, sideways, the unit is from the scout; or the height difference if that is more. The ring's radius is what this is held to. */
    private static double separation(Mob mob, Mob leader) {
        double dx = mob.getX() - leader.getX();
        double dz = mob.getZ() - leader.getZ();
        return Math.max(Math.sqrt(dx * dx + dz * dz), Math.abs(mob.getY() - leader.getY()));
    }

    /** A soldier that is fighting may be beyond the team area, as far as the attack area (the yellow ring) reaches: it is not called back until the fight is over. */
    private boolean fightingInAttackArea(HiveHeart heart, Mob leader) {
        return mob instanceof HiveSoldier soldier && soldier.isAggressive()
                && separation(mob, leader) <= heart.teams().attackRadius(heart.teams().teamOf(mob.getUUID()));
    }

    private double ringRadius(HiveHeart heart) {
        return heart.teams().radius(heart.teams().teamOf(mob.getUUID()));
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = unit.findHeart();
        leader = heart == null ? null : heart.teamLeader(mob);
        return heart != null && leader != null && separation(mob, leader) > ringRadius(heart) && !fightingInAttackArea(heart, leader);
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = unit.findHeart();
        leader = heart == null ? null : heart.teamLeader(mob);
        return heart != null && leader != null && separation(mob, leader) > Math.max(1.0D, ringRadius(heart) - MARGIN) && !fightingInAttackArea(heart, leader);
    }

    @Override
    public void start() {
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        leader = null;
    }

    @Override
    public void tick() {
        if (leader != null && --repathCooldown <= 0) {
            mob.getNavigation().moveTo(leader, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }
}
