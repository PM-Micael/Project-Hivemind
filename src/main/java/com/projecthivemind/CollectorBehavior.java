package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How the hive's collectors behave. They always collect dropped items in the hive area; the one thing to set is how
 * much further than that they may reach. One setting for the whole hive, saved with its Heart and edited on the
 * menu's Behavior tab.
 *
 * @param extraRange how many blocks beyond the edge of the hive area a collector may go to fetch an item
 */
public record CollectorBehavior(int extraRange) {
    public static final int MAX_EXTRA_RANGE = 32;

    /** Out of the box, collectors stay inside the hive area. */
    public static final CollectorBehavior DEFAULT = new CollectorBehavior(0);

    public CollectorBehavior {
        extraRange = Mth.clamp(extraRange, 0, MAX_EXTRA_RANGE);
    }

    public int[] radii() {
        return new int[] {extraRange};
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ExtraRange", extraRange);
        return tag;
    }

    public static CollectorBehavior load(CompoundTag tag) {
        return tag.contains("ExtraRange") ? new CollectorBehavior(tag.getInt("ExtraRange")) : DEFAULT;
    }
}
