package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How one worker behaves when it has no orders and is not selected by the player. Each worker has its own settings,
 * edited from its page of the hive menu.
 *
 * <p>A worker only goes after blocks that are inside the radius of the option that wants them, that the hive can see
 * (see HiveSight), and that the hive's tools can harvest.
 *
 * @param mineOre    mine ore blocks
 * @param oreRadius  how far around itself a worker looks for ore, in blocks
 * @param chopLogs   chop the logs of natural trees
 * @param logRadius  how far around itself a worker looks for logs, in blocks
 * @param digThrough if a block it wants cannot be reached on foot, dig toward it in a straight line
 * @param stayInside when not selected, always try to be inside the hive area: this wins over everything else, jobs included
 * @param harvestCrops harvest fully grown crops anywhere inside the hive area (no radius: the whole area)
 * @param clearPlants clear the grass and flowers inside the hive area (no radius: the whole area)
 * @param wander walk about at random while idle, instead of standing still
 * @param flattenGround fill the gaps in the ground inside the hive area, up to the Heart's own level, with the block the worker was given
 */
public record WorkerBehavior(boolean mineOre, int oreRadius, boolean chopLogs, int logRadius, boolean digThrough, boolean stayInside, boolean harvestCrops, boolean clearPlants, boolean wander, boolean flattenGround) {
    /** Kept lower than the soldiers' limit because workers scan every block in their range for work. */
    public static final int MAX_RADIUS = 32;

    /** Workers do nothing on their own until the player turns something on. */
    public static final WorkerBehavior DEFAULT = new WorkerBehavior(false, 8, false, 8, false, false, false, false, false, false);

    private static final int MINE_ORE = 1;
    private static final int CHOP_LOGS = 2;
    private static final int DIG_THROUGH = 4;
    private static final int STAY_INSIDE = 8;
    private static final int HARVEST_CROPS = 16;
    private static final int CLEAR_PLANTS = 32;
    private static final int WANDER = 64;
    private static final int FLATTEN_GROUND = 128;

    public WorkerBehavior {
        oreRadius = Mth.clamp(oreRadius, 0, MAX_RADIUS);
        logRadius = Mth.clamp(logRadius, 0, MAX_RADIUS);
    }

    /** The three checkboxes packed into one number. */
    public int flags() {
        return (mineOre ? MINE_ORE : 0) | (chopLogs ? CHOP_LOGS : 0) | (digThrough ? DIG_THROUGH : 0) | (stayInside ? STAY_INSIDE : 0) | (harvestCrops ? HARVEST_CROPS : 0) | (clearPlants ? CLEAR_PLANTS : 0) | (wander ? WANDER : 0) | (flattenGround ? FLATTEN_GROUND : 0);
    }

    /** The radii, in the order of the options above that have one. */
    public int[] radii() {
        return new int[] {oreRadius, logRadius};
    }

    public static WorkerBehavior from(int flags, int[] radii) {
        return new WorkerBehavior((flags & MINE_ORE) != 0, radii[0], (flags & CHOP_LOGS) != 0, radii[1], (flags & DIG_THROUGH) != 0, (flags & STAY_INSIDE) != 0, (flags & HARVEST_CROPS) != 0, (flags & CLEAR_PLANTS) != 0, (flags & WANDER) != 0, (flags & FLATTEN_GROUND) != 0);
    }

    /** True if there is any kind of work for an idle worker to look for. Digging through alone is not work. */
    public boolean any() {
        return mineOre || chopLogs;
    }

    /** The furthest any ticked option reaches: how far a worker has to look. */
    public int maxRadius() {
        return Math.max(mineOre ? oreRadius : 0, chopLogs ? logRadius : 0);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putIntArray("Radii", radii());
        return tag;
    }

    public static WorkerBehavior load(CompoundTag tag) {
        if (!tag.contains("Flags")) {
            return DEFAULT;
        }
        int[] radii = tag.getIntArray("Radii");
        if (radii.length < 2) {
            int shared = tag.contains("UnitArea") ? tag.getInt("UnitArea") : 8;
            radii = new int[] {shared, shared};
        }
        return from(tag.getInt("Flags"), radii);
    }
}
