package com.projecthivemind;

import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;

/**
 * The hive area: a square around a Hive Heart, as wide as its level allows (see HiveLevel#infectionRadius), at every
 * height. It is not marked by any block: the player sees its edge as particles (see HiveBorder), and it is the area
 * that collectors gather items in, soldiers defend, and in which nothing spawns on its own.
 */
public final class HiveArea {
    /** How far below the Heart's own block the area reaches: one block, the one the Heart sits on. Nothing deeper is in the hive area. */
    private static final int FLOOR_DEPTH = 1;

    private HiveArea() {
    }

    /**
     * The hive area of a Heart as a box, measured from the Heart's block: as far up as it reaches sideways, but only down to the block the
     * Heart sits on (see {@link #FLOOR_DEPTH}), so what is deep underground is not in it.
     */
    public static AABB areaBox(ServerLevel level, HiveHeart heart) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return new AABB(center.getX() - radius, Math.max(level.getMinBuildHeight(), center.getY() - FLOOR_DEPTH), center.getZ() - radius,
                center.getX() + radius + 1, Math.min(level.getMaxBuildHeight(), center.getY() + radius + 1), center.getZ() + radius + 1);
    }

    /** True if this point is inside this Heart's area (sideways: the area covers every height). */
    public static boolean containsXZ(HiveHeart heart, double x, double z) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return x >= center.getX() - radius && x < center.getX() + radius + 1 && z >= center.getZ() - radius && z < center.getZ() + radius + 1;
    }

    /** Like {@link #containsXZ(HiveHeart, double, double)}, but with the area this many blocks bigger on every side. */
    public static boolean containsXZ(HiveHeart heart, double x, double z, int margin) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius() + margin;
        BlockPos center = heart.blockPosition();
        return x >= center.getX() - radius && x < center.getX() + radius + 1 && z >= center.getZ() - radius && z < center.getZ() + radius + 1;
    }

    /** True if this point is inside this Heart's area as a cube: sideways as for {@link #containsXZ}, as far up as sideways, and down no further than the block the Heart sits on. */
    public static boolean containsCube(HiveHeart heart, double x, double y, double z) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return containsXZ(heart, x, z) && y >= center.getY() - FLOOR_DEPTH && y < center.getY() + radius + 1;
    }

    /** The point of the area nearest to this one, kept a block and a half in from the edge: a walking mob stops about a block short of where it is sent, and must still end up inside. */
    public static net.minecraft.world.phys.Vec3 nearestInside(HiveHeart heart, double x, double z) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        double clampedX = Math.max(center.getX() - radius + 1.5D, Math.min(center.getX() + radius - 0.5D, x));
        double clampedZ = Math.max(center.getZ() - radius + 1.5D, Math.min(center.getZ() + radius - 0.5D, z));
        return new net.minecraft.world.phys.Vec3(clampedX, heart.getY(), clampedZ);
    }

    /** True if this position is inside any Hive Heart's area, at any height. */
    public static boolean contains(LevelAccessor level, BlockPos pos) {
        AABB search = new AABB(pos).inflate(HiveLevels.maxInfectionRadius() + 1);
        for (HiveHeart heart : level.getEntitiesOfClass(HiveHeart.class, search)) {
            int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
            BlockPos center = heart.blockPosition();
            if (Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius) {
                return true;
            }
        }
        return false;
    }
}
