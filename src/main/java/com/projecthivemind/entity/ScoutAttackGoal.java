package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveFood;
import com.projecthivemind.UnitAction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * A scout told to attack a mob: run it down and hit it with what it holds, over and over, until it dies or the order is
 * cancelled. The same as a soldier's order, only with the scout's own hand, since it takes no gear from the hive.
 */
public class ScoutAttackGoal extends Goal {
    private static final double CHASE_SPEED = 1.2D;
    private static final int REPATH_INTERVAL = 10;
    private static final int MIN_ATTACK_INTERVAL = 4;

    private final HiveScout scout;
    private int repathCooldown;
    private int attackCooldown;

    public ScoutAttackGoal(HiveScout scout) {
        this.scout = scout;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** The mob being attacked, or null. Clears the order if the target is gone or dead: the job is done. */
    @Nullable
    private LivingEntity target() {
        UnitAction action = scout.action();
        if (action == null || action.kind() != UnitAction.Kind.ATTACK || action.target() == null) {
            return null;
        }
        if (scout.level() instanceof ServerLevel level
                && level.getEntity(action.target()) instanceof LivingEntity target && target.isAlive()) {
            return target;
        }
        scout.setAction(null);
        return null;
    }

    @Override
    public boolean canUse() {
        return target() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target() != null;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        attackCooldown = 0;
        scout.setAggressive(true);
    }

    @Override
    public void stop() {
        scout.setAggressive(false);
        if (scout.action() == null) {
            scout.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        LivingEntity target = target();
        if (target == null) {
            return;
        }
        scout.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (--repathCooldown <= 0) {
            scout.getNavigation().moveTo(target, CHASE_SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
        if (--attackCooldown <= 0 && scout.isWithinMeleeAttackRange(target) && scout.hasLineOfSight(target)) {
            ItemStack weapon = scout.getMainHandItem();
            scout.swing(InteractionHand.MAIN_HAND);
            boolean hit = scout.doHurtTarget(target);
            HiveHeart hive = scout.findHeart();
            if (hive != null) {
                hive.food().exhaust(HiveFood.ATTACK);
            }
            if (hit && !weapon.isEmpty()) {
                weapon.getItem().postHurtEnemy(weapon, target, scout);
            }
            attackCooldown = Math.max(MIN_ATTACK_INTERVAL, (int) Math.round(20.0D / HiveEquipment.attackSpeed(weapon)));
        }
    }
}
