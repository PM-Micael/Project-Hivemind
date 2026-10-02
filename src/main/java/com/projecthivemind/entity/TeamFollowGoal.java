package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The first team rule: a worker or a soldier in a team stays close to the team's scout and follows it. It only walks to the scout
 * when it has nothing else to do, so any order or job it is given comes first, and when that is done it goes straight back. It
 * walks whether or not the player has it selected: selecting a team member does not let the player move it.
 */
public class TeamFollowGoal extends Goal {
    private static final double SPEED = 1.2D;
    /** The unit starts to follow when the scout is further than this, and stops when it is closer than the other. */
    private static final double START_DISTANCE_SQR = 6.0D * 6.0D;
    private static final double STOP_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL = 10;

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

    @Nullable
    private Mob findLeader() {
        HiveHeart heart = unit.findHeart();
        return heart == null || unit.action() != null ? null : heart.teamLeader(mob);
    }

    @Override
    public boolean canUse() {
        leader = findLeader();
        return leader != null && mob.distanceToSqr(leader) > START_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        leader = findLeader();
        return leader != null && mob.distanceToSqr(leader) > STOP_DISTANCE_SQR;
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
