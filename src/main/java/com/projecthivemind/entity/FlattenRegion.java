package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * A piece of ground a worker set to flatten works on: the columns of the block range, the floor it is levelled to, and, for a round area, the
 * circle those columns must be inside. There are two kinds: the hive area (levelled to the floor the Heart stands on), and the team area round
 * the team's scout (levelled to the floor the scout stands on).
 */
public record FlattenRegion(int minX, int maxX, int minZ, int maxZ, int floorTop, @Nullable double[] circle) {
    /** How far past the hive border the hive area's flattening reaches, in blocks. */
    public static final int EDGE_MARGIN = 1;

    /** True if the column is part of the region: always for the hive area, inside the circle for the team area. */
    public boolean contains(int x, int z) {
        if (circle == null) {
            return true;
        }
        double dx = x + 0.5D - circle[0];
        double dz = z + 0.5D - circle[1];
        return dx * dx + dz * dz <= circle[2] * circle[2];
    }

    /** The regions this worker is set to flatten right now: the hive area, and the team area if it follows a scout. */
    public static List<FlattenRegion> of(HiveWorker worker, HiveHeart heart) {
        List<FlattenRegion> regions = new ArrayList<>();
        if (!(worker.level() instanceof ServerLevel level)) {
            return regions;
        }
        if (worker.behavior().flattenGround()) {
            AABB area = HiveArea.areaBox(level, heart);
            // One block further out than the border on every side, so that the edge of the flattened ground is not at the very edge of the area
            // (soldiers that keep to the border do not walk off a ledge there).
            regions.add(new FlattenRegion((int) area.minX - EDGE_MARGIN, (int) area.maxX + EDGE_MARGIN, (int) area.minZ - EDGE_MARGIN, (int) area.maxZ + EDGE_MARGIN,
                    (int) Math.floor(heart.getY()) - 1, null));
        }
        if (worker.behavior().flattenTeam()) {
            Mob leader = heart.teamLeader(worker);
            if (leader != null && leader.level() == level) {
                int radius = heart.teams().radius(heart.teams().teamOf(worker.getUUID()));
                BlockPos at = leader.blockPosition();
                regions.add(new FlattenRegion(at.getX() - radius, at.getX() + radius + 1, at.getZ() - radius, at.getZ() + radius + 1,
                        (int) Math.floor(leader.getY()) - 1, new double[] {leader.getX(), leader.getZ(), radius}));
            }
        }
        return regions;
    }
}
