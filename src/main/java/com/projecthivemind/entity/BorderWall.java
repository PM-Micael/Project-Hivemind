package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.HiveLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A wall round the Hive Heart, three blocks high, built on the ring two blocks inside the hive border, with the outer two blocks of the
 * border cleared so that no ground out there stands level with the top of the wall.
 *
 * <p>Heights are measured from the floor the Heart stands on. The wall reaches three blocks above that floor in every column of the
 * ring, built up from whatever ground is there (so it is taller over a dip, and nothing is placed where the ground already reaches
 * that high). In the two outer rings of the border any ground at or above the top of the wall is dug away, so a mob cannot walk in
 * from a ledge. There is one doorway, one block wide and two high, in the middle of the south side: otherwise the hive's own units
 * would be shut in.
 *
 * <p>Like the other plans this is only a recipe. What is still to do is read from the world every time, so a half-built wall, or
 * one left when the game closed, carries on where it was.
 */
public final class BorderWall {
    /** How high the wall is, above the Heart's floor. */
    public static final int HEIGHT = 3;

    private BorderWall() {
    }

    private static int radius(HiveHeart heart) {
        return HiveLevels.get(heart.hiveLevel()).infectionRadius();
    }

    private static int floorTop(HiveHeart heart) {
        return (int) Math.floor(heart.getY()) - 1;
    }

    /** The columns of a ring at this many blocks from the Heart (in a square), as (x, z). */
    private static List<int[]> ring(HiveHeart heart, int distance) {
        List<int[]> columns = new ArrayList<>();
        BlockPos center = heart.blockPosition();
        for (int dx = -distance; dx <= distance; dx++) {
            for (int dz = -distance; dz <= distance; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == distance) {
                    columns.add(new int[] {center.getX() + dx, center.getZ() + dz});
                }
            }
        }
        return columns;
    }

    private static int groundTop(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** The nearest block that still has to be dug out of the outer rings: the top of a column whose ground reaches the wall's top. */
    @Nullable
    public static BlockPos nextDig(ServerLevel level, HiveHeart heart, Vec3 from) {
        int limit = floorTop(heart) + HEIGHT;
        int radius = radius(heart);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int distance = radius - 1; distance <= radius; distance++) {
            for (int[] column : ring(heart, distance)) {
                if (!level.hasChunkAt(probe.set(column[0], limit, column[1]))) {
                    continue;
                }
                int top = groundTop(level, column[0], column[1]);
                if (top < limit) {
                    continue;
                }
                BlockPos pos = new BlockPos(column[0], top, column[1]);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0.0F) {
                    continue;
                }
                double distanceSqr = Vec3.atCenterOf(pos).distanceToSqr(from);
                if (distanceSqr < bestDistance) {
                    bestDistance = distanceSqr;
                    best = pos;
                }
            }
        }
        return best;
    }

    /** True if some columns of the wall's ring or of the outer rings are in chunks that are not loaded: work in them cannot be seen, so the wall is not known to be whole. */
    public static boolean hasUnloadedColumns(ServerLevel level, HiveHeart heart) {
        int topY = floorTop(heart) + HEIGHT;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int distance = radius(heart) - 2; distance <= radius(heart); distance++) {
            for (int[] column : ring(heart, distance)) {
                if (!level.hasChunkAt(probe.set(column[0], topY, column[1]))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The nearest block of the wall still to be placed: the lowest missing one of a column on the ring. */
    @Nullable
    public static BlockPos nextPlace(ServerLevel level, HiveHeart heart, Vec3 from, BlockState wallState) {
        int topY = floorTop(heart) + HEIGHT;
        int distance = radius(heart) - 2;
        BlockPos center = heart.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int[] column : ring(heart, distance)) {
            if (!level.hasChunkAt(probe.set(column[0], topY, column[1]))) {
                continue;
            }
            boolean doorway = column[0] == center.getX() && column[1] == center.getZ() + distance;
            int ground = groundTop(level, column[0], column[1]);
            // From just above the ground up to the top; at the doorway only the lintel.
            for (int y = Math.max(ground + 1, doorway ? topY : floorTop(heart) - 64); y <= topY; y++) {
                BlockPos pos = new BlockPos(column[0], y, column[1]);
                BlockState state = level.getBlockState(pos);
                if (state.canBeReplaced() && state.getFluidState().isEmpty()
                        && level.isUnobstructed(wallState, pos, CollisionContext.empty())) {
                    double distanceSqr = Vec3.atCenterOf(pos).distanceToSqr(from);
                    if (distanceSqr < bestDistance) {
                        bestDistance = distanceSqr;
                        best = pos;
                    }
                    break;
                }
            }
        }
        return best;
    }
}
