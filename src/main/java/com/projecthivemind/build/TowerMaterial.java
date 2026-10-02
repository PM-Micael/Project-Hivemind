package com.projecthivemind.build;

/** What a tower can be built from: the player picks one of these when ordering it. */
public enum TowerMaterial {
    /** Wooden planks, with the wooden stairs of the same kind of wood. */
    WOOD("wood"),
    /** Cobblestone, with cobblestone stairs. */
    COBBLESTONE("cobblestone"),
    /** Cobbled deepslate or deepslate, with cobbled deepslate stairs. */
    DEEPSLATE("deepslate");

    private final String key;

    TowerMaterial(String key) {
        this.key = key;
    }

    /** The end of the language key that names this material. */
    public String key() {
        return key;
    }

    public static TowerMaterial byIndex(int index) {
        TowerMaterial[] all = values();
        return all[Math.max(0, Math.min(index, all.length - 1))];
    }
}
