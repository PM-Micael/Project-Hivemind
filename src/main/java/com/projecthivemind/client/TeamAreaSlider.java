package com.projecthivemind.client;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A slider for one of a team's areas, in whole blocks between a least and a most: how far around its scout the team keeps together, or how far
 * its soldiers go after hostile mobs. The new size is reported when the mouse is let go, not at every step of the drag.
 */
public class TeamAreaSlider extends AbstractSliderButton {
    private final IntConsumer onChosen;
    private final int min;
    private final int max;
    private final String labelKey;

    public TeamAreaSlider(int x, int y, int width, int height, int value, int min, int max, String labelKey, IntConsumer onChosen) {
        super(x, y, width, height, Component.empty(), (double) (Math.max(min, Math.min(max, value)) - min) / (max - min));
        this.onChosen = onChosen;
        this.min = min;
        this.max = max;
        this.labelKey = labelKey;
        updateMessage();
    }

    public int radius() {
        return min + (int) Math.round(value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable(labelKey, radius()));
    }

    @Override
    protected void applyValue() {
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        onChosen.accept(radius());
    }
}
