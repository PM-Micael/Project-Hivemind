package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.projecthivemind.network.SyncEyesPayload;

/**
 * What the hive can see right now, as last told by the server: the living things in sight (anything not in this set is
 * hidden from the player while they are in the RTS view) and the eyes it sees with, which the terrain fog uses.
 * Plain data only.
 */
public final class ClientSight {
    private static Set<Integer> visible = new HashSet<>();
    private static List<SyncEyesPayload.EyePoint> eyes = List.of();
    private static int eyesVersion;

    private ClientSight() {
    }

    public static void update(List<Integer> visibleMobIds) {
        visible = new HashSet<>(visibleMobIds);
    }

    public static boolean isVisible(int entityId) {
        return visible.contains(entityId);
    }

    public static void updateEyes(List<SyncEyesPayload.EyePoint> newEyes) {
        eyes = List.copyOf(newEyes);
        eyesVersion++;
    }

    public static List<SyncEyesPayload.EyePoint> eyes() {
        return eyes;
    }

    /** Goes up every time the server sends new eyes. */
    public static int eyesVersion() {
        return eyesVersion;
    }

    public static void reset() {
        visible = new HashSet<>();
        eyes = List.of();
        eyesVersion++;
    }
}
