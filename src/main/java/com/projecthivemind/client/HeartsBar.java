package com.projecthivemind.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Health drawn as the game's hearts: two points a heart, ten to a row, rows going down. */
final class HeartsBar {
    private static final ResourceLocation CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final int PER_ROW = 10;

    private HeartsBar() {
    }

    static int hearts(float maxHealth) {
        return Math.max(1, (int) Math.ceil(maxHealth / 2.0F));
    }

    /** How wide the bar is, in pixels. */
    static int width(float maxHealth) {
        return Math.min(hearts(maxHealth), PER_ROW) * 8 + 1;
    }

    /** How tall the bar is, in pixels. */
    static int height(float maxHealth) {
        return ((hearts(maxHealth) + PER_ROW - 1) / PER_ROW) * 10 - 1;
    }

    static void draw(GuiGraphics graphics, int x, int y, float health, float maxHealth) {
        int points = (int) Math.ceil(health);
        for (int i = 0; i < hearts(maxHealth); i++) {
            int heartX = x + (i % PER_ROW) * 8;
            int heartY = y + (i / PER_ROW) * 10;
            graphics.blitSprite(CONTAINER, heartX, heartY, 9, 9);
            if (points >= i * 2 + 2) {
                graphics.blitSprite(FULL, heartX, heartY, 9, 9);
            } else if (points == i * 2 + 1) {
                graphics.blitSprite(HALF, heartX, heartY, 9, 9);
            }
        }
    }
}
