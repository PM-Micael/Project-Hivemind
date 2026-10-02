package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveCollector;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Picking what a collector plants: every seed or crop (or, for the saplings task, every sapling) that can be planted, as a grid of icons. Click one to choose it;
 * the buttons under the grid clear the choice, or cancel. Shown over the hive menu, which comes back afterwards.
 */
public class SeedPickerScreen extends Screen {
    private static final int CELL = 22;
    private static final int COLUMNS = 8;
    private static final int PADDING = 12;

    private final Screen parent;
    private final HiveCollector.PlantKind kind;
    private final Consumer<Item> onPick;
    private final List<Item> seeds = new ArrayList<>();

    /** @param onPick called with the chosen seed, or null to clear it */
    public SeedPickerScreen(Screen parent, HiveCollector.PlantKind kind, Consumer<Item> onPick) {
        super(Component.translatable(kind == HiveCollector.PlantKind.SAPLING ? "screen.projecthivemind.sapling.title" : "screen.projecthivemind.seed.title"));
        this.kind = kind;
        this.parent = parent;
        this.onPick = onPick;
        for (Item item : BuiltInRegistries.ITEM) {
            if (HiveCollector.plantBlock(kind, item) != null) {
                seeds.add(item);
            }
        }
    }

    private int panelWidth() {
        return COLUMNS * CELL + PADDING * 2;
    }

    private int panelHeight() {
        int rows = (seeds.size() + COLUMNS - 1) / COLUMNS;
        return 30 + rows * CELL + 40;
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    private int top() {
        return (height - panelHeight()) / 2;
    }

    @Override
    protected void init() {
        int buttonY = top() + panelHeight() - 28;
        int half = (panelWidth() - PADDING * 2 - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.seed.none"), button -> choose(null))
                .bounds(left() + PADDING, buttonY, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left() + PADDING + half + 4, buttonY, half, 20).build());
    }

    private void choose(@Nullable Item item) {
        onPick.accept(item);
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** The seed under the mouse, or null. */
    @Nullable
    private Item seedAt(double mouseX, double mouseY) {
        double x = mouseX - (left() + PADDING);
        double y = mouseY - (top() + 24);
        if (x < 0 || y < 0 || x >= COLUMNS * CELL) {
            return null;
        }
        int index = (int) (y / CELL) * COLUMNS + (int) (x / CELL);
        return index >= 0 && index < seeds.size() ? seeds.get(index) : null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Item item = seedAt(mouseX, mouseY);
        if (item != null && button == 0) {
            choose(item);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        HiveStyle.panel(graphics, left(), top(), panelWidth(), panelHeight());
        graphics.drawString(font, title, left() + PADDING, top() + 10, HiveStyle.LABEL, false);
        Item hovered = seedAt(mouseX, mouseY);
        for (int i = 0; i < seeds.size(); i++) {
            int x = left() + PADDING + (i % COLUMNS) * CELL;
            int y = top() + 24 + (i / COLUMNS) * CELL;
            graphics.fill(x, y, x + CELL - 2, y + CELL - 2, seeds.get(i) == hovered ? 0xFF6B2A2A : 0xFF3A2424);
            graphics.renderItem(new ItemStack(seeds.get(i)), x + 2, y + 2);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Item hovered = seedAt(mouseX, mouseY);
        if (hovered != null) {
            graphics.renderTooltip(font, new ItemStack(hovered), mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
