package com.projecthivemind;

import java.util.Map;

/**
 * What a hive is capable of at one level. New levels are new entries in {@link HiveLevels}, not new code.
 *
 * @param maxHealth       Hive Heart health in health points (2 points per heart)
 * @param storageSlots    size of the hive's shared inventory
 * @param craftingGrid    side length of the crafting grid in the hive menu (3 = 3x3)
 * @param infectionRadius blocks from the Heart in each horizontal direction that are infected (4 = a 9x9 area)
 * @param unitCaps        how many units of each kind the hive may have at once
 */
public record HiveLevel(int level, float maxHealth, int storageSlots, int craftingGrid, int infectionRadius,
                        Map<UnitKind, Integer> unitCaps) {
    public int cap(UnitKind kind) {
        return unitCaps.getOrDefault(kind, 0);
    }
}
