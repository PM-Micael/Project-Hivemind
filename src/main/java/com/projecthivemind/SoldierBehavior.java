package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How the hive's soldiers behave when they have no orders and are not selected by the player. One set of settings
 * for the whole hive, saved with its Heart and edited on the menu's Behavior tab.
 *
 * <p>Two areas matter. The hive area is the fixed area around the Heart (the same one the creep marks out). The unit
 * area is a circle of {@code unitAreaRadius} blocks around each soldier, which moves with it.
 *
 * <p>The unit area limits every option except {@code threats}: a soldier only reacts to a mob inside its own range.
 * The hive-area options need the mob in the hive area as well as in range.
 *
 * @param allInHiveArea     attack every mob that is not part of a hive inside the hive area (and in range)
 * @param hostileInHiveArea attack hostile mobs inside the hive area (and in range)
 * @param unitAreaRadius    how far around each soldier its own area reaches, in blocks
 * @param allInUnitArea     attack every non-hive mob inside the soldier's own area
 * @param hostileInUnitArea attack hostile mobs inside the soldier's own area
 * @param threats           attack any mob, anywhere, that has hurt the hive or its units or is trying to: the one
 *                          option that ignores the unit area
 */
public record SoldierBehavior(boolean allInHiveArea, boolean hostileInHiveArea, int unitAreaRadius,
                              boolean allInUnitArea, boolean hostileInUnitArea, boolean threats) {
    public static final int MAX_UNIT_AREA = 64;

    /** A hive defends itself by default: hostile mobs in its area, and anything that attacks it. */
    public static final SoldierBehavior DEFAULT = new SoldierBehavior(false, true, 8, false, false, true);

    // The checkboxes as bits, for sending them in one number.
    private static final int ALL_IN_HIVE = 1;
    private static final int HOSTILE_IN_HIVE = 2;
    private static final int ALL_IN_UNIT = 4;
    private static final int HOSTILE_IN_UNIT = 8;
    private static final int THREATS = 16;

    public SoldierBehavior {
        unitAreaRadius = Mth.clamp(unitAreaRadius, 0, MAX_UNIT_AREA);
    }

    /** The five checkboxes packed into one number. */
    public int flags() {
        return (allInHiveArea ? ALL_IN_HIVE : 0) | (hostileInHiveArea ? HOSTILE_IN_HIVE : 0)
                | (allInUnitArea ? ALL_IN_UNIT : 0) | (hostileInUnitArea ? HOSTILE_IN_UNIT : 0)
                | (threats ? THREATS : 0);
    }

    public static SoldierBehavior fromFlags(int flags, int unitAreaRadius) {
        return new SoldierBehavior((flags & ALL_IN_HIVE) != 0, (flags & HOSTILE_IN_HIVE) != 0, unitAreaRadius,
                (flags & ALL_IN_UNIT) != 0, (flags & HOSTILE_IN_UNIT) != 0, (flags & THREATS) != 0);
    }

    /** True if any checkbox is ticked, i.e. idle soldiers have anything to look for at all. */
    public boolean any() {
        return flags() != 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putInt("UnitArea", unitAreaRadius);
        return tag;
    }

    public static SoldierBehavior load(CompoundTag tag) {
        if (!tag.contains("Flags")) {
            return DEFAULT;
        }
        return fromFlags(tag.getInt("Flags"), tag.getInt("UnitArea"));
    }
}
