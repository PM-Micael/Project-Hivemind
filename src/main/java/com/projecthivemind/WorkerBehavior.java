package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How one worker behaves when it has no orders and is not selected by the player. Each worker has its own settings,
 * edited from its page of the hive menu.
 *
 * <p>A worker only goes after blocks that are inside the radius of the option that wants them, that the hive can see
 * (an open face, not buried in rock), and that the hive's tools can harvest.
 *
 * @param mineOre    mine ore blocks
 * @param oreRadius  how far around itself a worker looks for ore, in blocks
 * @param chopLogs   fell the natural trees within logRadius of the worker: every log of a tree, and its leaves (like fellTrees, but by range instead of by the hive border)
 * @param logRadius  how far around itself a worker looks for logs, in blocks
 * @param digThrough if a block it wants cannot be reached on foot, dig toward it in a straight line
 * @param stayInside when not selected, always try to be inside the hive area: this wins over everything else, jobs included
 * @param harvestCrops harvest fully grown crops anywhere inside the hive area (no radius: the whole area)
 * @param clearPlants clear the grass and flowers inside the hive area (no radius: the whole area)
 * @param wander walk about at random while idle, instead of standing still
 * @param flattenGround fill the gaps in the ground inside the hive area, up to the Heart's own level, with the block the worker was given
 * @param flattenTeam   the same inside the team area around the team's scout (the scout's own floor is the level), for a worker that follows a scout
 * @param fellTrees       fell the natural trees inside the hive border: every log, and the leaves
 * @param fleeHostiles   run away from hostile mobs that come within fleeRadius: the highest priority a worker has, and it holds even while the worker is on a job
 * @param fleeRadius     how close, in blocks, a hostile mob has to come for the worker to run
 * @param stayAtHeart    when idle inside the border (and not wandering), walk to the Hive Heart and stand in it
 */
public record WorkerBehavior(boolean mineOre, int oreRadius, boolean chopLogs, int logRadius, boolean digThrough, boolean stayInside, boolean harvestCrops, boolean clearPlants, boolean wander, boolean flattenGround, boolean flattenTeam, boolean fellTrees, boolean fleeHostiles, int fleeRadius, boolean stayAtHeart) {
    /** Kept lower than the soldiers' limit because workers scan every block in their range for work. */
    public static final int MAX_RADIUS = 32;

    /** Workers do nothing on their own until the player turns something on. */
    public static final WorkerBehavior DEFAULT = new WorkerBehavior(false, 8, false, 8, false, false, false, false, false, false, false, false, false, 16, true);

    private static final int MINE_ORE = 1;
    private static final int CHOP_LOGS = 2;
    private static final int DIG_THROUGH = 4;
    private static final int STAY_INSIDE = 8;
    private static final int HARVEST_CROPS = 16;
    private static final int CLEAR_PLANTS = 32;
    private static final int WANDER = 64;
    private static final int FLATTEN_GROUND = 128;
    private static final int FELL_TREES = 2048;
    private static final int FLATTEN_TEAM = 16384;
    private static final int FLEE_HOSTILES = 8192;
    /** Saved when the option is OFF, so that a worker saved before the option existed has it on. */
    private static final int NOT_AT_HEART = 65536;

    public WorkerBehavior {
        oreRadius = Mth.clamp(oreRadius, 0, MAX_RADIUS);
        logRadius = Mth.clamp(logRadius, 0, MAX_RADIUS);
        fleeRadius = Mth.clamp(fleeRadius, 0, MAX_RADIUS);
    }

    /** The three checkboxes packed into one number. */
    public int flags() {
        return (mineOre ? MINE_ORE : 0) | (chopLogs ? CHOP_LOGS : 0) | (digThrough ? DIG_THROUGH : 0) | (stayInside ? STAY_INSIDE : 0) | (harvestCrops ? HARVEST_CROPS : 0) | (clearPlants ? CLEAR_PLANTS : 0) | (wander ? WANDER : 0) | (flattenGround ? FLATTEN_GROUND : 0) | (flattenTeam ? FLATTEN_TEAM : 0) | (fellTrees ? FELL_TREES : 0) | (fleeHostiles ? FLEE_HOSTILES : 0) | (stayAtHeart ? 0 : NOT_AT_HEART);
    }

    /** The radii, in the order of the options above that have one. */
    public int[] radii() {
        return new int[] {oreRadius, logRadius, fleeRadius};
    }

    public static WorkerBehavior from(int flags, int[] radii) {
        return new WorkerBehavior((flags & MINE_ORE) != 0, radii[0], (flags & CHOP_LOGS) != 0, radii[1], (flags & DIG_THROUGH) != 0, (flags & STAY_INSIDE) != 0, (flags & HARVEST_CROPS) != 0, (flags & CLEAR_PLANTS) != 0, (flags & WANDER) != 0, (flags & FLATTEN_GROUND) != 0, (flags & FLATTEN_TEAM) != 0, (flags & FELL_TREES) != 0, (flags & FLEE_HOSTILES) != 0, radii.length > 2 ? radii[2] : 16, (flags & NOT_AT_HEART) == 0);
    }

    /** True if there is any kind of work for an idle worker to look for. Digging through alone is not work. */
    public boolean any() {
        return mineOre;
    }

    /** The furthest any ticked option reaches: how far a worker has to look. */
    public int maxRadius() {
        return mineOre ? oreRadius : 0;
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
