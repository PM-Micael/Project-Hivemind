package com.projecthivemind;

import java.util.List;
import java.util.Map;

public final class HiveLevels {
    private static final List<HiveLevel> LEVELS = List.of(
            // Level 1 is reached by planting the Hive Heart.
            // Sight: 2 chunks (32 blocks) for everything but scouts, which see 12 chunks (192 blocks).
            new HiveLevel(1, 20.0F, 27, 3, 4, 32, 192, Map.of(
                    UnitKind.SCOUT, 1,
                    UnitKind.WORKER, 1,
                    UnitKind.SOLDIER, 1,
                    UnitKind.COLLECTOR, 1)));

    private HiveLevels() {
    }

    /** Definition for a level, clamped to the levels that exist. */
    public static HiveLevel get(int level) {
        return LEVELS.get(Math.max(0, Math.min(level, LEVELS.size()) - 1));
    }

    public static int maxInfectionRadius() {
        return LEVELS.stream().mapToInt(HiveLevel::infectionRadius).max().orElse(0);
    }
}
