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
    private static boolean normalInventory;
    private static boolean canSwapInventory;

    private ClientState() {
    }

    public static void update(HivemindStage newStage, boolean normal, boolean canSwap) {
        stage = newStage;
        normalInventory = normal;
        canSwapInventory = canSwap;
    }

    /** Forget everything, e.g. when leaving a world, so state never leaks into the next one. */
    public static void reset() {
        stage = null;
        normalInventory = false;
        canSwapInventory = false;
    }

    /** Null until the server has told us. */
    @Nullable
    public static HivemindStage stage() {
        return stage;
    }

    public static boolean is(HivemindStage expected) {
        return stage == expected;
    }

    /** Bodyless hivemind with the RTS camera, hive menu and no vanilla inventory. */
    public static boolean hiveMode() {
        return stage == HivemindStage.HIVE && !normalInventory;
    }

    /** A creative hivemind who has swapped to the normal inventory: plays like normal creative for now. */
    public static boolean normalInventoryMode() {
        return stage == HivemindStage.HIVE && normalInventory;
    }

    public static boolean canSwapInventory() {
        return canSwapInventory;
    }
}
