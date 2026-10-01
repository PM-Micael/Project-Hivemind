package com.projecthivemind.network;

import com.projecthivemind.BlockAction;
import com.projecthivemind.HivemindStage;
import com.projecthivemind.client.ChooseModeScreen;
import com.projecthivemind.client.ClientActions;
import com.projecthivemind.client.ClientSight;
import com.projecthivemind.client.ClientState;

import net.minecraft.client.Minecraft;
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

    public static void onSyncSight(SyncSightPayload payload, IPayloadContext context) {
        ClientSight.update(payload.visibleMobs());
    }

    public static void onSyncActions(SyncActionsPayload payload, IPayloadContext context) {
        ClientActions.update(payload.positions(), payload.attacked());
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
