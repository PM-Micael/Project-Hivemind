package com.projecthivemind.client;

import com.projecthivemind.UnitKind;
import com.projecthivemind.network.SpawnUnitPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Replaces the vanilla inventory for a bodyless Hivemind player. */
public class HivemindScreen extends Screen {
    private static final int BUTTON_WIDTH = 160;
    private static final int BUTTON_HEIGHT = 20;

    private Button workerButton;
    private Button soldierButton;

    public HivemindScreen() {
        super(Component.translatable("screen.projecthivemind.hive.title"));
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = this.height / 2 - BUTTON_HEIGHT - 2;
        workerButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.hive.spawn_worker"),
                        button -> PacketDistributor.sendToServer(new SpawnUnitPayload(UnitKind.WORKER)))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        soldierButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.hive.spawn_soldier"),
                        button -> PacketDistributor.sendToServer(new SpawnUnitPayload(UnitKind.SOLDIER)))
                .bounds(x, y + BUTTON_HEIGHT + 4, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        updateButtons();
    }

    @Override
    public void tick() {
        // The server confirms spawns and deaths; keep the buttons in step with the latest sync.
        updateButtons();
    }

    private void updateButtons() {
        workerButton.active = !ClientState.hasWorker();
        soldierButton.active = !ClientState.hasSoldier();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFF);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Pressing the inventory key again closes the menu, like the vanilla inventory.
        if (Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
