package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The command bar that replaces the survival hotbar in the RTS view. Its slots use the game's own hotbar keys, in
 * order, so they follow whatever the player has bound: 1 toggles all Scouts, 2 all Soldiers, 3 all Workers.
 *
 * <p>Toggling selects every unit of that kind, or, if they are all selected already, deselects them all.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class CommandBar {
    /** One slot of the bar: the units it selects, and what to call them. */
    private record Group(UnitKind kind, String labelKey) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group(UnitKind.SCOUT, "command.projecthivemind.scouts"),
            new Group(UnitKind.SOLDIER, "command.projecthivemind.soldiers"),
            new Group(UnitKind.WORKER, "command.projecthivemind.workers"));

    private static final int SLOT_WIDTH = 72;
    private static final int SLOT_HEIGHT = 22;
    private static final int GAP = 4;
    private static final int BOTTOM_MARGIN = 1;
    private static final int BACKGROUND = 0xC0201414;
    private static final int BORDER_NONE = 0xFF3A2A2A;
    private static final int BORDER_EMPTY = 0xFF241818;
    private static final int BORDER_ALL = 0xFFFFFF55;

    /** How many of each group's units exist, and how many of those are selected. Refreshed every tick. */
    private static final int[] TOTAL = new int[GROUPS.size()];
    private static final int[] SELECTED = new int[GROUPS.size()];

    private CommandBar() {
    }

    /**
     * Runs before the game handles its own keys this tick. In the RTS view the number keys are the command bar's:
     * left to the game, they would open the spectator teleport menu. Keys with no command yet (4 to 9) do nothing.
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !ClientState.hiveMode()) {
            return;
        }
        refreshCounts(minecraft);
        if (minecraft.screen != null) {
            return;
        }
        KeyMapping[] keys = minecraft.options.keyHotbarSlots;
        for (int i = 0; i < keys.length; i++) {
            while (keys[i].consumeClick()) {
                if (i < GROUPS.size()) {
                    toggleGroup(minecraft, i);
                }
            }
        }
    }

    private static void refreshCounts(Minecraft minecraft) {
        for (int i = 0; i < GROUPS.size(); i++) {
            List<Integer> ids = ownUnits(minecraft, GROUPS.get(i).kind());
            TOTAL[i] = ids.size();
            SELECTED[i] = (int) ids.stream().filter(ClientSelection::isSelected).count();
        }
    }

    /** The entity ids of the player's own living units of one kind. */
    private static List<Integer> ownUnits(Minecraft minecraft, UnitKind kind) {
        List<Integer> ids = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof HiveUnit unit && unit.kind() == kind && entity.isAlive()
                    && minecraft.player.getUUID().equals(unit.ownerId())) {
                ids.add(entity.getId());
            }
        }
        return ids;
    }

    /** Select all of a kind of unit, or if they are all selected already, deselect them all. */
    private static void toggleGroup(Minecraft minecraft, int index) {
        Group group = GROUPS.get(index);
        // A collector is only ever selected alone: selecting these lets it go.
        for (int id : ownUnits(minecraft, UnitKind.COLLECTOR)) {
            ClientSelection.deselect(id);
        }
        List<Integer> ids = ownUnits(minecraft, group.kind());
        if (ids.isEmpty()) {
            minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.no_such_units",
                    Component.translatable(group.labelKey())), false);
            return;
        }
        boolean allSelected = ids.stream().allMatch(ClientSelection::isSelected);
        for (int id : ids) {
            if (allSelected) {
                ClientSelection.deselect(id);
            } else {
                ClientSelection.select(id);
            }
        }
        refreshCounts(minecraft);
    }

    /** Drawn as a GUI layer along the bottom of the screen. */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!ClientState.hiveMode()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int slots = GROUPS.size();
        int left = (graphics.guiWidth() - (slots * SLOT_WIDTH + (slots - 1) * GAP)) / 2;
        int top = graphics.guiHeight() - SLOT_HEIGHT - BOTTOM_MARGIN;

        for (int i = 0; i < slots; i++) {
            int x = left + i * (SLOT_WIDTH + GAP);
            boolean empty = TOTAL[i] == 0;
            boolean all = !empty && SELECTED[i] == TOTAL[i];
            int border = all ? BORDER_ALL : empty ? BORDER_EMPTY : BORDER_NONE;
            int text = empty ? 0x707070 : 0xFFFFFF;

            graphics.fill(x - 1, top - 1, x + SLOT_WIDTH + 1, top + SLOT_HEIGHT + 1, border);
            graphics.fill(x, top, x + SLOT_WIDTH, top + SLOT_HEIGHT, BACKGROUND);

            String key = minecraft.options.keyHotbarSlots[i].getTranslatedKeyMessage().getString();
            graphics.drawString(minecraft.font, key + " " + Component.translatable(GROUPS.get(i).labelKey()).getString(),
                    x + 4, top + 3, text, false);
            graphics.drawString(minecraft.font, SELECTED[i] + "/" + TOTAL[i], x + 4, top + 12, all ? 0xFFFF55 : 0xA0A0A0, false);
        }
    }
}
