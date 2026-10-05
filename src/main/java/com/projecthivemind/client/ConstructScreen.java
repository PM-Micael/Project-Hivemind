package com.projecthivemind.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * What to construct: the one entry of the right-click menu ("Construct") leads here, to a list of everything the workers can build. Choosing one
 * opens the screen for how exactly it is to be done (see DigStaircaseScreen, BuildTowerScreen and BuildBridgeScreen). The workers and the block it
 * is on were picked before this opened. New kinds of construction are new entries of the list.
 */
public class ConstructScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int ROW = 30;

    /** One kind of construction: its name, what it is in a few words, and the screen it opens. */
    private record Choice(String key, java.util.function.Supplier<Screen> screen) {
    }

    private final List<Choice> choices;

    public ConstructScreen(List<Integer> workers, BlockPos pos) {
        super(Component.translatable("screen.projecthivemind.construct.title"));
        List<Integer> builders = List.copyOf(workers);
        BlockPos site = pos.immutable();
        this.choices = List.of(
                new Choice("staircase", () -> new DigStaircaseScreen(builders, site)),
                new Choice("tower", () -> new BuildTowerScreen(builders, site)),
                new Choice("bridge", () -> new BuildBridgeScreen(builders, site)),
                new Choice("tunnel", () -> new BuildTunnelScreen(builders, site)),
                new Choice("generator", () -> {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.projecthivemind.network.BuildGeneratorPayload(builders, site));
                    return null;
                }));
    }

    private int panelHeight() {
        return 52 + choices.size() * ROW + 12;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - panelHeight()) / 2;
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            Button button = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.construct." + choice.key()),
                    pressed -> minecraft.setScreen(choice.screen().get())).bounds(left + 12, top + 32 + i * ROW, WIDTH - 24, 22).build());
            button.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.construct." + choice.key() + ".about")));
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.cancel"), button -> onClose())
                .bounds(left + 12, top + 32 + choices.size() * ROW + 2, WIDTH - 24, 20).build());
    }

    /** The panel and its text are drawn here, as part of the background, so that the blur does not cover them. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = (height - panelHeight()) / 2;
        HiveStyle.panel(graphics, left, top, WIDTH, panelHeight());
        graphics.drawString(font, title, left + 12, top + 12, HiveStyle.LABEL, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
