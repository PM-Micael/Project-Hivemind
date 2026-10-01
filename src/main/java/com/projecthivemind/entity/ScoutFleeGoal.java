package com.projecthivemind.entity;

import com.projecthivemind.ScoutBehavior;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.monster.Enemy;

/**
 * A scout that is not selected runs away from hostile mobs, if the hive's scout settings say so. It uses the game's
 * own "avoid" behaviour: it picks the nearest hostile mob it can see, and heads for a spot away from it.
 *
 * <p>It has priority over collecting items, so a scout drops everything and runs when something dangerous comes close.
 */
public class ScoutFleeGoal extends AvoidEntityGoal<Mob> {
    /** How close a hostile mob has to be, in blocks, before the scout runs. */
    private static final float FLEE_DISTANCE = 16.0F;

    private final HiveScout scout;

    public ScoutFleeGoal(HiveScout scout) {
        // Neither a walking nor a sprinting boost: the scout's own speed is already the fastest in the hive.
        super(scout, Mob.class, FLEE_DISTANCE, 1.0D, 1.0D,
                mob -> mob instanceof Enemy && !(mob instanceof HiveUnit) && !(mob instanceof HiveHeart));
        this.scout = scout;
    }

    /** Only when the setting is on, and only a scout that is idle and not selected: a selected one obeys the player. */
    private boolean allowed() {
        HiveHeart heart = scout.findHeart();
        ScoutBehavior behavior = heart == null ? ScoutBehavior.DEFAULT : heart.scoutBehavior();
        return heart != null && behavior.fleeHostiles() && scout.action() == null && !heart.isUnitSelected(scout.getId());
    }

    @Override
    public boolean canUse() {
        return allowed() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return allowed() && super.canContinueToUse();
    }
}
