package com.projecthivemind.client;

import javax.annotation.Nullable;

import com.projecthivemind.HivemindStage;

/**
 * What the local client knows about its own player's Hivemind state, as last synced by the server.
 * Holds plain data only (no Minecraft client classes) so common code can read it safely.
 */
public final class ClientState {
    @Nullable
    private static HivemindStage stage;
    private static boolean hasWorker;
    private static boolean hasSoldier;

    private ClientState() {
    }

    public static void update(HivemindStage newStage, boolean worker, boolean soldier) {
        stage = newStage;
        hasWorker = worker;
        hasSoldier = soldier;
    }

    /** Forget everything, e.g. when leaving a world, so state never leaks into the next one. */
    public static void reset() {
        stage = null;
        hasWorker = false;
        hasSoldier = false;
    }

    /** Null until the server has told us. */
    @Nullable
    public static HivemindStage stage() {
        return stage;
    }

    public static boolean is(HivemindStage expected) {
        return stage == expected;
    }

    public static boolean hasWorker() {
        return hasWorker;
    }

    public static boolean hasSoldier() {
        return hasSoldier;
    }
}
