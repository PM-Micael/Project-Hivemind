package com.projecthivemind.entity;

import java.util.Comparator;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.HiveArea;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Once the hive has consumed a shulker box (an evolution task) its Heart shoots shulker bullets, as shulkers do, at hostile mobs inside the
 * hive area: one every two seconds, at the nearest.
 */
public final class HeartTurret {
    /** Ticks between one bullet and the next: two seconds. */
    private static final int INTERVAL = 40;

    private HeartTurret() {
    }

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (heart.tickCount % INTERVAL != 0 || !EvolveTask.SHULKER_BOX.doneIn(heart.evolveMask()) || !(heart.level() instanceof ServerLevel level)) {
            return;
        }
        Mob target = level.getEntitiesOfClass(Mob.class, HiveArea.areaBox(level, heart),
                        mob -> mob instanceof Enemy && mob.isAlive() && !HiveAttacks.spares(mob)
                                && HiveArea.containsCube(heart, mob.getX(), mob.getY(), mob.getZ()))
                .stream().min(Comparator.comparingDouble(heart::distanceToSqr)).orElse(null);
        if (target == null) {
            return;
        }
        level.addFreshEntity(new HiveShulkerBullet(level, heart, target, Direction.Axis.Y));
        level.playSound(null, heart.blockPosition(), SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 2.0F, (level.random.nextFloat() - level.random.nextFloat()) * 0.2F + 1.0F);
    }
}
