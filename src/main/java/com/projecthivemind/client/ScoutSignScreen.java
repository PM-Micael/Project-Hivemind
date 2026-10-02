package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;

import com.projecthivemind.network.SignTextPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Writing on the sign a scout has put up: four lines. The vanilla sign editor needs a player next to the sign. */
public class ScoutSignScreen extends Screen {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 140;

    private final BlockPos pos;
    private final EditBox[] lines = new EditBox[4];

    public ScoutSignScreen(BlockPos pos) {
        super(Component.translatable("screen.projecthivemind.sign.title"));
        this.pos = pos.immutable();
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        for (int i = 0; i < lines.length; i++) {
            lines[i] = addRenderableWidget(new EditBox(font, left + 12, top + 28 + i * 20, WIDTH - 24, 16, Component.empty()));
            lines[i].setMaxLength(45);
        }
        setInitialFocus(lines[0]);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
            List<String> text = new ArrayList<>();
            for (EditBox line : lines) {
                text.add(line.getValue());
            }
            PacketDistributor.sendToServer(new SignTextPayload(pos, text));
            onClose();
        }).bounds(left + 12, top + HEIGHT - 28, WIDTH - 24, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        HiveStyle.panel(graphics, left, top, WIDTH, HEIGHT);
        graphics.drawString(font, title, left + 12, top + 10, HiveStyle.LABEL, false);
    }
}
