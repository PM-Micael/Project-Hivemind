package com.projecthivemind.client;

import javax.annotation.Nullable;

import com.projecthivemind.menu.ScoutTradeMenu;
import com.projecthivemind.menu.StorageScroll;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.ScrollStoragePayload;
import com.projecthivemind.network.TradePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Trading with a villager through a scout: its offers in two columns, the hive's storage underneath as the purse.
 * Click an offer to buy it once; it is paid from, and delivered to, the hive.
 */
public class ScoutTradeScreen extends AbstractContainerScreen<ScoutTradeMenu> {
    private static final int COLUMNS = 2;
    private static final int ROWS = 6;
    private static final int CELL_X = 6;
    private static final int CELL_Y = 18;
    private static final int CELL_W = 82;
    private static final int CELL_H = 20;
    private static final int CELL_PITCH_Y = 21;

    private static final int CAN_BUY = 0xFF2E4A2E;
    private static final int CANNOT_BUY = 0xFF4A2E2E;
    private static final int SOLD_OUT = 0xFF262626;
    private static final int HOVER = 0xFFFFFFFF;

    public ScoutTradeScreen(ScoutTradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = ScoutTradeMenu.panelHeight(menu.storageSlots());
    }

    /** The offer index under this screen position, or -1. */
    private int offerAt(double mouseX, double mouseY) {
        MerchantOffers offers = menu.offers();
        for (int i = 0; i < Math.min(offers.size(), COLUMNS * ROWS); i++) {
            int x = leftPos + CELL_X + (i % COLUMNS) * (CELL_W + 2);
            int y = topPos + CELL_Y + (i / COLUMNS) * CELL_PITCH_Y;
            if (mouseX >= x && mouseX < x + CELL_W && mouseY >= y && mouseY < y + CELL_H) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = offerAt(mouseX, mouseY);
        if (index >= 0 && button == 0) {
            MerchantOffer offer = menu.offers().get(index);
            if (!offer.isOutOfStock() && menu.affordable(offer)) {
                PacketDistributor.sendToServer(new TradePayload(menu.containerId, index));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }


    /** The mouse wheel over the hive storage scrolls it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        StorageScroll scroll = menu.storageScroll();
        if (scroll.maxRow() > 0 && mouseX >= leftPos + 8 && mouseX < leftPos + 8 + 9 * 18 + 6
                && mouseY >= topPos + ScoutTradeMenu.STORAGE_Y && mouseY < topPos + ScoutTradeMenu.STORAGE_Y + scroll.visibleRows() * 18) {
            int row = HiveStyle.scrolledRow(scroll.row(), scrollY, scroll.maxRow());
            if (row != scroll.row()) {
                PacketDistributor.sendToServer(new ScrollStoragePayload(menu.containerId, row));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null) {
            slotId = slot.index;
        }
        PacketDistributor.sendToServer(new HiveMenuClickPayload(menu.containerId, slotId, mouseButton, type));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        HiveStyle.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        HiveStyle.slots(graphics, menu, leftPos, topPos);
        StorageScroll storageScroll = menu.storageScroll();
        HiveStyle.scrollbar(graphics, leftPos + 8 + 9 * 18 + 1, topPos + ScoutTradeMenu.STORAGE_Y, storageScroll.visibleRows() * 18,
                storageScroll.totalRows(), storageScroll.visibleRows(), storageScroll.row());

        MerchantOffers offers = menu.offers();
        int hovered = offerAt(mouseX, mouseY);
        for (int i = 0; i < Math.min(offers.size(), COLUMNS * ROWS); i++) {
            MerchantOffer offer = offers.get(i);
            int x = leftPos + CELL_X + (i % COLUMNS) * (CELL_W + 2);
            int y = topPos + CELL_Y + (i / COLUMNS) * CELL_PITCH_Y;
            int color = offer.isOutOfStock() ? SOLD_OUT : menu.affordable(offer) ? CAN_BUY : CANNOT_BUY;
            if (i == hovered) {
                graphics.fill(x - 1, y - 1, x + CELL_W + 1, y + CELL_H + 1, HOVER);
            }
            graphics.fill(x, y, x + CELL_W, y + CELL_H, color);

            drawStack(graphics, offer.getCostA(), x + 2, y + 2);
            drawStack(graphics, offer.getCostB(), x + 22, y + 2);
            graphics.drawString(font, ">", x + 44, y + 6, 0xFFE0E0E0, false);
            drawStack(graphics, offer.getResult(), x + 58, y + 2);
            if (offer.isOutOfStock()) {
                graphics.drawString(font, "X", x + CELL_W - 10, y + 6, 0xFFFF5555, false);
            }
        }
    }

    private void drawStack(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, HiveStyle.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.scout.hive_storage"), 8,
                ScoutTradeMenu.STORAGE_Y - 11, HiveStyle.LABEL, false);
        if (menu.offers().isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.scout.loading_trades"), 8, CELL_Y + 4, 0xA0A0A0, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);

        // Hovering an item in an offer shows what it is.
        int index = offerAt(mouseX, mouseY);
        if (index >= 0) {
            MerchantOffer offer = menu.offers().get(index);
            int x = leftPos + CELL_X + (index % COLUMNS) * (CELL_W + 2);
            int relative = mouseX - x;
            ItemStack shown = relative < 20 ? offer.getCostA() : relative < 40 ? offer.getCostB() : relative >= 56 ? offer.getResult() : ItemStack.EMPTY;
            if (!shown.isEmpty()) {
                graphics.renderTooltip(font, shown, mouseX, mouseY);
            }
        }
    }
}
