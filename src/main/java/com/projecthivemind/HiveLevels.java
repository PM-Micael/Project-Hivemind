package com.projecthivemind;

import java.util.List;
import java.util.Map;

public final class HiveLevels {
    private static final List<HiveLevel> LEVELS = List.of(
            // Level 1 is reached by planting the Hive Heart.
            // Sight: 2 chunks (32 blocks) for units. The Heart sees 1 chunk per level, scouts 2 chunks per level.
            new HiveLevel(1, 20.0F, 27, 3, 4, 32, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 1,
                    UnitKind.SOLDIER, 1,
                    UnitKind.COLLECTOR, 1),
                    new HiveLevel.Quest(10, 5, 0, 0, null, 0, 0)),
            // Level 2: 20 hearts, 54 storage slots, a 15x15 hive area, 3 soldiers, 2 workers.
            new HiveLevel(2, 40.0F, 54, 3, 7, 32, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 2,
                    UnitKind.SOLDIER, 3,
                    UnitKind.COLLECTOR, 1),
                    new HiveLevel.Quest(0, 0, 10, 24000, null, 0, 0)),
            // Level 3: 30 hearts, 81 storage slots (scrolled), a 29x29 hive area, 3 workers, 5 soldiers and 2 collectors.
            new HiveLevel(3, 60.0F, 81, 3, 14, 32, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 3,
                    UnitKind.SOLDIER, 5,
                    UnitKind.COLLECTOR, 2),
                    new HiveLevel.Quest(0, 0, 0, 0, 0, 10, 24)),
            // Level 4: 40 hearts, 108 storage slots (scrolled), a 61x61 hive area, 7 workers and 7 soldiers. The other units stay as they were.
            new HiveLevel(4, 80.0F, 108, 3, 30, 32, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 7,
                    UnitKind.SOLDIER, 7,
                    UnitKind.COLLECTOR, 2),
                    null));

    /** The level at which the Heart has its own furnace. */
    public static final int FURNACE_LEVEL = 3;

    private HiveLevels() {
    }

    /** Definition for a level, clamped to the levels that exist. */
    public static HiveLevel get(int level) {
        return LEVELS.get(Math.max(0, Math.min(level, LEVELS.size()) - 1));
    }

    /** True if there is a level above this one. */
    public static boolean hasNext(int level) {
        return level < LEVELS.size();
    }

    public static int maxInfectionRadius() {
        return LEVELS.stream().mapToInt(HiveLevel::infectionRadius).max().orElse(0);
    }
}
