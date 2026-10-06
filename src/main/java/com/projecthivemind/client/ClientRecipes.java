package com.projecthivemind.client;

import java.util.List;

/** What the server last said about the recipes the hive crafted last (their ids, the newest first). */
public final class ClientRecipes {
    private static List<String> recent = List.of();

    private ClientRecipes() {
    }

    public static void update(List<String> recipes) {
        recent = List.copyOf(recipes);
    }

    public static List<String> recent() {
        return recent;
    }

    public static void clear() {
        recent = List.of();
    }
}
