package com.projecthivemind.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Outlines what the player is commanding. The target of an open context menu is outlined white; anything units have
 * been given an action on is outlined red.
 *
 * <p>Mobs get the game's glow outline, which shows through walls. A block has no silhouette, so it gets a see-through
 * wireframe box around its shape instead. All of it is drawn on this client only; nothing changes on the server.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class HiveHighlights {
    private static final String RED_TEAM = "projecthivemind_red";
    private static final int GLOW_FLAG = 6;
    private static final float[] WHITE = {1.0F, 1.0F, 1.0F, 1.0F};
    private static final float[] RED = {1.0F, 0.15F, 0.15F, 1.0F};

    /** Mobs this client is currently making glow, by entity id, and whether each is outlined red. */
    private static final Map<Integer, Boolean> GLOWING = new HashMap<>();

    private HiveHighlights() {
    }

    // ---- mobs: the game's own glow outline ----

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            GLOWING.clear();
            return;
        }

        // Red for mobs under attack; white for the mob an open menu is about (unless it is already red).
        Map<Integer, Boolean> wanted = new HashMap<>();
        for (int id : ClientActions.attackedMobs()) {
            wanted.put(id, true);
        }
        if (ContextMenu.targetMobId() >= 0) {
            wanted.putIfAbsent(ContextMenu.targetMobId(), false);
        }

        // Switch off the ones that are no longer wanted.
        Iterator<Map.Entry<Integer, Boolean>> it = GLOWING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Boolean> entry = it.next();
            if (!wanted.containsKey(entry.getKey())) {
                stopGlowing(level, entry.getKey());
                it.remove();
            }
        }
        // Keep the rest on. This runs every tick because the server's own updates to an entity's flags would otherwise
        // switch the glow off again.
        for (Map.Entry<Integer, Boolean> entry : wanted.entrySet()) {
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) {
                startGlowing(level, entity, entry.getValue());
                GLOWING.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GLOWING.clear();
    }

    private static void startGlowing(ClientLevel level, Entity entity, boolean red) {
        entity.setSharedFlag(GLOW_FLAG, true);
        // The outline colour is the colour of the entity's team. A client-only team gives the red; no team is white.
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = redTeam(scoreboard);
        boolean inRed = scoreboard.getPlayersTeam(entity.getScoreboardName()) == team;
        if (red && !inRed) {
            scoreboard.addPlayerToTeam(entity.getScoreboardName(), team);
        } else if (!red && inRed) {
            scoreboard.removePlayerFromTeam(entity.getScoreboardName(), team);
        }
    }

    private static void stopGlowing(ClientLevel level, int entityId) {
        Entity entity = level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        entity.setSharedFlag(GLOW_FLAG, false);
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = redTeam(scoreboard);
        if (scoreboard.getPlayersTeam(entity.getScoreboardName()) == team) {
            scoreboard.removePlayerFromTeam(entity.getScoreboardName(), team);
        }
    }

    private static PlayerTeam redTeam(Scoreboard scoreboard) {
        PlayerTeam team = scoreboard.getPlayerTeam(RED_TEAM);
        if (team == null) {
            team = scoreboard.addPlayerTeam(RED_TEAM);
            team.setColor(ChatFormatting.RED);
        }
        return team;
    }

    // ---- blocks: a see-through wireframe ----

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || event.getPoseStack() == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Set<BlockPos> red = ClientActions.blocks();
        BlockPos menuBlock = ContextMenu.targetBlock();
        if (minecraft.level == null || (red.isEmpty() && menuBlock == null)) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        // Drawn without the depth test so the outline shows through walls, like the glow on units does.
        RenderSystem.disableDepthTest();
        for (BlockPos pos : red) {
            drawBox(minecraft, poseStack, lines, camera, pos, RED);
        }
        if (menuBlock != null && !red.contains(menuBlock)) {
            drawBox(minecraft, poseStack, lines, camera, menuBlock, WHITE);
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();
    }

    /** A box around the block's actual shape, so a flower or a slab is outlined, not a whole cube. */
    private static void drawBox(Minecraft minecraft, PoseStack poseStack, VertexConsumer lines, Vec3 camera, BlockPos pos, float[] colour) {
        BlockState state = minecraft.level.getBlockState(pos);
        VoxelShape shape = state.getShape(minecraft.level, pos);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        AABB box = shape.bounds().move(pos).inflate(0.002D).move(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(poseStack, lines, box, colour[0], colour[1], colour[2], colour[3]);
    }
}
