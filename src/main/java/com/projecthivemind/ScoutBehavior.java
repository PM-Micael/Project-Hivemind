package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How one scout behaves. Each scout has its own settings, edited from its page of the hive menu. Each option has its own
 * radius.
 *
 * @param collectItems   pick up dropped items the way a player would, into the hive's inventory. A selected scout picks
 *                       up whatever it walks over; one that is not selected also walks to dropped items
 * @param collectRadius  how far around itself an idle, unselected scout looks for items, in blocks
 * @param fleeHostiles   when not selected, run away from hostile mobs. This takes priority over collecting items
 * @param fleeRadius     how close a hostile mob has to be before the scout runs, in blocks
 * @param stayInside     when not selected, always try to be inside the hive area: this wins over everything else
 */
public record ScoutBehavior(boolean collectItems, int collectRadius, boolean fleeHostiles, int fleeRadius, boolean stayInside) {
    public static final int MAX_RADIUS = 64;
    public static final int DEFAULT_RADIUS = 32;

    /** Scouts do nothing on their own until the player turns something on. */
    public static final ScoutBehavior DEFAULT = new ScoutBehavior(false, DEFAULT_RADIUS, false, DEFAULT_RADIUS, false);

    private static final int COLLECT_ITEMS = 1;
    private static final int FLEE_HOSTILES = 2;
    private static final int STAY_INSIDE = 4;

    public ScoutBehavior {
        collectRadius = Mth.clamp(collectRadius, 0, MAX_RADIUS);
        fleeRadius = Mth.clamp(fleeRadius, 0, MAX_RADIUS);
    }

    /** The two checkboxes packed into one number. */
    public int flags() {
        return (collectItems ? COLLECT_ITEMS : 0) | (fleeHostiles ? FLEE_HOSTILES : 0) | (stayInside ? STAY_INSIDE : 0);
    }

    public int[] radii() {
        return new int[] {collectRadius, fleeRadius};
    }

    public static ScoutBehavior from(int flags, int[] radii) {
        return new ScoutBehavior((flags & COLLECT_ITEMS) != 0, radii[0], (flags & FLEE_HOSTILES) != 0, radii[1], (flags & STAY_INSIDE) != 0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putIntArray("Radii", radii());
        return tag;
    }

    public static ScoutBehavior load(CompoundTag tag) {
        if (!tag.contains("Flags")) {
            return DEFAULT;
        }
        int[] radii = tag.getIntArray("Radii");
        if (radii.length < 2) {
            int shared = tag.contains("UnitArea") ? tag.getInt("UnitArea") : DEFAULT_RADIUS;
            radii = new int[] {shared, shared};
        }
        return from(tag.getInt("Flags"), radii);
    }
}
