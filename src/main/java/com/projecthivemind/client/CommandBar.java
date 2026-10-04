package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.network.FocusTeamPayload;
import com.projecthivemind.network.SyncUnitsPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The command bar that replaces the survival hotbar in the RTS view. It has a slot for each team the hive has, and its slots use the game's
 * own hotbar keys, in order, so they follow whatever the player has bound: 1 is the first team (the Heart's), 2 the second (the first portal's)
 * and so on.
 *
 * <p>Pressing a team's key selects that team (and deselects everything else). Pressing it again within a second moves the camera to the team's scout, or to the
 * next unit of the team if it has none; pressing it a third time within that second deselects.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class CommandBar {
    private static final int SLOT_WIDTH = 72;
    private static final int SLOT_HEIGHT = 22;
    private static final int GAP = 4;
    private static final int BOTTOM_MARGIN = 1;
    private static final int BACKGROUND = 0xC0201414;
    private static final int BORDER_NONE = 0xFF3A2A2A;
    private static final int BORDER_EMPTY = 0xFF241818;
    private static final int BORDER_ALL = 0xFFFFFF55;

    private CommandBar() {
    }

    /** The units of a team that are out, as the server last told the client. */
    private static List<SyncUnitsPayload.Entry> members(int team) {
        return ClientUnits.all().stream().filter(entry -> entry.teamIndex() == team).toList();
    }

    private static int selectedCount(List<SyncUnitsPayload.Entry> members) {
        return (int) members.stream().filter(entry -> ClientSelection.isSelected(entry.entityId())).count();
    }

    /**
     * Runs before the game handles its own keys this tick. In the RTS view the number keys are the command bar's:
     * left to the game, they would open the spectator teleport menu. Keys with no team do nothing.
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !ClientState.hiveMode() || minecraft.screen != null) {
            return;
        }
        KeyMapping[] keys = minecraft.options.keyHotbarSlots;
        for (int i = 0; i < keys.length; i++) {
            while (keys[i].consumeClick()) {
                if (i < ClientTeams.count()) {
                    selectTeam(minecraft, i);
                }
            }
        }
    }

    /**
     * The team last picked with its key, the game tick of its first press, and how many presses there have been in that second. One press
     * selects the team, a second press within the second takes the camera to it, and a third within the same second deselects it.
     */
    private static int lastTeam = -1;
    private static long windowStart;
    private static int presses;
    private static final int WINDOW_TICKS = 20;

    private static void selectTeam(Minecraft minecraft, int team) {
        long now = minecraft.level.getGameTime();
        if (team == lastTeam && now - windowStart <= WINDOW_TICKS) {
            presses++;
        } else {
            lastTeam = team;
            windowStart = now;
            presses = 1;
        }
        if (presses >= 3) {
            // The third press in the same second: let the team go, and start over.
            lastTeam = -1;
            presses = 0;
            HiveSelection.expectUnits(Set.of(), 0);
            ClientSelection.retain(Set.of());
            return;
        }
        if (!selectMembers(minecraft, team)) {
            lastTeam = -1;
            presses = 0;
            return;
        }
        if (presses == 2) {
            PacketDistributor.sendToServer(new FocusTeamPayload(team));
        }
    }

    /**
     * Select the team and nothing else. While the team has a scout, the scout is the one unit selected: the rest follow it and act on their own.
     * A team with no scout (it died and left the team) has all its units selected, to be controlled as normal. False if the team has no one out.
     */
    private static boolean selectMembers(Minecraft minecraft, int team) {
        List<SyncUnitsPayload.Entry> members = members(team);
        if (members.isEmpty()) {
            minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.team_empty", team + 1), false);
            return false;
        }
        Set<Integer> ids = new HashSet<>();
        boolean hasScout = members.stream().anyMatch(entry -> entry.kind() == UnitKind.SCOUT.ordinal());
        for (SyncUnitsPayload.Entry entry : members) {
            if (!hasScout || entry.kind() == UnitKind.SCOUT.ordinal()) {
                ids.add(entry.entityId());
            }
        }
        HiveSelection.expectUnits(ids, minecraft.player.tickCount + 100);
        ClientSelection.retain(ids);
        ids.forEach(ClientSelection::select);
        return true;
    }

    /** Drawn as a GUI layer along the bottom of the screen. */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!ClientState.hiveMode()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int slots = Math.min(ClientTeams.count(), minecraft.options.keyHotbarSlots.length);
        int left = (graphics.guiWidth() - (slots * SLOT_WIDTH + (slots - 1) * GAP)) / 2;
        int top = graphics.guiHeight() - SLOT_HEIGHT - BOTTOM_MARGIN;

        for (int i = 0; i < slots; i++) {
            List<SyncUnitsPayload.Entry> members = members(i);
            int total = members.size();
            int selected = selectedCount(members);
            int x = left + i * (SLOT_WIDTH + GAP);
            boolean empty = total == 0;
            boolean all = !empty && selected == total;
            int border = all ? BORDER_ALL : empty ? BORDER_EMPTY : BORDER_NONE;
            int text = empty ? 0x707070 : 0xFFFFFF;

            graphics.fill(x - 1, top - 1, x + SLOT_WIDTH + 1, top + SLOT_HEIGHT + 1, border);
            graphics.fill(x, top, x + SLOT_WIDTH, top + SLOT_HEIGHT, BACKGROUND);

            String key = minecraft.options.keyHotbarSlots[i].getTranslatedKeyMessage().getString();
            graphics.drawString(minecraft.font, key + " " + Component.translatable("screen.projecthivemind.team.title", i + 1).getString(),
                    x + 4, top + 3, text, false);
            graphics.drawString(minecraft.font, selected + "/" + total, x + 4, top + 12, all ? 0xFFFF55 : 0xA0A0A0, false);
        }
    }
}
