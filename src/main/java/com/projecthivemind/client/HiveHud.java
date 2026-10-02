package com.projecthivemind.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The Hive Heart's health, drawn where the player's own health is in the vanilla game (above the hotbar, on the left)
 * and with the same hearts. The bodyless hivemind has no health of its own: its life is the Heart's. More than ten
 * hearts wrap into rows going up, like vanilla does with extra health.
 */
public final class HiveHud {
    private static final ResourceLocation CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final int HEARTS_PER_ROW = 10;

    private HiveHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        float maxHealth = ClientState.heartMaxHealth();
        if (!ClientState.hiveMode() || maxHealth <= 0.0F || minecraft.options.hideGui) {
            return;
        }
        int health = (int) Math.ceil(ClientState.heartHealth());
        int hearts = (int) Math.ceil(maxHealth / 2.0F);
        int rows = (hearts + HEARTS_PER_ROW - 1) / HEARTS_PER_ROW;
        // Vanilla squeezes the rows together as they pile up, so a lot of health does not climb off the screen.
        int rowHeight = Math.max(10 - (rows - 2), 3);

        int left = graphics.guiWidth() / 2 - 91;
        int bottom = graphics.guiHeight() - 39;
        for (int i = 0; i < hearts; i++) {
            int x = left + (i % HEARTS_PER_ROW) * 8;
            int y = bottom - (i / HEARTS_PER_ROW) * rowHeight;
            graphics.blitSprite(CONTAINER, x, y, 9, 9);
            if (health >= i * 2 + 2) {
                graphics.blitSprite(FULL, x, y, 9, 9);
            } else if (health == i * 2 + 1) {
                graphics.blitSprite(HALF, x, y, 9, 9);
            }
        }
    }
}
