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
 * materials), how tall or deep, then Build; it always has walls. The workers and the
 * block it is on were picked before this opened.
 */
public class BuildTowerScreen extends Screen {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 262;
    private static final int ROW = 32;

    private final List<Integer> workers;
    private final BlockPos pos;
    /** The construction block whose settings these are, when the screen is opened again from its menu (Options) rather than for a new tower. */
    @javax.annotation.Nullable
    private BlockPos editing;
    /** The Y the build goes to, as first shown (or Integer.MIN_VALUE for the default), and the Y of the block it stands on or is dug down from. */
    private int initialTarget = Integer.MIN_VALUE;
    private final int baseY;
    private int direction;
    private int shape;
    /** The materials picked, one bit each (see TowerSet#bit). At least one is always picked. */
    private int materials = TowerSet.bit(TowerMaterial.WOOD);
    /** A tower or shaft always has walls all round (the corner pillars are part of them): there is no longer a choice. */
    private final boolean walls = true;
    private boolean torches;
    private Button torchesButton;
    private final Button[] shapeButtons = new Button[TowerShape.values().length];
    private final Button[] materialButtons = new Button[TowerMaterial.values().length];
    private net.minecraft.client.gui.components.EditBox heightBox;

    public BuildTowerScreen(List<Integer> workers, BlockPos pos) {
        super(Component.translatable("screen.projecthivemind.tower.title"));
        this.workers = List.copyOf(workers);
        this.pos = pos.immutable();
        this.baseY = pos.getY();
        // The double tower needs two workers; with only one the single staircase is the choice.
        this.shape = this.workers.size() >= TowerShape.DOUBLE.minWorkers() ? TowerShape.DOUBLE.ordinal() : TowerShape.SPIRAL.ordinal();
    }

    /** The settings of a tower or shaft under construction, to change: what is shown is what it was ordered with. */
    public BuildTowerScreen(BlockPos construction, net.minecraft.nbt.CompoundTag config) {
        super(Component.translatable("screen.projecthivemind.tower.title"));
        this.workers = List.of();
        this.pos = construction.immutable();
        this.editing = construction.immutable();
        this.baseY = config.contains("BaseY") ? config.getInt("BaseY") : construction.getY();
        this.direction = config.getInt("Direction");
        this.shape = config.getInt("Shape");
        this.materials = config.getInt("Materials") != 0 ? config.getInt("Materials") : this.materials;
        this.torches = config.getBoolean("Torches");
        this.initialTarget = direction == TowerDirection.UP.ordinal() ? baseY + config.getInt("Height") : baseY - config.getInt("Height");
    }

