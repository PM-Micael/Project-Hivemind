package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveEquipment;
import com.projecthivemind.UnitAction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * A soldier told to attack a mob: run it down and hit it, over and over, until it dies or the order is cancelled.
 * It does not give up because the target runs away or hits back.
 */
public class SoldierAttackGoal extends Goal {
    private static final double CHASE_SPEED = 1.15D;
    private static final int REPATH_INTERVAL = 6;
    /** Never swing faster than this many ticks apart, whatever the weapon says. */
    private static final int MIN_ATTACK_INTERVAL = 5;

    private final HiveSoldier soldier;
    private int repathCooldown;
    private int attackCooldown;

    public SoldierAttackGoal(HiveSoldier soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** The mob being attacked, or null. Clears the order if the target is gone or dead: the job is done. */
    @Nullable
    private LivingEntity target() {
        UnitAction action = soldier.action();
        if (action == null || action.kind() != UnitAction.Kind.ATTACK || action.target() == null) {
            return null;
        }
        if (soldier.level() instanceof ServerLevel level
                && level.getEntity(action.target()) instanceof LivingEntity target && target.isAlive()) {
            return target;
        }
        soldier.setAction(null);
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
        soldier.setAggressive(true);
    }

    @Override
    public void stop() {
        soldier.setAggressive(false);
        // Only halt the soldier if it has nothing else to do. If it was just given a new order (say, to walk
        // somewhere), that order's path is already set and must not be cancelled here.
        if (soldier.action() == null) {
            soldier.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        LivingEntity target = target();
        if (target == null) {
            return;
        }
        soldier.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--repathCooldown <= 0) {
            soldier.getNavigation().moveTo(target, CHASE_SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
        if (--attackCooldown <= 0 && soldier.isWithinMeleeAttackRange(target) && soldier.hasLineOfSight(target)) {
            attack(target);
        }
    }

    private void attack(LivingEntity target) {
        ItemStack weapon = soldier.getMainHandItem();
        soldier.swing(InteractionHand.MAIN_HAND);
        boolean hit = soldier.doHurtTarget(target);
        if (hit && !weapon.isEmpty()) {
            // Mobs do not wear their weapons in vanilla, but a player does: a hit costs the weapon durability. The gear
            // mirror then charges the same to the original in the hive.
            weapon.getItem().postHurtEnemy(weapon, target, soldier);
        }
        // Swing as often as the weapon allows: attacks per second is its attack speed.
        attackCooldown = Math.max(MIN_ATTACK_INTERVAL, (int) Math.round(20.0D / HiveEquipment.attackSpeed(weapon)));
    }
}
