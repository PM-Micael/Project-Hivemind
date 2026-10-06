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
                    UnitKind.COLLECTOR, 1,
                    UnitKind.FEEDER, 1),
                    new HiveLevel.Quest(0, 5, 0, 0, null, 0, 0, false, 0, false, EvolveTask.CRAFTING_TABLE)),
            // Level 2: 20 hearts, 54 storage slots, a 15x15 hive area, 3 soldiers, 2 workers, 3 feeders.
            new HiveLevel(2, 40.0F, 54, 3, 7, 32, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 2,
                    UnitKind.SOLDIER, 3,
                    UnitKind.COLLECTOR, 1,
                    UnitKind.FEEDER, 3),
                    new HiveLevel.Quest(0, 0, 10, 24000, null, 0, 0, false, 0, false)),
            // Level 3: 30 hearts, 81 storage slots (scrolled), a 29x29 hive area, 3 workers, 5 soldiers and 2 collectors.
            new HiveLevel(3, 60.0F, 81, 3, 14, 32, Map.of(
                    UnitKind.SCOUT, 2,
                    UnitKind.WORKER, 3,
                    UnitKind.SOLDIER, 5,
                    UnitKind.COLLECTOR, 2,
                    UnitKind.FEEDER, 3),
                    new HiveLevel.Quest(0, 0, 0, 0, 0, 10, 9, false, 0, false)),
            // Level 4: 40 hearts, 108 storage slots (scrolled), a 61x61 hive area, 5 workers, 7 soldiers and 3 collectors. The other units stay as they were.
            new HiveLevel(4, 80.0F, 108, 3, 30, 32, Map.of(
                    UnitKind.SCOUT, 2,
                    UnitKind.WORKER, 5,
                    UnitKind.SOLDIER, 7,
                    UnitKind.COLLECTOR, 3,
                    UnitKind.FEEDER, 3),
                    new HiveLevel.Quest(0, 0, 0, 0, null, 0, 0, true, 3, false)),
            // Level 5: 50 hearts, 135 storage slots (27 more than level 4), 7 workers and 10 soldiers. Everything else is as it was at level 4.
            new HiveLevel(5, 100.0F, 135, 3, 30, 32, Map.of(
                    UnitKind.SCOUT, 2,
                    UnitKind.WORKER, 7,
                    UnitKind.SOLDIER, 10,
                    UnitKind.COLLECTOR, 3,
                    UnitKind.FEEDER, 3),
                    // Level 6 is reached by defeating the Ender Dragon (and nothing else).
                    new HiveLevel.Quest(0, 0, 0, 0, null, 0, 0, false, 0, true)),
            // Level 6: 60 hearts, 162 storage slots (27 more than level 5), 15 soldiers. Everything else is as it was at level 5 (more to come).
            new HiveLevel(6, 120.0F, 162, 3, 30, 32, Map.of(
                    UnitKind.SCOUT, 2,
                    UnitKind.WORKER, 7,
                    UnitKind.SOLDIER, 15,
                    UnitKind.COLLECTOR, 3,
                    UnitKind.FEEDER, 3),
                    null));


    /** The level at which scouts can place hive portals. */
    public static final int PORTAL_LEVEL = 2;

    /** The level at which the Evolve tab of the hive menu is there. */
    public static final int EVOLVE_LEVEL = 1;

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