    private int rowY(int row) {
        return (height - HEIGHT) / 2 + 34 + row * ROW;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        int halfWidth = (WIDTH - 24 - 4) / 2;

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
        // The height (or depth, for a shaft) is typed in: any whole number from the least to the most.
        heightBox = addRenderableWidget(new net.minecraft.client.gui.components.EditBox(font, left + 12, rowY(3), 60, 20,
                Component.translatable("screen.projecthivemind.tower.height")));
        heightBox.setFilter(text -> text.matches("-?\\d{0,5}"));
        heightBox.setValue(String.valueOf(initialTarget != Integer.MIN_VALUE ? Math.max(minY(), Math.min(maxY(), initialTarget)) : Math.max(minY(), Math.min(maxY(), baseY + 16))));

        torchesButton = addRenderableWidget(Button.builder(torchesLabel(), button -> {
            torches = !torches;
            button.setMessage(torchesLabel());
        }).bounds(left + 12, rowY(4), WIDTH - 24, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(editing != null ? "screen.projecthivemind.construction.save" : "screen.projecthivemind.tower.build"), button -> {
            if (editing != null) {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                tag.putInt("Shape", shape);
                tag.putInt("Direction", currentDirection());
                tag.putInt("Height", buildHeight());
                tag.putBoolean("Walls", walls);
                tag.putBoolean("Torches", torches);
                tag.putInt("Materials", materials);
                PacketDistributor.sendToServer(new com.projecthivemind.network.UpdateConstructionPayload(editing, tag));
            } else {
                PacketDistributor.sendToServer(new BuildTowerPayload(workers, pos, materials, buildHeight(),
                        BuildTowerPayload.pack(walls, currentDirection(), shape, torches)));
            }
            onClose();
        }).bounds(left + 12, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.cancel"), button -> onClose())
                .bounds(left + 16 + (WIDTH - 28) / 2, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        refresh();
    }

    private Component torchesLabel() {
        return Component.translatable(torches ? "screen.projecthivemind.tower.torches_on" : "screen.projecthivemind.tower.torches_off");
    }

    /** The lowest Y a build can go to: the bottom of the world. */
    private int minY() {
        return minecraft != null && minecraft.level != null ? minecraft.level.getMinBuildHeight() : -64;
    }

    /** The highest Y a build can go to: the top of the world, less the room a tower needs above it. */
    private int maxY() {
        return (minecraft != null && minecraft.level != null ? minecraft.level.getMaxBuildHeight() : 320) - 3;
    }

    /** The Y typed, kept between the bottom and the top of the world (and a little above the block if it is empty). */
    private int targetY() {
        try {
            return Math.max(minY(), Math.min(maxY(), Integer.parseInt(heightBox.getValue())));
        } catch (NumberFormatException exception) {
            return Math.max(minY(), Math.min(maxY(), baseY + TowerPlan.MIN_HEIGHT));
        }
    }

    /** Up (a tower) if the Y is above the block, down (a shaft) if it is below it. */
    private int currentDirection() {
        return targetY() >= baseY ? TowerDirection.UP.ordinal() : TowerDirection.DOWN.ordinal();
    }

    /** How far up or down that is, in blocks: never less than the least a build can have. */
    private int buildHeight() {
        return Math.max(TowerPlan.MIN_HEIGHT, Math.abs(targetY() - baseY));
    }

    /** A material's button text: its name, marked when it is picked. */
    private Component materialLabel(int index) {
        boolean picked = (materials & (1 << index)) != 0;
        return Component.literal(picked ? "[x] " : "[ ] ")
                .append(Component.translatable("screen.projecthivemind.tower.material." + TowerMaterial.byIndex(index).key()));
    }

    /** The chosen options are the buttons that cannot be pressed; a kind that needs more workers than selected cannot be chosen. */
    private void refresh() {
        for (int i = 0; i < shapeButtons.length; i++) {
            // For a new tower the kind that needs more workers than are selected cannot be chosen; settings being changed can be any (the workers can be added).
            shapeButtons[i].active = i != shape && (editing != null || workers.size() >= TowerShape.byIndex(i).minWorkers());
        }
        for (int i = 0; i < materialButtons.length; i++) {
            materialButtons[i].setMessage(materialLabel(i));
        }
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
        String[] labels = {"direction", "shape", "material", "height"};
        for (int row = 0; row < labels.length; row++) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.tower." + labels[row]), left + 12, rowY(row) - 10, 0xA0A0A0, false);
        }
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tower.height_range", minY(), maxY()), left + 80, rowY(3) + 6, 0x909090, false);
        // Up or down follows from the Y that is typed: a tower above the block, a shaft below it.
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tower.direction." + TowerDirection.byIndex(currentDirection()).key()), left + 12, rowY(0) + 6, 0xE0E0E0, false);
        int[] counts = TowerPlan.counts(TowerShape.byIndex(shape), TowerDirection.byIndex(currentDirection()), buildHeight(), walls);
        Component needs = currentDirection() == TowerDirection.DOWN.ordinal()
                ? Component.translatable("screen.projecthivemind.tower.needs_dig", counts[0], counts[1], counts[2])
                : Component.translatable("screen.projecthivemind.tower.needs", counts[0], counts[1]);
        graphics.drawString(font, needs, left + 12, rowY(4) + 26, 0xE0E0E0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tower.note"), left + 12, rowY(4) + 38, 0x909090, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tower.workers"), left + 12, rowY(4) + 50, 0x909090, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
