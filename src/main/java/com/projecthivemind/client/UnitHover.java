package com.projecthivemind.client;

import java.util.List;
import java.util.Locale;

import com.mojang.logging.LogUtils;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.SyncUnitsPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The name, health and job of the unit the cursor rests on, in a small box by the cursor: "Soldier 1", "Soldier 2" and so on,
 * the same names as on the unit pages of the hive menu, its health as hearts, and under that what it is doing (its job, or none).
 * Only in the RTS view, with no menu open.
 */
public final class UnitHover {
    private static final int PADDING = 4;
    private static boolean reportedError;

    private UnitHover() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        try {
            draw(graphics);
        } catch (RuntimeException exception) {
            // A fault here must not take the rest of the screen with it; say so once, so it can be found.
            if (!reportedError) {
                reportedError = true;
                LogUtils.getLogger().error("The unit hover box failed to draw", exception);
            }
        }
    }

    private static void draw(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!HiveCamera.controlling(minecraft) || ContextMenu.isOpen() || minecraft.options.hideGui) {
            return;
        }
        HiveUnit unit = HiveSelection.unitUnderCursor(minecraft);
        if (unit == null || !(unit instanceof LivingEntity living)) {
            return;
        }
        int id = ((Entity) unit).getId();
        Component name = nameOf(unit.kind(), id);
        float maxHealth = living.getMaxHealth();

        // What the unit is doing, for the kinds that have jobs: its job as the server last said it, or that it has none.
        Component job = null;
        if (unit.kind() == UnitKind.SOLDIER || unit.kind() == UnitKind.WORKER) {
            SyncUnitsPayload.Entry entry = ClientUnits.entry(id);
            Component what = entry != null && entry.hasJob() ? entry.job().copy().append(entry.paused()
                    ? Component.translatable("screen.projecthivemind.job.paused") : Component.empty())
                    : Component.translatable("screen.projecthivemind.job.none");
            job = Component.translatable("screen.projecthivemind.job.title", what);
        }

        int width = Math.max(Math.max(minecraft.font.width(name), HeartsBar.width(maxHealth)), job == null ? 0 : minecraft.font.width(job)) + PADDING * 2;
        int height = PADDING * 2 + 9 + 3 + HeartsBar.height(maxHealth) + (job == null ? 0 : 3 + 9);

        // By the cursor, like a tooltip, but kept on the screen.
        int[] cursor = ContextMenu.cursor(minecraft);
        int x = Math.min(cursor[0] + 12, graphics.guiWidth() - width - 2);
        int y = Math.max(2, Math.min(cursor[1] - 12, graphics.guiHeight() - height - 2));
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        HiveStyle.panel(graphics, x, y, width, height);
        graphics.drawString(minecraft.font, name, x + PADDING, y + PADDING, 0xFFFFFF, false);
        HeartsBar.draw(graphics, x + PADDING, y + PADDING + 9 + 3, living.getHealth(), maxHealth);
        if (job != null) {
            graphics.drawString(minecraft.font, job, x + PADDING, y + PADDING + 9 + 3 + HeartsBar.height(maxHealth) + 3, 0xA0A0A0, false);
        }
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
