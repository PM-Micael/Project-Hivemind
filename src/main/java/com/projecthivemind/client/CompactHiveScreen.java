package com.projecthivemind.client;

import javax.annotation.Nullable;

import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.menu.StorageScroll;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.ScrollStoragePayload;
import com.projecthivemind.network.SetMenuViewPayload;
import com.projecthivemind.network.SetStorageSearchPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive inventory made small, about the size and layout of the game's own: the storage in three rows (scrolled with the wheel, with its
 * search), and the crafting grid beside it. No tabs and no other workstations. For playing with a recipe viewer such as JEI, which wants the
 * room the full menu takes. It is the same menu as {@link HiveScreen}: only what is drawn and where the slots are differs.
 */
public class CompactHiveScreen extends AbstractContainerScreen<HiveMenu> {
    private static final int WIDTH = 184;
    private static final int HEIGHT = 154;
    private static final int GROUPS = HiveMenu.GROUP_STORAGE | HiveMenu.GROUP_CRAFT | HiveMenu.GROUP_COMPACT;
    /** The workstation groups whose slots are hidden, all but the crafting grid. */
    private static final int[] OTHER_STATIONS = {HiveMenu.GROUP_FURNACE, HiveMenu.GROUP_BREWING, HiveMenu.GROUP_ENCHANT, HiveMenu.GROUP_JUKEBOX,
            HiveMenu.GROUP_CARTOGRAPHY, HiveMenu.GROUP_ANVIL, HiveMenu.GROUP_TRASH};

    private final Inventory inventory;
    private EditBox search;
    /** Set while this screen is being swapped for the full one, so that the menu is not closed with it. */
    private boolean switching;

    public CompactHiveScreen(HiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.inventory = inventory;
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        menu.setCompact(true);
        menu.visibleGroups = GROUPS;
        menu.layoutStation(HiveMenu.GROUP_CRAFT, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        for (int group : OTHER_STATIONS) {
            menu.layoutStation(group, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }
        PacketDistributor.sendToServer(new SetMenuViewPayload(menu.containerId, GROUPS));

        search = addRenderableWidget(new EditBox(font, leftPos + HiveMenu.COMPACT_STORAGE_X + 48, topPos + HiveMenu.COMPACT_STORAGE_Y - 16, 9 * 18 - 48, 12,
                Component.translatable("screen.projecthivemind.hive.search")));
        search.setHint(Component.translatable("screen.projecthivemind.hive.search"));
        search.setMaxLength(32);
        search.setBordered(true);
        search.setResponder(text -> PacketDistributor.sendToServer(new SetStorageSearchPayload(menu.containerId, text)));

        Button full = Button.builder(Component.translatable("screen.projecthivemind.compact.off"), button -> switchToFull())
                .bounds(leftPos + WIDTH - 8 - 64, topPos + 4, 64, 16).build();
        full.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.compact.off.tooltip")));
        addRenderableWidget(full);
    }

    private void switchToFull() {
        ClientConfig.setCompactInventory(false);
        // The full screen starts with an empty search box, so the storage is shown whole again.
        PacketDistributor.sendToServer(new SetStorageSearchPayload(menu.containerId, ""));
        switching = true;
        Minecraft.getInstance().setScreen(new HiveScreen(menu, inventory, getTitle()));
    }

    /** Swapping screens must not close the menu. */
    @Override
    public void removed() {
        if (!switching) {
            super.removed();
        }
    }

    /** The server ignores vanilla container clicks from spectators, so clicks go over the mod's own packet. */
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
        HiveStyle.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        HiveStyle.slots(graphics, menu, leftPos, topPos);
        StorageScroll scroll = menu.storageScroll();
        HiveStyle.scrollbar(graphics, leftPos + HiveMenu.COMPACT_STORAGE_X + 9 * 18 + 1, topPos + HiveMenu.COMPACT_STORAGE_Y,
                scroll.visibleRows() * 18, scroll.totalRows(), scroll.visibleRows(), scroll.row());
        // The arrow from the crafting grid to its result.
        int arrowX = leftPos + HiveMenu.COMPACT_GRID_X + 3 * 18 + 4;
        int arrowY = topPos + HiveMenu.COMPACT_RESULT_Y + 7;
        graphics.fill(arrowX, arrowY, arrowX + 10, arrowY + 2, 0xFF9A4A4A);
        graphics.fill(arrowX + 8, arrowY - 2, arrowX + 10, arrowY + 4, 0xFF9A4A4A);
        graphics.fill(arrowX + 10, arrowY - 1, arrowX + 12, arrowY + 3, 0xFF9A4A4A);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.storage"), HiveMenu.COMPACT_STORAGE_X, HiveMenu.COMPACT_STORAGE_Y - 14, HiveStyle.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.crafting"), HiveMenu.COMPACT_GRID_X, 6, HiveStyle.LABEL, false);
    }

    /** While the search box has the keyboard, keys are for typing: otherwise the inventory key (E) would close the menu on every E typed. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search != null && search.isFocused() && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            return search.keyPressed(keyCode, scanCode, modifiers) || search.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** The mouse wheel over the storage scrolls it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        StorageScroll scroll = menu.storageScroll();
        if (scroll.maxRow() > 0 && mouseX >= leftPos + HiveMenu.COMPACT_STORAGE_X && mouseX < leftPos + HiveMenu.COMPACT_STORAGE_X + 9 * 18 + 6
                && mouseY >= topPos + HiveMenu.COMPACT_STORAGE_Y && mouseY < topPos + HiveMenu.COMPACT_STORAGE_Y + scroll.visibleRows() * 18) {
            int row = HiveStyle.scrolledRow(scroll.row(), scrollY, scroll.maxRow());
            if (row != scroll.row()) {
                PacketDistributor.sendToServer(new ScrollStoragePayload(menu.containerId, row));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
