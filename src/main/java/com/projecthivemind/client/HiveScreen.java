package com.projecthivemind.client;

import java.util.Locale;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.SpawnUnitPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive menu: shared storage, a crafting grid and unit spawning on the Hive tab, and the quest list on the
 * Quests tab. Drawn with plain rectangles until there is real art.
 */
public class HiveScreen extends AbstractContainerScreen<HiveMenu> {
    private static final int PANEL = 0xF0201414;
    private static final int PANEL_EDGE = 0xFF6B2A2A;
    private static final int SLOT_EDGE = 0xFF120A0A;
    private static final int SLOT_FILL = 0xFF3A2424;

    private Button hiveTab;
    private Button questsTab;
    private Button workerButton;
    private Button soldierButton;
    private Tooltip replaceTooltip;

    private static final int BUTTON_WIDTH = 60;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_Y = 142;
    /** Horizontal gap between the two spawn buttons' columns, leaving room for each one's counter. */
    private static final int BUTTON_COLUMN_SPACING = 100;

    public HiveScreen(HiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 280;
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

        // No Collector button: the Heart makes collectors itself.
        replaceTooltip = Tooltip.create(Component.translatable("screen.projecthivemind.hive.replace_oldest"));
        workerButton = spawnButton(0, UnitKind.WORKER, "screen.projecthivemind.hive.spawn_worker");
        soldierButton = spawnButton(1, UnitKind.SOLDIER, "screen.projecthivemind.hive.spawn_soldier");
        showQuests(menu.questsOpen);
    }

    private Button spawnButton(int column, UnitKind kind, String key) {
        return addRenderableWidget(Button.builder(Component.translatable(key),
                        button -> PacketDistributor.sendToServer(new SpawnUnitPayload(kind)))
                .bounds(leftPos + spawnButtonX(column), topPos + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private static int spawnButtonX(int column) {
        return HiveMenu.STORAGE_X + column * BUTTON_COLUMN_SPACING;
    }

    private void showQuests(boolean quests) {
        menu.questsOpen = quests;
        hiveTab.active = quests;
        questsTab.active = !quests;
        workerButton.visible = !quests;
        soldierButton.visible = !quests;
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
        // The buttons always work. At the limit a click replaces the oldest unit, so say so on hover.
        workerButton.setTooltip(atLimit(UnitKind.WORKER) ? replaceTooltip : null);
        soldierButton.setTooltip(atLimit(UnitKind.SOLDIER) ? replaceTooltip : null);

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
            drawUnitCounter(graphics, 0, UnitKind.WORKER);
            drawUnitCounter(graphics, 1, UnitKind.SOLDIER);
            String collectors = Component.translatable("screen.projecthivemind.hive.collectors",
                    menu.unitCount(UnitKind.COLLECTOR), menu.unitCap(UnitKind.COLLECTOR)).getString();
            graphics.drawString(font, collectors, imageWidth - 8 - font.width(collectors), BUTTON_Y + 6, 0xA0A0A0, false);

            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.armor"), HiveMenu.ARMOR_X, 44, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.storage"), HiveMenu.STORAGE_X, 44, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.crafting"), HiveMenu.GRID_X, 44, 0xA0A0A0, false);
            graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.tools"),
                    HiveMenu.TOOLS_X + 5 * 18 + 6, HiveMenu.TOOLS_Y + 5, 0xA0A0A0, false);
        }
    }

    private boolean atLimit(UnitKind kind) {
        return menu.unitCount(kind) >= menu.unitCap(kind);
    }

    /** "1/1" beside a spawn button: units out now, out of the most the hive allows. */
    private void drawUnitCounter(GuiGraphics graphics, int column, UnitKind kind) {
        graphics.drawString(font, menu.unitCount(kind) + "/" + menu.unitCap(kind),
                spawnButtonX(column) + BUTTON_WIDTH + 5, BUTTON_Y + 6, atLimit(kind) ? 0xFFAA00 : 0xFFFFFF, false);
    }

    /** Health points as hearts: 2 points per heart, dropping a trailing ".0". */
    private static String hearts(int healthPoints) {
        float hearts = healthPoints / 2.0F;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.format(Locale.ROOT, "%.1f", hearts);
    }
}
