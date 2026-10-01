package com.projecthivemind.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * The local player's unit selection and the outline colours that follow from it. Holds plain data only (no
 * Minecraft client classes), so the unit entities, which are common code, can ask it how to draw themselves.
 */
public final class ClientSelection {
    /** Outline colours as RGB. White for a unit, yellow for a selected one. */
    private static final int WHITE = 0xFFFFFF;
    private static final int YELLOW = 0xFFFF55;

    private static final Set<Integer> SELECTED = new HashSet<>();
    @Nullable
    private static UUID localPlayer;

    private ClientSelection() {
    }

    public static void setLocalPlayer(@Nullable UUID id) {
        localPlayer = id;
    }

    /** Units glow, outlined through walls, for their owner only. */
    public static boolean shouldGlow(@Nullable UUID owner) {
        return owner != null && owner.equals(localPlayer);
    }

    public static int outlineColor(int entityId) {
        return SELECTED.contains(entityId) ? YELLOW : WHITE;
    }

    public static boolean isSelected(int entityId) {
        return SELECTED.contains(entityId);
    }

    /** Select an unselected unit, or deselect a selected one. */
    public static void toggle(int entityId) {
        if (!SELECTED.remove(entityId)) {
            SELECTED.add(entityId);
        }
    }

    public static void select(int entityId) {
        SELECTED.add(entityId);
    }

    public static void deselect(int entityId) {
        SELECTED.remove(entityId);
    }

    public static Set<Integer> selected() {
        return Set.copyOf(SELECTED);
    }

    public static void retain(Set<Integer> stillValid) {
        SELECTED.retainAll(stillValid);
    }

    public static void reset() {
        SELECTED.clear();
        localPlayer = null;
    }
}
