package com.projecthivemind.client;

import com.projecthivemind.HivemindStage;
import com.projecthivemind.ModEntities;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    /** Client-only stand-in used to draw the larva with the vanilla silverfish model. */
    private static Silverfish larvaProxy;

    private ClientEvents() {
    }

    // ---- mod bus ----

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Units look exactly like their vanilla counterparts for now.
        event.registerEntityRenderer(ModEntities.HIVE_WORKER.get(), SkeletonRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_SOLDIER.get(), ZombieRenderer::new);
    }

    // ---- game bus ----

    /** Draw the larva as a silverfish instead of the player model. Only the local player's stage is known client-side. */
    @SubscribeEvent
    static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = event.getEntity();
        if (player != minecraft.player || !ClientState.is(HivemindStage.LARVA)) {
            return;
        }

        if (larvaProxy == null || larvaProxy.level() != player.level()) {
            larvaProxy = new Silverfish(EntityType.SILVERFISH, player.level());
        }
        Silverfish proxy = larvaProxy;
        proxy.setPos(player.getX(), player.getY(), player.getZ());
        proxy.xo = player.xo;
        proxy.yo = player.yo;
        proxy.zo = player.zo;
        proxy.setYRot(player.getYRot());
        proxy.yRotO = player.yRotO;
        proxy.setXRot(player.getXRot());
        proxy.xRotO = player.xRotO;
        proxy.yBodyRot = player.yBodyRot;
        proxy.yBodyRotO = player.yBodyRotO;
        proxy.yHeadRot = player.yHeadRot;
        proxy.yHeadRotO = player.yHeadRotO;
        proxy.tickCount = player.tickCount;

        EntityRenderer<? super Silverfish> renderer = minecraft.getEntityRenderDispatcher().getRenderer(proxy);
        float yaw = Mth.lerp(event.getPartialTick(), player.yRotO, player.getYRot());
        renderer.render(proxy, yaw, event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
        event.setCanceled(true);
    }

    /** No Steve hand for a larva or a bodyless hivemind. */
    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        if (ClientState.is(HivemindStage.LARVA) || ClientState.is(HivemindStage.HIVE)) {
            event.setCanceled(true);
        }
    }

    /** The bodyless hivemind has no vanilla inventory; show the hive menu instead. */
    @SubscribeEvent
    static void onScreenOpening(ScreenEvent.Opening event) {
        if (ClientState.is(HivemindStage.HIVE) && event.getNewScreen() instanceof InventoryScreen) {
            event.setNewScreen(new HivemindScreen());
        }
    }

    /**
     * Safety net behind {@link HiveCamera}'s click blocking: spectator mode would make a left click start
     * spectating the clicked unit, so never let vanilla attack/use/pick through in hive mode.
     */
    @SubscribeEvent
    static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (ClientState.is(HivemindStage.HIVE)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.reset();
        larvaProxy = null;
    }
}
