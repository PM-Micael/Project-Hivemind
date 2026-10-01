package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The mobs the hive can see right now, as last told by the server. Anything not in this set is hidden from the player
 * while they are in the RTS view. Plain data only.
 */
public final class ClientSight {
    private static Set<Integer> visible = new HashSet<>();

    private ClientSight() {
    }

    public static void update(List<Integer> visibleMobIds) {
        visible = new HashSet<>(visibleMobIds);
    }

    public static boolean isVisible(int entityId) {
        return visible.contains(entityId);
    }

    public static void reset() {
        visible = new HashSet<>();
    }
}
