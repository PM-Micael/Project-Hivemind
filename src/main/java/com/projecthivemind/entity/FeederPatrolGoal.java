package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * What a feeder does when it has nothing else to do: it drifts along the hive border, flying from one point on the edge of the hive area
 * to another a little way along it, a few blocks above the ground. The lowest priority a feeder has.
 */
public class FeederPatrolGoal extends Goal {
    private static final double SPEED = 0.8D;
    /** How far along the border, in blocks, the next point is at most, and how far inside the edge it is. */
    private static final int STEP = 12;
    private static final double INSET = 1.5D;
    /** The pause between flights, in ticks, at least and at most (1 to 4 seconds). */
    private static final int MIN_PAUSE = 20;
    private static final int MAX_PAUSE = 80;
    private static final int GIVE_UP_TICKS = 200;

    private final HiveFeeder feeder;
    @Nullable
    private Vec3 destination;
    private int nextFlight;
    private int flightTicks;
    /** Which way round the border this feeder goes: 1 or -1, kept for the whole flight so it does not zigzag. */
    private int direction = 1;

    public FeederPatrolGoal(HiveFeeder feeder) {
        this.feeder = feeder;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = feeder.findLocalHeart();
        // A deadline, not tickCount % N: goals are only evaluated on some ticks, so a modulo check can never line up.
        if (heart == null || feeder.tickCount < nextFlight || !(feeder.level() instanceof ServerLevel level)) {
            return false;
        }
        nextFlight = feeder.tickCount + MIN_PAUSE + feeder.getRandom().nextInt(MAX_PAUSE - MIN_PAUSE);
        destination = nextPoint(level, heart);
        return destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        return destination != null && !feeder.getNavigation().isDone() && ++flightTicks < GIVE_UP_TICKS;
    }

    @Override
    public void start() {
        flightTicks = 0;
        feeder.getNavigation().moveTo(destination.x, destination.y, destination.z, SPEED);
    }

    @Override
    public void stop() {
        feeder.getNavigation().stop();
        destination = null;
    }

    /** A point on the border a few blocks along from the feeder: it goes to the nearest point of the border, then round it. */
    @Nullable
    private Vec3 nextPoint(ServerLevel level, HiveHeart heart) {
        AABB area = HiveArea.areaBox(level, heart);
        double minX = area.minX + INSET;
        double maxX = area.maxX - INSET;
        double minZ = area.minZ + INSET;
        double maxZ = area.maxZ - INSET;
        if (maxX <= minX || maxZ <= minZ) {
            return null;
        }
        // Where the feeder is on the border's loop, as a distance from the corner (minX, minZ), going round the sides in turn.
        double width = maxX - minX;
        double depth = maxZ - minZ;
        double perimeter = 2 * (width + depth);
        double along = loopPosition(feeder.getX(), feeder.getZ(), minX, maxX, minZ, maxZ, width, depth);
        if (feeder.getRandom().nextInt(4) == 0) {
            direction = -direction;
        }
        along = ((along + direction * (STEP / 2 + feeder.getRandom().nextInt(STEP))) % perimeter + perimeter) % perimeter;
        double x;
        double z;
        if (along < width) {
            x = minX + along;
            z = minZ;
        } else if (along < width + depth) {
            x = maxX;
            z = minZ + (along - width);
        } else if (along < 2 * width + depth) {
            x = maxX - (along - width - depth);
            z = maxZ;
        } else {
            x = minX;
            z = maxZ - (along - 2 * width - depth);
        }
        BlockPos column = BlockPos.containing(x, 0, z);
        if (!level.hasChunkAt(column)) {
            return null;
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
        return new Vec3(x, ground + 2.0D + feeder.getRandom().nextInt(3), z);
    }

    /** The distance round the border's loop, from the corner (minX, minZ), of the point of the loop nearest this one. */
    private static double loopPosition(double x, double z, double minX, double maxX, double minZ, double maxZ, double width, double depth) {
        double cx = Math.max(minX, Math.min(maxX, x));
        double cz = Math.max(minZ, Math.min(maxZ, z));
        double toMinX = cx - minX;
        double toMaxX = maxX - cx;
        double toMinZ = cz - minZ;
        double toMaxZ = maxZ - cz;
        double nearest = Math.min(Math.min(toMinX, toMaxX), Math.min(toMinZ, toMaxZ));
        if (nearest == toMinZ) {
            return cx - minX;
        } else if (nearest == toMaxX) {
            return width + (cz - minZ);
        } else if (nearest == toMaxZ) {
            return width + depth + (maxX - cx);
        }
        return 2 * width + depth + (maxZ - cz);
    }
}
