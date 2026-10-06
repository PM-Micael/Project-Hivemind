package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The enchantments (their ids) the hive has made available in its enchanting station, as the server last said. */
public final class ClientEnchants {
    private static Set<String> unlocked = new HashSet<>();

    private ClientEnchants() {
    }

    public static void update(List<String> ids) {
        unlocked = new HashSet<>(ids);
    }

    public static boolean isUnlocked(String id) {
        return unlocked.contains(id);
    }

    public static Set<String> all() {
        return unlocked;
    }

    public static void clear() {
        unlocked = new HashSet<>();
    }
}
