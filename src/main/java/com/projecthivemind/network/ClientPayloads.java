package com.projecthivemind.network;

import com.projecthivemind.BlockAction;
import com.projecthivemind.HivemindStage;
import com.projecthivemind.client.ChooseModeScreen;
import com.projecthivemind.client.ClientActions;
import com.projecthivemind.client.ClientSight;
import com.projecthivemind.client.ClientUnits;
import com.projecthivemind.client.ClientState;
import com.projecthivemind.client.ScoutSignScreen;
import com.projecthivemind.menu.ScoutTradeMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Handlers that run on the client. Kept in their own class so dedicated servers never load client code. */
public final class ClientPayloads {
    private ClientPayloads() {
    }

    public static void onSync(SyncHivemindPayload payload, IPayloadContext context) {
        boolean wasHiveMode = ClientState.hiveMode();
        ClientState.update(payload.stage(), payload.normalInventory(), payload.canSwapInventory());

        Minecraft minecraft = Minecraft.getInstance();
        if (payload.stage() == HivemindStage.UNCHOSEN && !(minecraft.screen instanceof ChooseModeScreen)) {
            minecraft.setScreen(new ChooseModeScreen());
        }
        // Leaving the RTS view for normal play: the RTS camera had released the mouse, so take it back.
        if (wasHiveMode && !ClientState.hiveMode() && minecraft.screen == null) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    public static void onControlHotbar(ControlHotbarPayload payload, IPayloadContext context) {
        com.projecthivemind.client.ClientControl.setHotbar(payload.stacks(), payload.counts(), payload.selected());
    }

    public static void onSyncSight(SyncSightPayload payload, IPayloadContext context) {
        ClientSight.update(payload.visibleMobs());
    }

    public static void onOpenBook(OpenBookPayload payload, IPayloadContext context) {
        BookViewScreen.BookAccess access = BookViewScreen.BookAccess.fromItem(payload.book());
        if (access != null) {
            Minecraft.getInstance().setScreen(new BookViewScreen(access));
        }
    }

    public static void onOpenSign(OpenSignPayload payload, IPayloadContext context) {
        Minecraft.getInstance().setScreen(new ScoutSignScreen(payload.pos()));
    }

    public static void onTradeOffers(TradeOffersPayload payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof ScoutTradeMenu menu
                && menu.containerId == payload.containerId()) {
            menu.setOffers(payload.offers());
        }
    }

    public static void onSyncHeartHealth(SyncHeartHealthPayload payload, IPayloadContext context) {
        ClientState.updateHeartHealth(payload.health(), payload.maxHealth(), payload.armor(), payload.food());
        ClientState.updateBorder(payload.center(), payload.areaRadius());
    }

    public static void onSyncTeam(com.projecthivemind.network.SyncTeamPayload payload, IPayloadContext context) {
        com.projecthivemind.client.ClientTeams.update(payload.radii(), payload.attackRadii());
    }
    public static void onSyncConstructions(SyncConstructionsPayload payload, IPayloadContext context) {
        com.projecthivemind.client.ClientConstructions.update(payload.constructions());
    }

    public static void onSyncMusic(SyncMusicPayload payload, IPayloadContext context) {
        com.projecthivemind.client.ClientMusic.set(payload.disc());
    }

    public static void onSyncEnchants(com.projecthivemind.network.SyncEnchantsPayload payload, IPayloadContext context) {
        com.projecthivemind.client.ClientEnchants.update(payload.unlocked());
    }

    public static void onSyncPortals(SyncPortalsPayload payload, IPayloadContext context) {
        com.projecthivemind.client.ClientPortals.update(payload);
    }

    /** The hive is at its portal limit: let the player choose whether the new portal replaces the oldest. */
    public static void onConfirmPortal(ConfirmPortalPayload payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        PlacePortalPayload order = payload.order();
        minecraft.setScreen(new ConfirmScreen(
                replace -> {
                    minecraft.setScreen(null);
                    if (replace) {
                        PacketDistributor.sendToServer(new PlacePortalPayload(order.unitIds(), order.pos(), order.face(), true));
                    }
                },
                Component.translatable("screen.projecthivemind.portal.replace_title"),
                Component.translatable("screen.projecthivemind.portal.replace_message"),
                Component.translatable("screen.projecthivemind.portal.replace_yes"),
                Component.translatable("screen.projecthivemind.portal.replace_no")));
    }


    public static void onSyncUnits(SyncUnitsPayload payload, IPayloadContext context) {
        ClientUnits.update(payload.units());
    }

    public static void onSyncEyes(SyncEyesPayload payload, IPayloadContext context) {
        ClientSight.updateEyes(payload.eyes());
    }

    public static void onSyncActions(SyncActionsPayload payload, IPayloadContext context) {
        ClientActions.update(payload.positions(), payload.attacked());
    }

    /** The hive's tools cannot harvest what the staircase digs through: let the player choose whether to dig it anyway. */
    public static void onWeakStairs(WeakStairsPayload payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        DigStaircasePayload order = payload.order();
        minecraft.setScreen(new ConfirmScreen(
                dig -> {
                    minecraft.setScreen(null);
                    if (dig) {
                        PacketDistributor.sendToServer(new DigStaircasePayload(order.unitIds(), order.pos(), order.direction(), order.stopY(), order.torches(), true));
                    }
                },
                Component.translatable("screen.projecthivemind.weak_tool.title"),
                Component.translatable("screen.projecthivemind.weak_tool.message"),
                Component.translatable("screen.projecthivemind.weak_tool.continue"),
                Component.translatable("screen.projecthivemind.weak_tool.cancel")));
    }

    /** The hive's tools cannot harvest the block: let the player choose whether to dig it anyway. */
    public static void onWeakTool(WeakToolPayload payload, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(
                dig -> {
                    minecraft.setScreen(null);
                    if (dig) {
                        PacketDistributor.sendToServer(new BlockActionPayload(payload.unitIds(), payload.pos(), BlockAction.DIG, true));
                    }
                },
                Component.translatable("screen.projecthivemind.weak_tool.title"),
                Component.translatable("screen.projecthivemind.weak_tool.message"),
                Component.translatable("screen.projecthivemind.weak_tool.continue"),
                Component.translatable("screen.projecthivemind.weak_tool.cancel")));
    }
}
