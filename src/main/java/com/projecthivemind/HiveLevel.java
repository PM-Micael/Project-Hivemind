package com.projecthivemind;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * What a hive is capable of at one level. New levels are new entries in {@link HiveLevels}, not new code.
 *
 * @param maxHealth        Hive Heart health in health points (2 points per heart)
 * @param storageSlots     size of the hive's shared inventory
 * @param craftingGrid     side length of the crafting grid in the hive menu (3 = 3x3)
 * @param infectionRadius  blocks from the Heart in each horizontal direction that are infected (4 = a 9x9 area). This
 *                         is also the hive area that collectors and soldiers work in.
 * @param sightRadius      how far, in blocks, every hive unit except scouts can see (see HiveSight). The Heart sees
 *                         one chunk per level instead, see {@link #heartSightRadius()}.
 * @param unitCaps         how many units of each kind the hive may have at once
 * @param quest            what the hive has to do to reach the next level, or null at the highest level
 */
public record HiveLevel(int level, float maxHealth, int storageSlots, int craftingGrid, int infectionRadius,
                        int sightRadius, Map<UnitKind, Integer> unitCaps, @Nullable Quest quest) {
    /**
     * The quest that levels a hive up. Each part is a total the hive has to reach, and a part that is 0 is not asked:
     * logs collected, chunks explored by its units, mobs its units killed, and ticks the hive has been alive for
     * (24000 is a whole day and night), the height one of its units has to get down to ({@code reachY}, null for none), and
     * coal and iron ingots collected, whether the Nether has been entered (by a unit or the camera), blaze rods collected, and whether the Ender
     * Dragon has been defeated (while the hive was in the End).
     */
    public record Quest(int logs, int chunks, int kills, int survivalTicks, @Nullable Integer reachY, int coal, int ironIngots,
                        boolean nether, int blazeRods, boolean dragon) {
    }

    /** How many hive portals the hive may have standing at once: none before level 3, one from then on. */
    public int maxPortals() {
        return level >= HiveLevels.PORTAL_LEVEL ? 1 : 0;
    }

    /** How many teams the hive has: one for the Heart, and one for each portal it may have. */
    public int teamCount() {
        return 1 + maxPortals();
    }

    public int cap(UnitKind kind) {
        return unitCaps.getOrDefault(kind, 0);
    }

    /** The Heart's own sight: one chunk (16 blocks) per hive level. */
    public int heartSightRadius() {
        return level * 16;
    }

    /** A scout's sight: two chunks (32 blocks) per hive level. Still being tuned. */
    public int scoutSightRadius() {
        return level * 2 * 16;
    }

    /** The sight radius for one kind of unit. */
    public int sightRadius(UnitKind kind) {
        return kind == UnitKind.SCOUT ? scoutSightRadius() : sightRadius;
    }
}
