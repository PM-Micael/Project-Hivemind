package com.projecthivemind.entity;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.HivemindManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;

/**
 * The carved pumpkin evolution: endermen do not take a hive's units or its Heart as targets on their own, as they do not take a player wearing
 * one. An enderman the hive has hit first does fight back.
 *
 * <p>This is asked where a mob picks its targets (see {@link UnitRelations} and {@link HeartTargetGoal}), so an enderman never chooses such a target,
 * instead of choosing it and having the choice undone afterwards. The target-change event asks as well, as a backstop for anything else that sets one.
 */
public final class EnderPeace {
    /** How long after the hive hits an enderman it may fight back (a mob's revenge time in the game is about this long). */
    private static final int PROVOKED_TICKS = 200;

    private EnderPeace() {
    }

    /** True if this mob is an enderman that must leave this unit or Heart alone. */
    public static boolean spares(Mob mob, LivingEntity target) {
        if (!(mob instanceof EnderMan) || !(target.level() instanceof ServerLevel level)) {
            return false;
        }
        java.util.UUID owner = target instanceof HiveUnit unit ? unit.ownerId() : target instanceof HiveHeart heart ? heart.ownerId() : null;
        ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        HiveHeart heart = player == null ? null : HivemindManager.findHeart(player);
        if (heart == null || !EvolveTask.CARVED_PUMPKIN.doneIn(heart.evolveMask())) {
            return false;
        }
        // An enderman the hive has hit is another matter: it may take its attacker as a target, as any mob would.
        boolean provoked = mob.getLastHurtByMob() == target && mob.tickCount - mob.getLastHurtByMobTimestamp() < PROVOKED_TICKS;
        return !provoked;
    }
}
