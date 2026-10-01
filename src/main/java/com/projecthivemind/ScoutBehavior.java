package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How the hive's scouts behave. One set of settings for the whole hive, saved with its Heart and edited on the menu's
 * Behavior tab.
 *
 * @param unitAreaRadius how far around itself a scout looks for dropped items, and how close a hostile mob has to be
 *                       before it runs, in blocks
 * @param collectItems   pick up dropped items the way a player would, into the hive's inventory. A selected scout picks
 *                       up whatever it walks over; one that is not selected also walks to dropped items.
 * @param fleeHostiles   when not selected, run away from hostile mobs. This takes priority over collecting items.
 */
public record ScoutBehavior(int unitAreaRadius, boolean collectItems, boolean fleeHostiles) {
    public static final int MAX_UNIT_AREA = 64;
    public static final int DEFAULT_UNIT_AREA = 32;

    /** Scouts do nothing on their own until the player turns something on. */
    public static final ScoutBehavior DEFAULT = new ScoutBehavior(DEFAULT_UNIT_AREA, false, false);

    private static final int COLLECT_ITEMS = 1;
    private static final int FLEE_HOSTILES = 2;

    public ScoutBehavior {
        unitAreaRadius = Mth.clamp(unitAreaRadius, 0, MAX_UNIT_AREA);
    }

    /** The two checkboxes packed into one number. */
    public int flags() {
        return (collectItems ? COLLECT_ITEMS : 0) | (fleeHostiles ? FLEE_HOSTILES : 0);
    }

    public static ScoutBehavior fromFlags(int flags, int unitAreaRadius) {
        return new ScoutBehavior(unitAreaRadius, (flags & COLLECT_ITEMS) != 0, (flags & FLEE_HOSTILES) != 0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putInt("UnitArea", unitAreaRadius);
        return tag;
    }

    public static ScoutBehavior load(CompoundTag tag) {
        if (!tag.contains("Flags")) {
            return DEFAULT;
        }
        // Hives saved before the radius existed keep the distance scouts always used.
        return fromFlags(tag.getInt("Flags"), tag.contains("UnitArea") ? tag.getInt("UnitArea") : DEFAULT_UNIT_AREA);
    }
}
