package com.projecthivemind.network;

import com.projecthivemind.HivemindManager;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Handlers that run on the server. Every request is re-validated by {@link HivemindManager}; never trust the client. */
public final class ServerPayloads {
    private ServerPayloads() {
    }

    public static void onChooseMode(ChooseModePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.choose(player, payload.hivemind());
        }
    }

    public static void onSpawnUnit(SpawnUnitPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.spawnUnit(player, payload.kind());
        }
    }
}
