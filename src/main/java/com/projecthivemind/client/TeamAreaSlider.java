package com.projecthivemind.client;

import java.util.function.IntConsumer;

import com.projecthivemind.entity.HiveTeams;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * The slider for how far around its scout a team keeps together: from {@link HiveTeams#MIN_RADIUS} to {@link HiveTeams#MAX_RADIUS}
 * blocks. The new size is reported when the mouse is let go, not at every step of the drag.
 */
public class TeamAreaSlider extends AbstractSliderButton {
    private final IntConsumer onChosen;

    public TeamAreaSlider(int x, int y, int width, int height, int radius, IntConsumer onChosen) {
        super(x, y, width, height, Component.empty(), (double) (radius - HiveTeams.MIN_RADIUS) / (HiveTeams.MAX_RADIUS - HiveTeams.MIN_RADIUS));
        this.onChosen = onChosen;
        updateMessage();
    }

    public int radius() {
        return HiveTeams.MIN_RADIUS + (int) Math.round(value * (HiveTeams.MAX_RADIUS - HiveTeams.MIN_RADIUS));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("screen.projecthivemind.team.area", radius()));
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
