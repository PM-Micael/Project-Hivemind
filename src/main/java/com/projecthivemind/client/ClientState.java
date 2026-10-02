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
    private static float heartHealth;
    private static float heartMaxHealth;
    private static int heartArmor;
    @Nullable
    private static net.minecraft.core.BlockPos borderCenter;
    private static int borderRadius;
    private static int hiveFood = 20;

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
        heartHealth = 0.0F;
        heartMaxHealth = 0.0F;
        heartArmor = 0;
        borderCenter = null;
        hiveFood = 20;
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

    /** The Hive Heart's health, as last told by the server, for the health bar. */
    public static void updateHeartHealth(float health, float maxHealth, int armor, int food) {
        hiveFood = food;
        heartArmor = armor;
        heartHealth = health;
        heartMaxHealth = maxHealth;
    }

    /** Where the hive area is: the Heart's block, and how many blocks the area reaches from it each way. */
    public static void updateBorder(net.minecraft.core.BlockPos center, int radius) {
        borderCenter = radius < 0 ? null : center;
        borderRadius = radius;
    }

    @Nullable
    public static net.minecraft.core.BlockPos borderCenter() {
        return borderCenter;
    }

    public static int borderRadius() {
        return borderRadius;
    }

    public static int hiveFood() {
        return hiveFood;
    }

    public static int heartArmor() {
        return heartArmor;
    }

    public static float heartHealth() {
        return heartHealth;
    }

    public static float heartMaxHealth() {
        return heartMaxHealth;
    }

    public static boolean canSwapInventory() {
        return canSwapInventory;
    }
}
