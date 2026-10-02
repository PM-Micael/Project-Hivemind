package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.build.TowerDirection;
import com.projecthivemind.build.TowerMaterial;
import com.projecthivemind.build.TowerPlan;
import com.projecthivemind.build.TowerSet;
import com.projecthivemind.build.TowerShape;
import com.projecthivemind.network.BuildTowerPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The order for a tower, or a shaft dug down: which direction, which kind, what it is made of (one or more
 * materials), how tall or deep, and whether it has walls or just four corner pillars, then Build. The workers and the
 * block it is on were picked before this opened.
 */
public class BuildTowerScreen extends Screen {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 284;
    private static final int ROW = 32;

    private final List<Integer> workers;
    private final BlockPos pos;
    private int direction;
    private int shape;
    /** The materials picked, one bit each (see TowerSet#bit). At least one is always picked. */
    private int materials = TowerSet.bit(TowerMaterial.WOOD);
    private int heightIndex = 1;
    private boolean walls = true;
    private boolean torches;
    private Button torchesButton;
    private final Button[] directionButtons = new Button[TowerDirection.values().length];
    private final Button[] shapeButtons = new Button[TowerShape.values().length];
    private final Button[] materialButtons = new Button[TowerMaterial.values().length];
    private final Button[] heightButtons = new Button[TowerPlan.HEIGHTS.length];
    private Button wallsButton;
    private Button pillarsButton;

    public BuildTowerScreen(List<Integer> workers, BlockPos pos) {
        super(Component.translatable("screen.projecthivemind.tower.title"));
        this.workers = List.copyOf(workers);
        this.pos = pos.immutable();
        // The double tower needs two workers; with only one the single staircase is the choice.
        this.shape = this.workers.size() >= TowerShape.DOUBLE.minWorkers() ? TowerShape.DOUBLE.ordinal() : TowerShape.SPIRAL.ordinal();
    }

    private int rowY(int row) {
        return (height - HEIGHT) / 2 + 34 + row * ROW;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        int halfWidth = (WIDTH - 24 - 4) / 2;

        for (int i = 0; i < directionButtons.length; i++) {
            int index = i;
            directionButtons[i] = addRenderableWidget(Button.builder(
                    Component.translatable("screen.projecthivemind.tower.direction." + TowerDirection.byIndex(i).key()), button -> {
                        direction = index;
                        refresh();
                    }).bounds(left + 12 + i * (halfWidth + 4), rowY(0), halfWidth, 20).build());
        }
        for (int i = 0; i < shapeButtons.length; i++) {
            int index = i;
            shapeButtons[i] = addRenderableWidget(Button.builder(
                    Component.translatable("screen.projecthivemind.tower.shape." + TowerShape.byIndex(i).key()), button -> {
                        shape = index;
                        refresh();
                    }).bounds(left + 12 + i * (halfWidth + 4), rowY(1), halfWidth, 20).build());
        }
        int materialWidth = (WIDTH - 24 - 2 * 4) / 3;
        for (int i = 0; i < materialButtons.length; i++) {
            int index = i;
            materialButtons[i] = addRenderableWidget(Button.builder(materialLabel(i), button -> {
                // Each material is switched on or off; the last one cannot be switched off.
                int toggled = materials ^ (1 << index);
                if (toggled != 0) {
                    materials = toggled;
                }
                refresh();
            }).bounds(left + 12 + i * (materialWidth + 4), rowY(2), materialWidth, 20).build());
        }
        int heightWidth = (WIDTH - 24 - 3 * 4) / 4;
        for (int i = 0; i < heightButtons.length; i++) {
            int index = i;
            heightButtons[i] = addRenderableWidget(Button.builder(Component.literal(String.valueOf(TowerPlan.HEIGHTS[i])), button -> {
                heightIndex = index;
                refresh();
            }).bounds(left + 12 + i * (heightWidth + 4), rowY(3), heightWidth, 20).build());
        }
        wallsButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.walls"), button -> {
            walls = true;
            refresh();
        }).bounds(left + 12, rowY(4), halfWidth, 20).build());
        pillarsButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.pillars"), button -> {
            walls = false;
            refresh();
        }).bounds(left + 16 + halfWidth, rowY(4), halfWidth, 20).build());

        torchesButton = addRenderableWidget(Button.builder(torchesLabel(), button -> {
            torches = !torches;
            button.setMessage(torchesLabel());
        }).bounds(left + 12, rowY(4) + 24, WIDTH - 24, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.build"), button -> {
            PacketDistributor.sendToServer(new BuildTowerPayload(workers, pos, materials, TowerPlan.HEIGHTS[heightIndex],
                    BuildTowerPayload.pack(walls, direction, shape, torches)));
            onClose();
        }).bounds(left + 12, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.cancel"), button -> onClose())
                .bounds(left + 16 + (WIDTH - 28) / 2, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        refresh();
    }

    private Component torchesLabel() {
        return Component.translatable(torches ? "screen.projecthivemind.tower.torches_on" : "screen.projecthivemind.tower.torches_off");
    }

    /** A material's button text: its name, marked when it is picked. */
    private Component materialLabel(int index) {
        boolean picked = (materials & (1 << index)) != 0;
        return Component.literal(picked ? "[x] " : "[ ] ")
                .append(Component.translatable("screen.projecthivemind.tower.material." + TowerMaterial.byIndex(index).key()));
    }

    /** The chosen options are the buttons that cannot be pressed; a kind that needs more workers than selected cannot be chosen. */
    private void refresh() {
        for (int i = 0; i < directionButtons.length; i++) {
            directionButtons[i].active = i != direction;
        }
        for (int i = 0; i < shapeButtons.length; i++) {
            shapeButtons[i].active = i != shape && workers.size() >= TowerShape.byIndex(i).minWorkers();
        }
        for (int i = 0; i < materialButtons.length; i++) {
            materialButtons[i].setMessage(materialLabel(i));
        }
        for (int i = 0; i < heightButtons.length; i++) {
            heightButtons[i].active = i != heightIndex;
        }
        wallsButton.active = !walls;
        pillarsButton.active = walls;
    }

    /**
     * The panel and its text are drawn here, as part of the background. Screen#render draws the background itself
     * (and in this version that is a blur): drawing anything before calling it puts it under the blur.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        HiveStyle.panel(graphics, left, top, WIDTH, HEIGHT);

        graphics.drawString(font, title, left + 12, top + 12, HiveStyle.LABEL, false);
        String[] labels = {"direction", "shape", "material", "height", "style"};
        for (int row = 0; row < labels.length; row++) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.tower." + labels[row]), left + 12, rowY(row) - 10, 0xA0A0A0, false);
        }
        int[] counts = TowerPlan.counts(TowerShape.byIndex(shape), TowerDirection.byIndex(direction), TowerPlan.HEIGHTS[heightIndex], walls);
        Component needs = direction == TowerDirection.DOWN.ordinal()
                ? Component.translatable("screen.projecthivemind.tower.needs_dig", counts[0], counts[1], counts[2])
                : Component.translatable("screen.projecthivemind.tower.needs", counts[0], counts[1]);
        graphics.drawString(font, needs, left + 12, rowY(4) + 50, 0xE0E0E0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tower.note"), left + 12, rowY(4) + 62, 0x909090, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tower.workers"), left + 12, rowY(4) + 74, 0x909090, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
