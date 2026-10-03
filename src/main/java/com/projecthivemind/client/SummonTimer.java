package com.projecthivemind.client;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.network.SyncPortalsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * While the hive is summoning units, the place they will come through (the Heart or a portal) shows a countdown floating above it, and ticks
 * like a redstone clock every second, with a chime when a unit arrives. It is for the owner's camera only: it is what the client knows from
 * the portal network's sync (see {@link ClientPortals}).
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class SummonTimer {
    private static final int RED = 0xFF5555;
    private static final int YELLOW = 0xFFDD55;
    /** The last countdown value the sound was played for, or -1 when nothing was being summoned. */
    private static int lastSeconds = -1;

    private SummonTimer() {
    }

    /** The point just above where the units come through, or null if that is not in the dimension the camera is in. */
    @Nullable
    private static Vec3 spot(Minecraft minecraft, ClientLevel level) {
        if (ClientPortals.summonTarget() >= 0) {
            if (ClientPortals.summonTarget() >= ClientPortals.portals().size()) {
                return null;
            }
            SyncPortalsPayload.Portal portal = ClientPortals.portals().get(ClientPortals.summonTarget());
            if (!level.dimension().location().toString().equals(portal.dimension())) {
                return null;
            }
            return Vec3.atCenterOf(portal.pos()).add(0.0D, 1.6D, 0.0D);
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof HiveHeart heart && minecraft.player.getUUID().equals(heart.ownerId())) {
                return new Vec3(heart.getX(), heart.getY() + heart.getBbHeight() + 1.0D, heart.getZ());
            }
        }
        return null;
    }

    /** Once a second: a redstone-clock tick, higher for the last three seconds; and a chime when the countdown starts over (a unit came through). */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !ClientPortals.summoning()) {
            lastSeconds = -1;
            return;
        }
        int seconds = ClientPortals.secondsToNext();
        if (seconds == lastSeconds) {
            return;
        }
        boolean arrived = lastSeconds >= 0 && seconds > lastSeconds;
        lastSeconds = seconds;
        Vec3 spot = spot(minecraft, minecraft.level);
        if (spot == null) {
            return;
        }
        if (arrived) {
            minecraft.level.playLocalSound(spot.x, spot.y, spot.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 1.0F, 1.2F, false);
        } else {
            minecraft.level.playLocalSound(spot.x, spot.y, spot.z, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 0.9F, seconds <= 3 ? 0.8F : 0.55F, false);
        }
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !ClientPortals.summoning()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Vec3 spot = spot(minecraft, minecraft.level);
        if (spot == null) {
            return;
        }
        int seconds = ClientPortals.secondsToNext();
        Component text = Component.translatable("screen.projecthivemind.summon_timer", seconds, ClientPortals.queue().size());
        Font font = minecraft.font;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        double distance = camera.distanceTo(spot);

        poseStack.pushPose();
        poseStack.translate(spot.x - camera.x, spot.y - camera.y, spot.z - camera.z);
        poseStack.mulPose(event.getCamera().rotation());
        // Bigger the further the camera is, so it can be read from the RTS height.
        float scale = (float) Math.max(0.025D, 0.012D * distance);
        poseStack.scale(-scale, -scale, scale);
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, 0.0F, seconds <= 3 ? RED : YELLOW, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0x66000000,
                LightTexture.FULL_BRIGHT);
        buffers.endBatch();
        poseStack.popPose();
    }
}
