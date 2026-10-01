package com.projecthivemind.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** The hive menus' look, shared by every screen the hivemind sees: dark red panel, dark slots. */
final class HiveStyle {
    static final int PANEL = 0xF0201414;
    static final int PANEL_EDGE = 0xFF6B2A2A;
    static final int SLOT_EDGE = 0xFF120A0A;
    static final int SLOT_FILL = 0xFF3A2424;
    static final int LABEL = 0xFFFFFF;

    private HiveStyle() {
    }

    static void panel(GuiGraphics graphics, int left, int top, int width, int height) {
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, PANEL_EDGE);
        graphics.fill(left, top, left + width, top + height, PANEL);
    }

    static void slots(GuiGraphics graphics, AbstractContainerMenu menu, int left, int top) {
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                int x = left + slot.x;
                int y = top + slot.y;
                graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_EDGE);
                graphics.fill(x, y, x + 16, y + 16, SLOT_FILL);
            }
        }
    }
}
