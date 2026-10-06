package com.projecthivemind.entity;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.HiveArea;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Once the hive has consumed a sea lantern (an evolution task) its Heart fires the elder guardian's beam at one hostile mob inside the hive area at
 * a time: the beam builds up for three seconds, as the guardian's does (shown as a line of teal sparks that gets thicker), and then hits as it does,
 * with magic damage and then a mob's blow. It never targets or hurts the hive's own units. It needs a clear line from the Heart to the target, and
 * if the target dies, leaves the area or is hidden behind something while the beam builds up, the beam is let go.
 */
public final class HeartBeam {
    /** Ticks from one beam ending to the next being aimed: four seconds. */
    private static final int INTERVAL = 80;
    /** The elder guardian's build-up: 60 ticks. */
    private static final int CHARGE_TICKS = 60;
    /** What the elder guardian's beam does: 3 magic damage (1, and 2 more for an elder), then its 8 damage blow. */
    private static final float MAGIC_DAMAGE = 3.0F;
    private static final float BLOW_DAMAGE = 8.0F;
    private static final DustParticleOptions BEAM = new DustParticleOptions(new Vector3f(0.45F, 0.95F, 0.85F), 1.0F);

    /** What each Heart has charging: the mob it chose and the tick it fires on. Server side only. */
    private record Charge(UUID target, long fireAt) {
    }

    private static final Map<HiveHeart, Charge> CHARGING = new WeakHashMap<>();

    private HeartBeam() {
    }

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (!EvolveTask.SEA_LANTERN.doneIn(heart.evolveMask()) || !(heart.level() instanceof ServerLevel level)) {
            CHARGING.remove(heart);
            return;
        }
        Charge charge = CHARGING.get(heart);
        if (charge != null) {
            if (!(level.getEntity(charge.target()) instanceof LivingEntity target) || !valid(heart, target)) {
                CHARGING.remove(heart);
                return;
            }
            float progress = 1.0F - (float) (charge.fireAt() - level.getGameTime()) / CHARGE_TICKS;
            draw(level, heart, target, Mth.clamp(progress, 0.0F, 1.0F));
            if (level.getGameTime() >= charge.fireAt()) {
                CHARGING.remove(heart);
                fire(level, heart, target);
            }
            return;
        }
        if (heart.tickCount % INTERVAL != 0) {
            return;
        }
        Mob target = level.getEntitiesOfClass(Mob.class, HiveArea.areaBox(level, heart), mob -> valid(heart, mob))
                .stream().min(Comparator.comparingDouble(heart::distanceToSqr)).orElse(null);
        if (target != null) {
            CHARGING.put(heart, new Charge(target.getUUID(), level.getGameTime() + CHARGE_TICKS));
            level.playSound(null, heart.blockPosition(), SoundEvents.GUARDIAN_ATTACK, SoundSource.HOSTILE, 3.0F, 1.0F);
        }
    }

    /** A hostile mob, alive, inside the hive area, not one of the hive's own, and in a clear line from the Heart. */
    private static boolean valid(HiveHeart heart, Entity entity) {
        return entity instanceof Enemy && entity.isAlive() && !HiveAttacks.spares(entity)
                && HiveArea.containsCube(heart, entity.getX(), entity.getY(), entity.getZ()) && heart.hasLineOfSight(entity);
    }

    private static Vec3 origin(HiveHeart heart) {
        return heart.position().add(0.0D, heart.getBbHeight() / 2.0D, 0.0D);
    }

    /** The beam so far: sparks along the line from the Heart to the target, more of them (a thicker beam) the nearer it is to firing. */
    private static void draw(ServerLevel level, HiveHeart heart, LivingEntity target, float progress) {
        Vec3 from = origin(heart);
        Vec3 toTarget = target.getEyePosition().subtract(from);
        Vec3 direction = toTarget.normalize();
        int steps = Mth.floor(toTarget.length() * 2.0D);
        int perStep = 1 + (int) (progress * 3.0F);
        for (int i = 1; i < steps; i++) {
            Vec3 at = from.add(direction.scale(i * 0.5D));
            level.sendParticles(BEAM, at.x, at.y, at.z, perStep, 0.05D * progress, 0.05D * progress, 0.05D * progress, 0.0D);
        }
    }

    /** The elder guardian's hit: magic damage, then its blow, and a burst where it lands. */
    private static void fire(ServerLevel level, HiveHeart heart, LivingEntity target) {
        draw(level, heart, target, 1.0F);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY(0.5D), target.getZ(), 12, 0.3D, 0.4D, 0.3D, 0.1D);
        level.playSound(null, target.blockPosition(), SoundEvents.GUARDIAN_HURT, SoundSource.HOSTILE, 2.0F, 1.0F);
        target.hurt(level.damageSources().indirectMagic(heart, heart), MAGIC_DAMAGE);
        target.hurt(level.damageSources().mobAttack(heart), BLOW_DAMAGE);
    }
}
