package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;

/**
 * How the hive's scouts behave. One set of settings for the whole hive, saved with its Heart and edited on the menu's
 * Behavior tab.
 *
 * @param collectItems pick up dropped items the way a player would, into the hive's inventory. A selected scout picks
 *                     up whatever it walks over; one that is not selected also walks to dropped items.
 * @param fleeHostiles when not selected, run away from hostile mobs. This takes priority over collecting items.
 */
public record ScoutBehavior(boolean collectItems, boolean fleeHostiles) {
    /** Scouts do nothing on their own until the player turns something on. */
    public static final ScoutBehavior DEFAULT = new ScoutBehavior(false, false);

    private static final int COLLECT_ITEMS = 1;
    private static final int FLEE_HOSTILES = 2;

    /** The two checkboxes packed into one number. */
    public int flags() {
        return (collectItems ? COLLECT_ITEMS : 0) | (fleeHostiles ? FLEE_HOSTILES : 0);
    }

    public static ScoutBehavior fromFlags(int flags) {
        return new ScoutBehavior((flags & COLLECT_ITEMS) != 0, (flags & FLEE_HOSTILES) != 0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        return tag;
    }

    public static ScoutBehavior load(CompoundTag tag) {
        return tag.contains("Flags") ? fromFlags(tag.getInt("Flags")) : DEFAULT;
    }
}
