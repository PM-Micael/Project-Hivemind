package com.projecthivemind.entity;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.HiveArea;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * The hive's auras. Hostile mobs inside the hive area are poisoned once the hive has consumed a poisonous potato, and withered once it has
 * consumed a wither skeleton skull (both, if both are done). The effect is renewed every second for as long as the mob stays in the border, and
 * runs out a few seconds after it leaves. The hive's own units are never affected, and what the effects cannot affect in the game (the undead
 * for poison, wither skeletons for wither) stays unaffected.
 */
public final class HeartAura {
    /** How long the effect lasts when it is renewed: five seconds, renewed every second. */
    private static final int DURATION = 100;
    private static final int INTERVAL = 20;

    private HeartAura() {
    }

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (heart.tickCount % INTERVAL != 0 || !(heart.level() instanceof ServerLevel level)) {
            return;
        }
        boolean poison = EvolveTask.POISONOUS_POTATO.doneIn(heart.evolveMask());
        boolean wither = EvolveTask.WITHER_SKULL.doneIn(heart.evolveMask());
        if (!poison && !wither) {
            return;
        }
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, HiveArea.areaBox(level, heart),
                entity -> entity instanceof Enemy && entity.isAlive() && !HiveAttacks.spares(entity)
                        && HiveArea.containsCube(heart, entity.getX(), entity.getY(), entity.getZ()))) {
            if (poison) {
                mob.addEffect(new MobEffectInstance(MobEffects.POISON, DURATION, 0), heart);
            }
            if (wither) {
                mob.addEffect(new MobEffectInstance(MobEffects.WITHER, DURATION, 0), heart);
            }
        }
    }
}
