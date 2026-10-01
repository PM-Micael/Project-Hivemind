package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How the hive's workers behave when they have no orders and are not selected by the player. One set of settings for
 * the whole hive, saved with its Heart and edited on the menu's Behavior tab.
 *
 * <p>A worker only goes after blocks that are inside its own range, that the hive can see (see HiveSight), and that
 * the hive's tools can harvest.
 *
 * @param unitAreaRadius how far around itself a worker looks for work, in blocks
 * @param mineOre        mine ore blocks
 * @param chopLogs       chop the logs of natural trees
 * @param digThrough     if a block it wants cannot be reached on foot, dig toward it in a straight line
 */
public record WorkerBehavior(int unitAreaRadius, boolean mineOre, boolean chopLogs, boolean digThrough) {
    /** Kept lower than the soldiers' limit because workers scan every block in their range for work. */
    public static final int MAX_UNIT_AREA = 32;

    /** Workers do nothing on their own until the player turns something on. */
    public static final WorkerBehavior DEFAULT = new WorkerBehavior(8, false, false, false);

    private static final int MINE_ORE = 1;
    private static final int CHOP_LOGS = 2;
    private static final int DIG_THROUGH = 4;

    public WorkerBehavior {
        unitAreaRadius = Mth.clamp(unitAreaRadius, 0, MAX_UNIT_AREA);
    }

    /** The three checkboxes packed into one number. */
    public int flags() {
        return (mineOre ? MINE_ORE : 0) | (chopLogs ? CHOP_LOGS : 0) | (digThrough ? DIG_THROUGH : 0);
    }

    public static WorkerBehavior fromFlags(int flags, int unitAreaRadius) {
        return new WorkerBehavior(unitAreaRadius, (flags & MINE_ORE) != 0, (flags & CHOP_LOGS) != 0, (flags & DIG_THROUGH) != 0);
    }

    /** True if there is any kind of work for an idle worker to look for. Digging through alone is not work. */
    public boolean any() {
        return mineOre || chopLogs;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putInt("UnitArea", unitAreaRadius);
        return tag;
    }

    public static WorkerBehavior load(CompoundTag tag) {
        if (!tag.contains("Flags")) {
            return DEFAULT;
        }
        return fromFlags(tag.getInt("Flags"), tag.getInt("UnitArea"));
    }
}
