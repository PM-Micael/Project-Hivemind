package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How one soldier behaves when it has no orders and is not selected by the player. Each soldier has its own settings,
 * edited from its page of the hive menu.
 *
 * <p>Every option except {@code threats} has its own radius: how far around the soldier (a circle that moves with it) a
 * mob can be for that option to apply. The hive area is the fixed area around the Heart (the one whose edge shows as
 * particles); the two hive-area options need the mob in it as well as in range.
 *
 * @param allInHiveArea      attack every mob that is not part of a hive inside the hive area (and in range)
 * @param allInHiveRadius    how far, in blocks, that option reaches
 * @param hostileInHiveArea  attack hostile mobs inside the hive area (and in range)
 * @param hostileInHiveRadius how far that option reaches
 * @param allInUnitArea      attack every non-hive mob in range of the soldier
 * @param allInUnitRadius    how far that option reaches
 * @param hostileInUnitArea  attack hostile mobs in range of the soldier
 * @param hostileInUnitRadius how far that option reaches
 * @param stayInside         when not selected, always try to be inside the hive area: this wins over everything else
 * @param threats            attack any mob, anywhere, that has hurt the hive or its units or is trying to: the one
 *                           option with no radius
 */
public record SoldierBehavior(boolean allInHiveArea, int allInHiveRadius, boolean hostileInHiveArea, int hostileInHiveRadius,
                              boolean allInUnitArea, int allInUnitRadius, boolean hostileInUnitArea, int hostileInUnitRadius,
                              boolean threats, boolean stayInside) {
    public static final int MAX_RADIUS = 64;
    /** A hive defends itself by default: hostile mobs in its area, and anything that attacks it. */
    public static final SoldierBehavior DEFAULT = new SoldierBehavior(false, 8, true, 8, false, 8, false, 8, true, false);
    // The checkboxes as bits, for sending them in one number.
    private static final int ALL_IN_HIVE = 1;
    private static final int HOSTILE_IN_HIVE = 2;
    private static final int ALL_IN_UNIT = 4;
    private static final int HOSTILE_IN_UNIT = 8;
    private static final int THREATS = 16;
    private static final int STAY_INSIDE = 32;

    public SoldierBehavior {
        allInHiveRadius = Mth.clamp(allInHiveRadius, 0, MAX_RADIUS);
        hostileInHiveRadius = Mth.clamp(hostileInHiveRadius, 0, MAX_RADIUS);
        allInUnitRadius = Mth.clamp(allInUnitRadius, 0, MAX_RADIUS);
        hostileInUnitRadius = Mth.clamp(hostileInUnitRadius, 0, MAX_RADIUS);
    }

    /** The five checkboxes packed into one number. */
    public int flags() {
        return (allInHiveArea ? ALL_IN_HIVE : 0) | (hostileInHiveArea ? HOSTILE_IN_HIVE : 0)
                | (allInUnitArea ? ALL_IN_UNIT : 0) | (hostileInUnitArea ? HOSTILE_IN_UNIT : 0)
                | (threats ? THREATS : 0) | (stayInside ? STAY_INSIDE : 0);
    }

    /** The four radii, in the order of the options above (not counting threats). */
    public int[] radii() {
        return new int[] {allInHiveRadius, hostileInHiveRadius, allInUnitRadius, hostileInUnitRadius};
    }

    public static SoldierBehavior from(int flags, int[] radii) {
        return new SoldierBehavior((flags & ALL_IN_HIVE) != 0, radii[0], (flags & HOSTILE_IN_HIVE) != 0, radii[1],
                (flags & ALL_IN_UNIT) != 0, radii[2], (flags & HOSTILE_IN_UNIT) != 0, radii[3], (flags & THREATS) != 0, (flags & STAY_INSIDE) != 0);
    }

    /** True if any checkbox is ticked, i.e. an idle soldier has anything to look for at all. */
    public boolean any() {
        // Staying inside is not something to look for.
        return (flags() & ~STAY_INSIDE) != 0;
    }

    /** The furthest any ticked option reaches: how far a soldier has to look. */
    public int maxRadius() {
        int max = 0;
        if (allInHiveArea) {
            max = Math.max(max, allInHiveRadius);
        }
        if (hostileInHiveArea) {
            max = Math.max(max, hostileInHiveRadius);
        }
        if (allInUnitArea) {
            max = Math.max(max, allInUnitRadius);
        }
        if (hostileInUnitArea) {
            max = Math.max(max, hostileInUnitRadius);
        }
        return max;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putIntArray("Radii", radii());
        return tag;
    }

    public static SoldierBehavior load(CompoundTag tag) {
        if (!tag.contains("Flags")) {
            return DEFAULT;
        }
        int[] radii = tag.getIntArray("Radii");
        if (radii.length < 4) {
            // Saved before each option had its own: one radius served all of them.
            int shared = tag.contains("UnitArea") ? tag.getInt("UnitArea") : 8;
            radii = new int[] {shared, shared, shared, shared};
        }
        return from(tag.getInt("Flags"), radii);
    }
}
