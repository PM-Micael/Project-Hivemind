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
    private HiveArea() {
    }

    /**
     * The hive area of a Heart as a box, measured from the Heart's block: a cube, reaching as far up and down as it does sideways, so
     * what is deep underground or high in the sky is not in it.
     */
    public static AABB areaBox(ServerLevel level, HiveHeart heart) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return new AABB(center.getX() - radius, Math.max(level.getMinBuildHeight(), center.getY() - radius), center.getZ() - radius,
                center.getX() + radius + 1, Math.min(level.getMaxBuildHeight(), center.getY() + radius + 1), center.getZ() + radius + 1);
    }

    /** True if this point is inside this Heart's area (sideways: the area covers every height). */
    public static boolean containsXZ(HiveHeart heart, double x, double z) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return x >= center.getX() - radius && x < center.getX() + radius + 1 && z >= center.getZ() - radius && z < center.getZ() + radius + 1;
    }

    /** True if this point is inside this Heart's area as a cube: sideways as for {@link #containsXZ}, and no further up or down than sideways. */
    public static boolean containsCube(HiveHeart heart, double x, double y, double z) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return containsXZ(heart, x, z) && y >= center.getY() - radius && y < center.getY() + radius + 1;
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
