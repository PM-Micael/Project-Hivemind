package com.projecthivemind.client;

import com.projecthivemind.network.ChooseModePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Shown the first time a player enters a world with the mod. Cannot be dismissed without choosing. */
public class ChooseModeScreen extends Screen {
    private static final int BUTTON_WIDTH = 160;
    private static final int BUTTON_HEIGHT = 20;

    public ChooseModeScreen() {
        super(Component.translatable("screen.projecthivemind.choose_mode.title"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = this.height / 2 - BUTTON_HEIGHT - 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.choose_mode.steve"), button -> choose(false))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.choose_mode.hivemind"), button -> choose(true))
                .bounds(x, y + BUTTON_HEIGHT + 4, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void choose(boolean hivemind) {
        PacketDistributor.sendToServer(new ChooseModePayload(hivemind));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable("screen.projecthivemind.choose_mode.subtitle"),
                this.width / 2, this.height / 2 - 36, 0xA0A0A0);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
