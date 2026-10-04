package com.projecthivemind.entity;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.HiveArea;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/**
 * Once the hive has consumed an echo shard (an evolution task) its Heart uses the warden's ranged attack, the sonic boom, on hostile mobs inside the
 * hive area: it charges for about a second and a half, then fires at what it chose, as a warden does. The boom is a single beam at its target (it
 * hits nothing else), it ignores armor, and it never targets or hurts the hive's own units.
 */
public final class HeartSonicBoom {
    /** Ticks between one boom and the next: three seconds. */
    private static final int INTERVAL = 60;
    /** The warden's charge: 34 ticks from the first sound to the boom. */
    private static final int CHARGE_TICKS = 34;
    private static final float DAMAGE = 10.0F;

    /** What each Heart has charging: the mob it chose and the tick it fires on. Server side only. */
    private record Charge(UUID target, long fireAt) {
    }

    private static final Map<HiveHeart, Charge> CHARGING = new WeakHashMap<>();

    private HeartSonicBoom() {
    }

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (!EvolveTask.ECHO_SHARD.doneIn(heart.evolveMask()) || !(heart.level() instanceof ServerLevel level)) {
            CHARGING.remove(heart);
            return;
        }
        Charge charge = CHARGING.get(heart);
        if (charge != null) {
            if (level.getGameTime() >= charge.fireAt()) {
                CHARGING.remove(heart);
                if (level.getEntity(charge.target()) instanceof LivingEntity target && valid(heart, target)) {
                    fire(level, heart, target);
                }
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
            level.playSound(null, heart.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 3.0F, 1.0F);
        }
    }

    /** A hostile mob, alive, inside the hive area, and not one of the hive's own. */
    private static boolean valid(HiveHeart heart, Entity entity) {
        return entity instanceof Enemy && entity.isAlive() && !HiveAttacks.spares(entity)
                && HiveArea.containsCube(heart, entity.getX(), entity.getY(), entity.getZ());
    }

    /** The warden's sonic boom: a line of shock waves from the Heart to the target, which takes 10 damage through its armor and is thrown back. */
    private static void fire(ServerLevel level, HiveHeart heart, LivingEntity target) {
        Vec3 from = heart.position().add(0.0D, heart.getBbHeight() / 2.0D, 0.0D);
        Vec3 toTarget = target.getEyePosition().subtract(from);
        Vec3 direction = toTarget.normalize();
        for (int i = 1; i < Mth.floor(toTarget.length()) + 7; i++) {
            Vec3 at = from.add(direction.scale(i));
            level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        level.playSound(null, heart.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 1.0F);
        if (target.hurt(level.damageSources().sonicBoom(heart), DAMAGE)) {
            double vertical = 0.5D * (1.0D - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
            double horizontal = 2.5D * (1.0D - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
            target.push(direction.x() * horizontal, direction.y() * vertical, direction.z() * horizontal);
        }
    }
}
