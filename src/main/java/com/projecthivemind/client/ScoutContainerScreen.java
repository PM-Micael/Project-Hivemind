package com.projecthivemind.client;

import javax.annotation.Nullable;

import com.projecthivemind.menu.ScoutContainerMenu;
import com.projecthivemind.menu.StorageScroll;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.ScrollStoragePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/** A container opened through a scout: its slots on top, the hive's storage underneath. */
public class ScoutContainerScreen extends AbstractContainerScreen<ScoutContainerMenu> {
    public ScoutContainerScreen(ScoutContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = ScoutContainerMenu.panelHeight(menu.targetSize(), menu.storageSlots());
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


    private int storageTop() {
        return ScoutContainerMenu.storageY(menu.targetSize());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        HiveStyle.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        HiveStyle.slots(graphics, menu, leftPos, topPos);
        StorageScroll scroll = menu.storageScroll();
        HiveStyle.scrollbar(graphics, leftPos + 8 + 9 * 18 + 1, topPos + storageTop(), scroll.visibleRows() * 18,
                scroll.totalRows(), scroll.visibleRows(), scroll.row());
    }

    /** The mouse wheel over the hive storage scrolls it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        StorageScroll scroll = menu.storageScroll();
        if (scroll.maxRow() > 0 && mouseX >= leftPos + 8 && mouseX < leftPos + 8 + 9 * 18 + 6
                && mouseY >= topPos + storageTop() && mouseY < topPos + storageTop() + scroll.visibleRows() * 18) {
            int row = HiveStyle.scrolledRow(scroll.row(), scrollY, scroll.maxRow());
            if (row != scroll.row()) {
                PacketDistributor.sendToServer(new ScrollStoragePayload(menu.containerId, row));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, HiveStyle.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.scout.hive_storage"), 8,
                ScoutContainerMenu.storageY(menu.targetSize()) - 11, HiveStyle.LABEL, false);
    }
}
