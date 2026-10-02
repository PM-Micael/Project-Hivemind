package com.projecthivemind.build;

/** The kinds of tower the player can order. */
public enum TowerShape {
    /**
     * 7 by 7, with two staircases winding up the inside half a turn apart, so there is a way up from either door. It
     * takes two workers: one to each staircase.
     */
    DOUBLE("double", 3, 2, 2, 1, new int[][] {
            {2, 4}, {1, 4}, {0, 4}, {0, 3}, {0, 2}, {0, 1}, {0, 0}, {1, 0},
            {2, 0}, {3, 0}, {4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}, {3, 4}}),
    /** 5 by 5, with a single staircase winding up the inside, one turn every 8 blocks. One worker can build it. */
    SPIRAL("spiral", 2, 1, 1, 2, new int[][] {
            {1, 2}, {0, 2}, {0, 1}, {0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}});

    private final String key;
    private final int radius;
    private final int staircases;
    private final int minWorkers;
    private final int startOffset;
    private final int[][] ring;

    /**
     * @param radius     blocks from the middle to the outside of the wall: 3 makes a 7 by 7, 2 makes a 5 by 5
     * @param staircases how many staircases wind up the inside
     * @param minWorkers the fewest workers that can be ordered to build it
     * @param startOffset how many cells round the ring from the cell inside the door the first step is. The cell inside
     *                   the door has to be left clear: a stair there, with its tall half beside the way in, stops anything
     *                   walking in. The first step is the next one that is entered straight on, from the cell before it.
     * @param ring       the cells round the inside edge, as (x, z) from the inside's corner, in the order a staircase
     *                   climbs them: one block higher for each
     */
    TowerShape(String key, int radius, int staircases, int minWorkers, int startOffset, int[][] ring) {
        this.key = key;
        this.radius = radius;
        this.staircases = staircases;
        this.minWorkers = minWorkers;
        this.startOffset = startOffset;
        this.ring = ring;
    }

    public String key() {
        return key;
    }

    public int radius() {
        return radius;
    }

    public int staircases() {
        return staircases;
    }

    /** The most workers a build uses: one to each staircase. Any others that were selected are left out. */
    public int maxWorkers() {
        return staircases;
    }

    public int minWorkers() {
        return minWorkers;
    }

    public int startOffset() {
        return startOffset;
    }

    public int[][] ring() {
        return ring;
    }

    /** The side of the inside, in blocks. */
    public int insideSize() {
        return 2 * (radius - 1) + 1;
    }

    public static TowerShape byIndex(int index) {
        TowerShape[] all = values();
        return all[Math.max(0, Math.min(index, all.length - 1))];
    }
}
