package com.projecthivemind.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/**
 * A hostile mob going after the Hive Heart. How far away it notices the Heart grows with the Heart's level: at level 2 it is the
 * mob's own follow range, which is exactly what it is for a player standing still, and it is less before that and more after.
 */
public class HeartTargetGoal extends NearestAttackableTargetGoal<HiveHeart> {
    /** The follow range factor for each level, from level 1. Level 2 is 1: the same as a player standing still. */
    private static final double[] RANGE_FACTORS = {0.5D, 1.0D, 1.25D, 1.5D, 2.0D};

    public HeartTargetGoal(Mob mob) {
        super(mob, HiveHeart.class, 10, true, false, other -> other instanceof HiveHeart heart
                && !EnderPeace.spares(mob, heart)
                && mob.distanceTo(heart) <= mob.getAttributeValue(Attributes.FOLLOW_RANGE) * factor(heart.visualLevel()));
    }

    public static double factor(int level) {
        return RANGE_FACTORS[Math.max(0, Math.min(level, RANGE_FACTORS.length) - 1)];
    }

    /** The search looks as far as the biggest Heart could be noticed from; keeping a target uses the range of the Heart it has. */
    @Override
    protected double getFollowDistance() {
        double base = this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        return this.mob.getTarget() instanceof HiveHeart heart ? base * factor(heart.visualLevel()) : base * RANGE_FACTORS[RANGE_FACTORS.length - 1];
    }
}
