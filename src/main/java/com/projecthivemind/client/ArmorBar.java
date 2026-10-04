package com.projecthivemind.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Defence drawn as the game's armor icons: two points an icon, ten to a row, rows going down. Nothing at all when there is no defence. */
final class ArmorBar {
    private static final ResourceLocation EMPTY = ResourceLocation.withDefaultNamespace("hud/armor_empty");
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("hud/armor_full");
    private static final ResourceLocation HALF = ResourceLocation.withDefaultNamespace("hud/armor_half");
    private static final int PER_ROW = 10;

    private ArmorBar() {
    }

    /** The icons drawn: whole rows of ten, at least one row. */
    private static int slots(int armor) {
        int icons = (armor + 1) / 2;
        return Math.max(1, (icons + PER_ROW - 1) / PER_ROW) * PER_ROW;
    }

    /** How wide the bar is, in pixels (0 when there is no defence). */
    static int width(int armor) {
        return armor <= 0 ? 0 : PER_ROW * 8 + 1;
    }

    /** How tall the bar is, in pixels (0 when there is no defence). */
    static int height(int armor) {
        return armor <= 0 ? 0 : (slots(armor) / PER_ROW) * 10 - 1;
    }

    static void draw(GuiGraphics graphics, int x, int y, int armor) {
        if (armor <= 0) {
            return;
        }
        for (int i = 0; i < slots(armor); i++) {
            int iconX = x + (i % PER_ROW) * 8;
            int iconY = y + (i / PER_ROW) * 10;
            if (armor >= i * 2 + 2) {
                graphics.blitSprite(FULL, iconX, iconY, 9, 9);
            } else if (armor == i * 2 + 1) {
                graphics.blitSprite(HALF, iconX, iconY, 9, 9);
            } else {
                graphics.blitSprite(EMPTY, iconX, iconY, 9, 9);
            }
        }
    }
}
