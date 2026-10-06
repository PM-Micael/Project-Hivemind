package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the server last said about the hive's enchantments: those made available in its enchanting station, and those it could learn now. */
public final class ClientEnchants {
    private static Set<String> unlocked = new HashSet<>();
    private static Set<String> ready = new HashSet<>();

    private ClientEnchants() {
    }

    public static void update(List<String> unlockedKeys, List<String> readyKeys) {
        unlocked = new HashSet<>(unlockedKeys);
        ready = new HashSet<>(readyKeys);
    }

    public static Set<String> all() {
        return unlocked;
    }

    /** True if an enchanted book with exactly this level is in the hive's storage (and the level is not learnt yet). */
    public static boolean isReady(String key) {
        return ready.contains(key);
    }

    public static void clear() {
        unlocked = new HashSet<>();
        ready = new HashSet<>();
    }
}
