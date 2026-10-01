package com.projecthivemind.client;

import java.util.Locale;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive menu: shared storage, a crafting grid, the soldier gear slots and unit counts on the Hive tab, and the
 * quest list on the Quests tab. Drawn with plain rectangles until there is real art.
 */
public class HiveScreen extends AbstractContainerScreen<HiveMenu> {
    private static final int PANEL = 0xF0201414;
    private static final int PANEL_EDGE = 0xFF6B2A2A;
    private static final int SLOT_EDGE = 0xFF120A0A;
    private static final int SLOT_FILL = 0xFF3A2424;

    /** Where the row of unit counts sits, and how far apart its three entries are. */
    private static final int COUNTS_Y = 148;
    private static final int COUNTS_SPACING = 72;

    private Button hiveTab;
    private Button questsTab;

    public HiveScreen(HiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 300;
        this.imageHeight = 168;
        this.titleLabelX = 8;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();
        hiveTab = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.hive.tab_hive"),
                button -> showQuests(false)).bounds(leftPos + 8, topPos + 28, 70, 18).build());
        questsTab = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.hive.tab_quests"),
                button -> showQuests(true)).bounds(leftPos + 82, topPos + 28, 70, 18).build());

        // Creative players can drop out of the hive to the normal inventory, e.g. to spawn items in for testing.
        if (ClientState.canSwapInventory()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.swap.to_normal"), button -> {
                PacketDistributor.sendToServer(new ToggleInventoryModePayload());
                onClose();
            }).bounds(leftPos + imageWidth - 8 - 90, topPos + 28, 90, 18).build());
        }
        showQuests(menu.questsOpen);
    }

    private void showQuests(boolean quests) {
        menu.questsOpen = quests;
        hiveTab.active = quests;
        questsTab.active = !quests;
    }

    /**
     * The server ignores vanilla container clicks from spectators, which the bodyless hivemind is. Send the click
     * over our own packet instead; the server applies it and sends the result back.
     */
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null) {
            slotId = slot.index;
        }
        PacketDistributor.sendToServer(new HiveMenuClickPayload(menu.containerId, slotId, mouseButton, type));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, PANEL_EDGE);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);

        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                int x = leftPos + slot.x;
                int y = topPos + slot.y;
                graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_EDGE);
                graphics.fill(x, y, x + 16, y + 16, SLOT_FILL);
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.header", menu.level()), titleLabelX, titleLabelY, 0xFFFFFF, false);

        String hearts = "♥ " + hearts(menu.health()) + " / " + hearts(menu.maxHealth());
        graphics.drawString(font, hearts, imageWidth - 8 - font.width(hearts), titleLabelY, 0xFF5555, false);

        if (menu.questsOpen) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.no_quests"), 8, 58, 0xA0A0A0, false);
        } else {
            // Same order as the command bar's keys: scouts, soldiers, workers; then the collectors, which you do not command.
            drawUnitCount(graphics, 0, "screen.projecthivemind.hive.scouts", UnitKind.SCOUT);
            drawUnitCount(graphics, 1, "screen.projecthivemind.hive.soldiers", UnitKind.SOLDIER);
            drawUnitCount(graphics, 2, "screen.projecthivemind.hive.workers", UnitKind.WORKER);
            drawUnitCount(graphics, 3, "screen.projecthivemind.hive.collectors", UnitKind.COLLECTOR);

            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.armor"), HiveMenu.ARMOR_X, 44, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.storage"), HiveMenu.STORAGE_X, 44, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.crafting"), HiveMenu.GRID_X, 44, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.tools"),
                    HiveMenu.TOOLS_X + 5 * 18 + 6, HiveMenu.TOOLS_Y + 5, 0xA0A0A0, false);
        }
    }

    /**
     * "Workers 1/1": units out now, out of the most the hive allows (orange at the limit). Underneath, what the hive's
     * next 10-second interval will do for them, with the countdown.
     */
    private void drawUnitCount(GuiGraphics graphics, int column, String labelKey, UnitKind kind) {
        int x = HiveMenu.STORAGE_X + column * COUNTS_SPACING;
        boolean atLimit = menu.unitCount(kind) >= menu.unitCap(kind);
        graphics.drawString(font, Component.translatable(labelKey, menu.unitCount(kind), menu.unitCap(kind)),
                x, COUNTS_Y, atLimit ? 0xFFAA00 : 0xFFFFFF, false);

        int seconds = menu.secondsUntilSpawn();
        Component status;
        int colour;
        switch (menu.unitStatus(kind)) {
            case HiveMenu.STATUS_SPAWNING -> {
                status = Component.translatable("screen.projecthivemind.hive.status.spawning", seconds);
                colour = 0x77DD77;
            }
            case HiveMenu.STATUS_REFRESHING -> {
                status = Component.translatable("screen.projecthivemind.hive.status.refreshing", seconds);
                colour = 0xFFDD55;
            }
            default -> {
                status = Component.translatable("screen.projecthivemind.hive.status.idle");
                colour = 0x808080;
            }
        }
        graphics.drawString(font, status, x, COUNTS_Y + 10, colour, false);
    }

    /** Health points as hearts: 2 points per heart, dropping a trailing ".0". */
    private static String hearts(int healthPoints) {
        float hearts = healthPoints / 2.0F;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.format(Locale.ROOT, "%.1f", hearts);
    }
}
