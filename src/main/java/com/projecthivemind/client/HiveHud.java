package com.projecthivemind.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * The hivemind's status bars, drawn where the player's own are in the vanilla game and with the same sprites. The
 * bodyless hivemind has no health of its own, so the hearts and the armor are the Hive Heart's (its health, and the
 * armor in the hive's armor slots). The experience bar is the player's own: the game does not draw it for a spectator,
 * and the hivemind is one. More than ten hearts wrap into rows going up, like vanilla does with extra health.
 */
public final class HiveHud {
    private static final ResourceLocation CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation ARMOR_EMPTY = ResourceLocation.withDefaultNamespace("hud/armor_empty");
    private static final ResourceLocation ARMOR_HALF = ResourceLocation.withDefaultNamespace("hud/armor_half");
    private static final ResourceLocation ARMOR_FULL = ResourceLocation.withDefaultNamespace("hud/armor_full");
    private static final ResourceLocation XP_BACKGROUND = ResourceLocation.withDefaultNamespace("hud/experience_bar_background");
    private static final ResourceLocation XP_PROGRESS = ResourceLocation.withDefaultNamespace("hud/experience_bar_progress");
    private static final int HEARTS_PER_ROW = 10;
    private static final int XP_BAR_WIDTH = 182;
    private static final int XP_LEVEL_COLOUR = 0x80FF20;

    private HiveHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientState.hiveMode() || minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        drawExperience(graphics, minecraft.font, minecraft.player);

        float maxHealth = ClientState.heartMaxHealth();
        if (maxHealth <= 0.0F) {
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

        // The armor, in a row above the hearts, only when there is any (as in vanilla).
        int armor = ClientState.heartArmor();
        if (armor > 0) {
            int armorY = bottom - (rows - 1) * rowHeight - 10;
            for (int i = 0; i < 10; i++) {
                int x = left + i * 8;
                int point = i * 2 + 1;
                ResourceLocation sprite = point < armor ? ARMOR_FULL : point == armor ? ARMOR_HALF : ARMOR_EMPTY;
                graphics.blitSprite(sprite, x, armorY, 9, 9);
            }
        }
    }

    /** The experience bar and level, in the vanilla place just above where the hotbar would be. */
    private static void drawExperience(GuiGraphics graphics, Font font, LocalPlayer player) {
        int left = graphics.guiWidth() / 2 - 91;
        int top = graphics.guiHeight() - 32 + 3;
        graphics.blitSprite(XP_BACKGROUND, left, top, XP_BAR_WIDTH, 5);
        int filled = (int) (player.experienceProgress * 183.0F);
        if (filled > 0) {
            graphics.blitSprite(XP_PROGRESS, XP_BAR_WIDTH, 5, 0, 0, left, top, filled, 5);
        }
        if (player.experienceLevel > 0) {
            String level = String.valueOf(player.experienceLevel);
            int x = (graphics.guiWidth() - font.width(level)) / 2;
            int y = graphics.guiHeight() - 31 - 4;
            // The black outline vanilla gives the number, so it reads on any background.
            graphics.drawString(font, level, x + 1, y, 0, false);
            graphics.drawString(font, level, x - 1, y, 0, false);
            graphics.drawString(font, level, x, y + 1, 0, false);
            graphics.drawString(font, level, x, y - 1, 0, false);
            graphics.drawString(font, level, x, y, XP_LEVEL_COLOUR, false);
        }
    }
}
