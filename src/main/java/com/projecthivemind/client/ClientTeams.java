package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.entity.HiveTeams;

/** How far around its scout each team keeps together, as last told by the server. */
public final class ClientTeams {
    private static List<Integer> radii = List.of();

    private ClientTeams() {
    }

    public static void update(List<Integer> newRadii) {
        radii = List.copyOf(newRadii);
    }

    /** How many teams the hive has right now. */
    public static int count() {
        return Math.max(1, radii.size());
    }

    public static int radius(int team) {
        return team >= 0 && team < radii.size() ? radii.get(team) : HiveTeams.DEFAULT_RADIUS;
    }

    public static void reset() {
        radii = List.of();
    }
}
