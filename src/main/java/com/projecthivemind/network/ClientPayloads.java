package com.projecthivemind.network;

import com.projecthivemind.HivemindStage;
import com.projecthivemind.client.ChooseModeScreen;
import com.projecthivemind.client.ClientState;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Handlers that run on the client. Kept in their own class so dedicated servers never load client code. */
public final class ClientPayloads {
    private ClientPayloads() {
    }

    public static void onSync(SyncHivemindPayload payload, IPayloadContext context) {
        ClientState.update(payload.stage(), payload.hasWorker(), payload.hasSoldier());

        Minecraft minecraft = Minecraft.getInstance();
        if (payload.stage() == HivemindStage.UNCHOSEN && !(minecraft.screen instanceof ChooseModeScreen)) {
            minecraft.setScreen(new ChooseModeScreen());
        }
    }
}
