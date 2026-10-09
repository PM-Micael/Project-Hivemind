package com.projecthivemind.entity;

import java.util.List;

import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Carries a scout on a {@link UnitAction.Kind#TRAVEL} job: a long walk over the surface to a spot, going round hostile mobs on the way.
 *
 * <p>A mob's path finder only looks so far, so the walk is made in short legs. Every leg ends at a waypoint a short way ahead, in the
 * direction of the spot, bent away from any hostile mob that is near (the nearer, the harder) and put on the ground there. If the scout
 * makes no headway for a while the direction is turned aside, a little further each time, and after enough tries the job is given up.
 * Chunks round the scout are kept loaded, so it keeps going when the player is far away.
 */
public final class ScoutTravel {
    public enum Result {
        GOING, ARRIVED, FAILED
    }

    /** Close enough to the spot to count as there, in blocks (flat distance). */
    private static final double ARRIVED = 6.0D;
    /** How far ahead a waypoint is, and how far when running from something. Kept inside the scout's follow range. */
    private static final double STEP = 28.0D;
    private static final double FLEE_STEP = 20.0D;
    /** Hostile mobs this near are steered round, and the weight of the steering grows as they get nearer. */
    private static final double DANGER_RADIUS = 18.0D;
    /** The scout's walk speed multiplier on a leg, and when something hostile is near. */
    private static final double SPEED = 1.1D;
    private static final double FLEE_SPEED = 1.35D;
    /** Ticks between looks round for danger and for a new leg. */
    private static final int THINK_INTERVAL = 10;
    /** A leg is walked again after this long even if its path has not run out. */
    private static final int REPATH_INTERVAL = 80;
    /** Every this many ticks the headway is checked: the scout must be this much nearer than before, or the direction is turned aside. */
    private static final int PROGRESS_INTERVAL = 100;
    private static final double MIN_PROGRESS = 3.0D;
    /** How many checks in a row may show no headway before the job is given up. */
    private static final int MAX_FAILURES = 10;
    /** Chunk ticket: kept fresh this often, over this many chunks round the scout (so the scout and the legs ahead are loaded). */
    private static final int TICKET_INTERVAL = 40;
    private static final int TICKET_DISTANCE = 4;
    /** How the direction is turned aside after no headway, in degrees, in order: left and right, a little more each time. */
    /** Staying within a 10 by 10 square for a minute counts as stuck. */
    private static final double STUCK_HALF_SIDE = 5.0D;
    private static final int STUCK_TICKS = 20 * 60;
    private static final int[] DETOURS = {45, -45, 90, -90, 135, -135, 180, 60, -60, 120};

    private int nextThink;
    private int nextRepath;
    private int nextProgressCheck;
    private int nextTicket;
    private double bestDistance = Double.MAX_VALUE;
    private int failures;
    /** Set when danger was near since the last headway check: running from it is not a reason to turn aside. */
    private boolean threatened;
    private int detour;
    /** Where the scout was when it last moved out of the stuck box, and when it got there. */
    private Vec3 anchor;
    private int anchorSince;
    /** The scout has not left the box for a minute and the owner has not been told yet; read once with {@link #takeStuck}. */
    private boolean stuck;
    private boolean stuckReported;

    /** True once each time the scout has stayed within a small square for a minute: it is stuck (or boxed in by mobs), and the owner should hear. */
    public boolean takeStuck() {
        boolean was = stuck;
        stuck = false;
        return was;
    }

    private void watchStuck(HiveScout scout) {
        if (anchor == null || Math.abs(scout.getX() - anchor.x) > STUCK_HALF_SIDE || Math.abs(scout.getZ() - anchor.z) > STUCK_HALF_SIDE) {
            anchor = scout.position();
            anchorSince = scout.tickCount;
            stuckReported = false;
        } else if (!stuckReported && scout.tickCount - anchorSince >= STUCK_TICKS) {
            stuckReported = true;
            stuck = true;
        }
    }

    public void reset() {
        anchor = null;
        stuck = false;
        stuckReported = false;
        nextThink = 0;
        nextRepath = 0;
        nextProgressCheck = 0;
        nextTicket = 0;
        bestDistance = Double.MAX_VALUE;
        failures = 0;
        threatened = false;
        detour = 0;
    }

    public Result tick(HiveScout scout, UnitAction action) {
        if (!(scout.level() instanceof ServerLevel level) || action.pos() == null || level.dimension() != Level.OVERWORLD) {
            return Result.FAILED;
        }
        keepLoaded(scout, level);
        watchStuck(scout);
        Vec3 target = Vec3.atBottomCenterOf(action.pos());
        double dx = target.x - scout.getX();
        double dz = target.z - scout.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= ARRIVED) {
            scout.getNavigation().stop();
            return Result.ARRIVED;
        }
        if (scout.tickCount < nextThink) {
            return Result.GOING;
        }
        nextThink = scout.tickCount + THINK_INTERVAL;

        List<Mob> hostiles = level.getEntitiesOfClass(Mob.class, scout.getBoundingBox().inflate(DANGER_RADIUS, 6.0D, DANGER_RADIUS),
                mob -> mob.isAlive() && mob instanceof Enemy && !(mob instanceof HiveUnit) && !(mob instanceof HiveHeart));
        boolean danger = !hostiles.isEmpty();
        if (danger) {
            threatened = true;
        }

        if (scout.tickCount >= nextProgressCheck) {
            nextProgressCheck = scout.tickCount + PROGRESS_INTERVAL;
            if (bestDistance - distance >= MIN_PROGRESS) {
                failures = 0;
                detour = 0;
            } else if (!threatened) {
                failures++;
                detour = failures;
                if (failures > MAX_FAILURES) {
                    return Result.FAILED;
                }
            }
            if (!threatened || distance < bestDistance) {
                bestDistance = distance;
            }
            threatened = false;
        }

        // Keep walking the current leg if it is going well; a new one when it has run out, danger is near, or it has gone on a while.
        if (!danger && !scout.getNavigation().isDone() && scout.tickCount < nextRepath) {
            return Result.GOING;
        }

        // The way to go: towards the spot, pushed away from each hostile mob, harder the nearer it is.
        double headX = dx / distance;
        double headZ = dz / distance;
        for (Mob hostile : hostiles) {
            double awayX = scout.getX() - hostile.getX();
            double awayZ = scout.getZ() - hostile.getZ();
            double away = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (away < 0.01D) {
                continue;
            }
            double weight = 2.5D * (DANGER_RADIUS - Math.min(away, DANGER_RADIUS)) / DANGER_RADIUS;
            headX += awayX / away * weight;
            headZ += awayZ / away * weight;
        }
        double length = Math.sqrt(headX * headX + headZ * headZ);
        if (length < 0.05D) {
            // Pushed equally every way: go sideways.
            headX = -dz / distance;
            headZ = dx / distance;
            length = 1.0D;
        }
        headX /= length;
        headZ /= length;
        if (detour > 0 && !danger) {
            double angle = Math.toRadians(DETOURS[(detour - 1) % DETOURS.length]);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double turnedX = headX * cos - headZ * sin;
            double turnedZ = headX * sin + headZ * cos;
            headX = turnedX;
            headZ = turnedZ;
        }

        double step = danger ? FLEE_STEP : STEP;
        if (!danger && detour == 0 && distance < step) {
            step = distance;
        }
        BlockPos waypoint = surfaceWaypoint(level, scout, headX, headZ, step);
        if (waypoint == null) {
            // Nothing loaded that way yet: wait for the chunks.
            nextThink = scout.tickCount + 5;
            return Result.GOING;
        }
        boolean walking = scout.getNavigation().moveTo(waypoint.getX() + 0.5D, waypoint.getY(), waypoint.getZ() + 0.5D, danger ? FLEE_SPEED : SPEED);
        nextRepath = scout.tickCount + REPATH_INTERVAL;
        if (!walking) {
            // No way that way: turn aside at once instead of waiting for the headway check.
            failures++;
            detour = failures;
            if (failures > MAX_FAILURES) {
                return Result.FAILED;
            }
        }
        return Result.GOING;
    }

    /** The ground at the end of a leg in this direction, or a shorter leg if the end is not loaded; null if even a short one is not. */
    private static BlockPos surfaceWaypoint(ServerLevel level, HiveScout scout, double headX, double headZ, double step) {
        for (double length = step; length >= 6.0D; length *= 0.6D) {
            int x = (int) Math.floor(scout.getX() + headX * length);
            int z = (int) Math.floor(scout.getZ() + headZ * length);
            if (level.hasChunk(x >> 4, z >> 4)) {
                return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            }
        }
        return null;
    }

    /** Keep the chunks round the scout loaded and ticking, so the walk goes on when no player is near. */
    private void keepLoaded(HiveScout scout, ServerLevel level) {
        if (scout.tickCount >= nextTicket) {
            nextTicket = scout.tickCount + TICKET_INTERVAL;
            level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(scout.blockPosition()), TICKET_DISTANCE, scout.blockPosition());
        }
    }
}
