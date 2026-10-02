package com.projecthivemind.client;

import com.projecthivemind.HivemindStage;
import com.projecthivemind.ModEntities;
import com.projecthivemind.ModMenus;
import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.network.OpenHiveMenuPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.HuskRenderer;
import net.minecraft.client.renderer.entity.SilverfishRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.network.chat.Component;
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
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

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
        event.registerEntityRenderer(ModEntities.HIVE_HEART.get(), HiveHeartRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_SCOUT.get(), HuskRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_WORKER.get(), SkeletonRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_SOLDIER.get(), ZombieRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_COLLECTOR.get(), SilverfishRenderer::new);
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.HIVE.get(), HiveScreen::new);
        event.register(ModMenus.SCOUT_CONTAINER.get(), ScoutContainerScreen::new);
        event.register(ModMenus.SCOUT_TRADE.get(), ScoutTradeScreen::new);
    }

    @SubscribeEvent
    static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ProjectHivemind.id("context_menu"), ContextMenu::render);
        event.registerAboveAll(ProjectHivemind.id("command_bar"), CommandBar::render);
        event.registerAboveAll(ProjectHivemind.id("hive_health"), HiveHud::render);
        event.registerAboveAll(ProjectHivemind.id("unit_hover"), UnitHover::render);
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
        if (ClientState.is(HivemindStage.LARVA) || ClientState.hiveMode()) {
            event.setCanceled(true);
        }
    }

    /**
     * The bodyless hivemind has no vanilla inventory. Pressing the inventory key asks the server to open the
     * hive menu instead; the server answers with the real menu, backed by the Hive Heart's storage.
     */
    @SubscribeEvent
    static void onScreenOpening(ScreenEvent.Opening event) {
        if (ClientState.hiveMode() && event.getNewScreen() instanceof InventoryScreen) {
            event.setCanceled(true);
            PacketDistributor.sendToServer(new OpenHiveMenuPayload());
        }
    }

    /**
     * A creative hivemind using the normal inventory gets a button on the creative inventory to go back to the hive.
     * (The matching button on the hive menu is in {@link HiveScreen}.)
     */
    @SubscribeEvent
    static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!ClientState.normalInventoryMode() || !ClientState.canSwapInventory()
                || !(event.getScreen() instanceof CreativeModeInventoryScreen screen)) {
            return;
        }
        event.addListener(Button.builder(Component.translatable("screen.projecthivemind.swap.to_hive"), button -> {
            PacketDistributor.sendToServer(new ToggleInventoryModePayload());
            screen.onClose();
        }).bounds(4, 4, 90, 20).build());
    }

    /**
     * Safety net behind {@link HiveCamera}'s click blocking: spectator mode would make a left click start
     * spectating the clicked unit, so never let vanilla attack/use/pick through in hive mode.
     */
    @SubscribeEvent
    static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (ClientState.hiveMode()) {
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
