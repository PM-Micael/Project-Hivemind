package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The small menu that pops up where the player right-clicks a block. It is drawn straight onto the screen rather than
 * being a screen of its own, so the camera keeps working and the cursor stays where it is while it is open.
 *
 * <p>Clicks are routed here by {@link HiveSelection}: a left click picks the option under the cursor, and any click
 * outside the menu dismisses it.
 */
public final class ContextMenu {
    private static final int ROW_HEIGHT = 14;
    private static final int PADDING = 4;
    private static final int BACKGROUND = 0xF0201414;
    private static final int BORDER = 0xFF6B2A2A;
    private static final int HOVER = 0xFF6B2A2A;
    private static final int TEXT = 0xFFFFFF;

    /** One row in the menu. */
    public record Option(Component label, Runnable action) {
    }

    private static final List<Option> OPTIONS = new ArrayList<>();
    private static boolean open;
    private static int left;
    private static int top;
    private static int width;

    private ContextMenu() {
    }

    public static boolean isOpen() {
        return open;
    }

    /** Open the menu with its top-left corner at a point in GUI coordinates, nudged to stay on the screen. */
    public static void open(Minecraft minecraft, int x, int y, List<Option> options) {
        OPTIONS.clear();
        OPTIONS.addAll(options);
        int textWidth = 0;
        for (Option option : OPTIONS) {
            textWidth = Math.max(textWidth, minecraft.font.width(option.label()));
        }
        width = textWidth + PADDING * 4;
        left = Mth.clamp(x, 0, Math.max(0, minecraft.getWindow().getGuiScaledWidth() - width));
        top = Mth.clamp(y, 0, Math.max(0, minecraft.getWindow().getGuiScaledHeight() - height()));
        open = true;
    }

    public static void close() {
        open = false;
        OPTIONS.clear();
    }

    private static int height() {
        return OPTIONS.size() * ROW_HEIGHT + PADDING * 2;
    }

    /** The row under a point in GUI coordinates, or -1. */
    private static int rowAt(int mouseX, int mouseY) {
        if (mouseX < left || mouseX >= left + width) {
            return -1;
        }
        int relative = mouseY - top - PADDING;
        int row = relative / ROW_HEIGHT;
        return relative >= 0 && row < OPTIONS.size() ? row : -1;
    }

    /** A left click: run the option under the cursor, if any. Either way the menu closes. */
    public static void click(Minecraft minecraft) {
        int[] cursor = cursor(minecraft);
        int row = rowAt(cursor[0], cursor[1]);
        Option chosen = row >= 0 ? OPTIONS.get(row) : null;
        close();
        if (chosen != null) {
            chosen.action().run();
        }
    }

    /** The mouse position in GUI coordinates, which is what everything on screen is drawn in. */
    public static int[] cursor(Minecraft minecraft) {
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(minecraft.getWindow().getWindow(), x, y);
        int guiX = (int) (x[0] * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth());
        int guiY = (int) (y[0] * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight());
        return new int[]{guiX, guiY};
    }

    /** Drawn as a GUI layer above everything else. */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!open) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int[] cursor = cursor(minecraft);
        int hovered = rowAt(cursor[0], cursor[1]);

        graphics.fill(left - 1, top - 1, left + width + 1, top + height() + 1, BORDER);
        graphics.fill(left, top, left + width, top + height(), BACKGROUND);
        for (int i = 0; i < OPTIONS.size(); i++) {
            int rowTop = top + PADDING + i * ROW_HEIGHT;
            if (i == hovered) {
                graphics.fill(left + 2, rowTop, left + width - 2, rowTop + ROW_HEIGHT, HOVER);
            }
            graphics.drawString(minecraft.font, OPTIONS.get(i).label(), left + PADDING * 2, rowTop + 3, TEXT, false);
        }
    }
}
