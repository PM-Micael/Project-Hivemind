package com.projecthivemind.entity;

import com.projecthivemind.HivemindManager;
import com.projecthivemind.HivemindStage;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;

/**
 * Warns the owner when the Hive Heart is in danger, wherever their camera is: red text in the middle of the screen and a sound. A mob that has the
 * Heart as its target gives the Warden's heartbeat; the Heart being hit gives the Elder Guardian's curse. Neither repeats more often than every
 * few seconds, and while the Heart is being hit the heartbeat is left out.
 */
public final class HeartAlerts {
    /** How far from the Heart a mob that has it as its target is looked for. */
    private static final double TARGET_SCAN_RADIUS = 48.0D;
    private static final long ATTACK_COOLDOWN = 60L;
    private static final long TARGET_COOLDOWN = 80L;
    /** How long after a hit the heartbeat is held back, so the two sounds do not sound over each other. */
    private static final long HIT_QUIET = 100L;

    private static long lastAttackAlert = Long.MIN_VALUE / 2;
    private static long lastTargetAlert = Long.MIN_VALUE / 2;

    private HeartAlerts() {
    }

    /** The Heart took damage. The hive's own cost (a unit dying) and damage with no one behind it do not count. */
    public static void attacked(HiveHeart heart, DamageSource source) {
        if (source.is(DamageTypes.GENERIC_KILL) || (source.getEntity() == null && source.getDirectEntity() == null)) {
            return;
        }
        long now = heart.level().getGameTime();
        if (now - lastAttackAlert < ATTACK_COOLDOWN) {
            return;
        }
        lastAttackAlert = now;
        alert(heart, SoundEvents.ELDER_GUARDIAN_CURSE, "message.projecthivemind.heart_attacked");
    }

    /** Once a second, from the Heart: is a mob after it? */
    public static void tick(HiveHeart heart) {
        if (!(heart.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (now - lastTargetAlert < TARGET_COOLDOWN || now - lastAttackAlert < HIT_QUIET) {
            return;
        }
        boolean targeted = !level.getEntitiesOfClass(Mob.class, heart.getBoundingBox().inflate(TARGET_SCAN_RADIUS),
                mob -> mob.isAlive() && mob.getTarget() == heart).isEmpty();
        if (targeted) {
            lastTargetAlert = now;
            alert(heart, SoundEvents.WARDEN_HEARTBEAT, "message.projecthivemind.heart_targeted");
        }
    }

    private static void alert(HiveHeart heart, SoundEvent sound, String textKey) {
        if (heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null || HivemindManager.get(owner).stage() != HivemindStage.HIVE) {
            return;
        }
        owner.playNotifySound(sound, SoundSource.PLAYERS, 1.0F, 1.0F);
        owner.connection.send(new ClientboundSetTitlesAnimationPacket(2, 30, 10));
        owner.connection.send(new ClientboundSetTitleTextPacket(Component.translatable(textKey).withStyle(ChatFormatting.RED)));
    }
}
