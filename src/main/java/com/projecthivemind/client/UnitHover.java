package com.projecthivemind.client;

import java.util.List;
import java.util.Locale;

import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The name and health of the unit the cursor rests on, in a small box by the cursor: "Soldier 1", "Soldier 2" and so on,
 * the same names as on the unit pages of the hive menu, and its health as hearts. Only in the RTS view, with no menu
 * open.
 */
public final class UnitHover {
    private static final int PADDING = 4;

    private UnitHover() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!HiveCamera.controlling(minecraft) || ContextMenu.isOpen() || minecraft.options.hideGui) {
            return;
        }
        HiveUnit unit = HiveSelection.unitUnderCursor(minecraft);
        if (unit == null || !(unit instanceof LivingEntity living)) {
            return;
        }
        Component name = nameOf(unit.kind(), ((Entity) unit).getId());
        float maxHealth = living.getMaxHealth();
        int width = Math.max(minecraft.font.width(name), HeartsBar.width(maxHealth)) + PADDING * 2;
        int height = PADDING * 2 + 9 + 3 + HeartsBar.height(maxHealth);

        // By the cursor, like a tooltip, but kept on the screen.
        int[] cursor = ContextMenu.cursor(minecraft);
        int x = Math.min(cursor[0] + 12, graphics.guiWidth() - width - 2);
        int y = Math.max(2, Math.min(cursor[1] - 12, graphics.guiHeight() - height - 2));
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        HiveStyle.panel(graphics, x, y, width, height);
        graphics.drawString(minecraft.font, name, x + PADDING, y + PADDING, 0xFFFFFF, false);
        HeartsBar.draw(graphics, x + PADDING, y + PADDING + 9 + 3, living.getHealth(), maxHealth);
        graphics.pose().popPose();
    }

    /** "Soldier 2": the kind, and the unit's place in the list of that kind as the server last sent it. */
    public static Component nameOf(UnitKind kind, int entityId) {
        Component kindName = Component.translatable("unit.projecthivemind." + kind.name().toLowerCase(Locale.ROOT));
        List<Integer> ofKind = ClientUnits.ofKind(kind);
        int index = ofKind.indexOf(entityId);
        return index < 0 ? kindName : Component.translatable("screen.projecthivemind.unit.numbered", kindName, index + 1);
    }
}
