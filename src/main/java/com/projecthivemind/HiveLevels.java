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
                    new HiveLevel.Quest(10, 5)),
            // Level 2: 20 hearts, 54 storage slots, a 15x15 hive area, up to 3 soldiers and 2 workers.
            new HiveLevel(2, 40.0F, 54, 3, 7, 32, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 2,
                    UnitKind.SOLDIER, 3,
                    UnitKind.COLLECTOR, 1),
                    null));

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
