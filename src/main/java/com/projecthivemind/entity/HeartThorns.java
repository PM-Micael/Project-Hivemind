package com.projecthivemind.entity;

import com.projecthivemind.EvolveTask;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Once the hive has consumed a cactus (an evolution task) anything that touches its Heart is hurt the way a cactus hurts it: 1 health
 * point each time it can be hurt (so about twice a second while it stays in contact). The hive's own units, the Heart, and players are spared.
 */
public final class HeartThorns {
    private HeartThorns() {
    }

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (!EvolveTask.CACTUS.doneIn(heart.evolveMask()) || !(heart.level() instanceof ServerLevel level)) {
            return;
        }
        for (LivingEntity touching : level.getEntitiesOfClass(LivingEntity.class, heart.getBoundingBox().inflate(0.05D),
                entity -> entity.isAlive() && !(entity instanceof Player) && !HiveAttacks.spares(entity))) {
            touching.hurt(level.damageSources().cactus(), 1.0F);
        }
    }
}
