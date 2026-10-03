package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.network.DigStaircasePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The order for a classic staircase dug down from a block: which way it goes and the height it stops at. The workers and the block
 * were picked before this opened. The staircase starts out going the way the camera faces.
 */
public class DigStaircaseScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int HEIGHT = 176;
    private static final Direction[] DIRECTIONS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final List<Integer> workers;
    private final BlockPos pos;
    private int direction;
    private final Button[] directionButtons = new Button[DIRECTIONS.length];
    private EditBox stopBox;
    private net.minecraft.client.gui.components.Checkbox torchBox;

    public DigStaircaseScreen(List<Integer> workers, BlockPos pos) {
        super(Component.translatable("screen.projecthivemind.stairs.title"));
        this.workers = List.copyOf(workers);
        this.pos = pos.immutable();
        Direction facing = net.minecraft.client.Minecraft.getInstance().player == null ? Direction.NORTH
                : net.minecraft.client.Minecraft.getInstance().player.getDirection();
        for (int i = 0; i < DIRECTIONS.length; i++) {
            if (DIRECTIONS[i] == facing) {
                direction = i;
            }
        }
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        int cell = (WIDTH - 24 - 3 * 4) / 4;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            int index = i;
            directionButtons[i] = addRenderableWidget(Button.builder(
                    Component.translatable("screen.projecthivemind.stairs.dir." + DIRECTIONS[i].getName()), button -> {
                        direction = index;
                        refresh();
                    }).bounds(left + 12 + i * (cell + 4), top + 44, cell, 20).build());
        }
        stopBox = addRenderableWidget(new EditBox(font, left + 12, top + 84, 60, 18, Component.translatable("screen.projecthivemind.stairs.stop")));
        stopBox.setFilter(text -> text.matches("-?\\d{0,4}"));
        stopBox.setValue(String.valueOf(pos.getY() - 8));
        torchBox = addRenderableWidget(net.minecraft.client.gui.components.Checkbox.builder(Component.translatable("screen.projecthivemind.torches"), font)
                .pos(left + 12, top + 106).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.stairs.dig"), button -> {
            PacketDistributor.sendToServer(new DigStaircasePayload(workers, pos, DIRECTIONS[direction].get2DDataValue(), stopY(), torchBox.selected(), false));
            onClose();
        }).bounds(left + 12, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.cancel"), button -> onClose())
                .bounds(left + 16 + (WIDTH - 28) / 2, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        refresh();
    }

    /** The height typed, or the block's own height if it is not a number (the server keeps it in range). */
    private int stopY() {
        try {
            return Integer.parseInt(stopBox.getValue());
        } catch (NumberFormatException exception) {
            return pos.getY();
        }
    }

    /** The chosen direction is the button that cannot be pressed. */
    private void refresh() {
        for (int i = 0; i < directionButtons.length; i++) {
            directionButtons[i].active = i != direction;
        }
    }

    /** The panel and its text are drawn here, as part of the background, so that the blur does not cover them. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        HiveStyle.panel(graphics, left, top, WIDTH, HEIGHT);
        graphics.drawString(font, title, left + 12, top + 12, HiveStyle.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.stairs.direction"), left + 12, top + 32, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.stairs.stop"), left + 12, top + 72, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.stairs.note"), left + 12, top + 130, 0x909090, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
