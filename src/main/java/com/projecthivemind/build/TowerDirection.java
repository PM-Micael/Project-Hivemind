package com.projecthivemind.build;

/** Which way a build goes from the block it is ordered on. */
public enum TowerDirection {
    /** A tower that rises from the block. */
    UP("up"),
    /** A shaft dug down into the ground below the block, with the same walls and staircases, and a floor at the bottom. */
    DOWN("down");

    private final String key;

    TowerDirection(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static TowerDirection byIndex(int index) {
        TowerDirection[] all = values();
        return all[Math.max(0, Math.min(index, all.length - 1))];
    }
}
