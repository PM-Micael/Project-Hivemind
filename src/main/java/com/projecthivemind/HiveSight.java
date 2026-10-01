package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hive Sight: what the hive can see. The Heart and every hive unit each see out to their own sight radius (the same
 * for all but scouts, which see much further), and walls block sight: something is in sight only if a straight line
 * from one of them reaches it without passing through an opaque block. Glass, leaves and other see-through blocks do
 * not block it.
 *
 * <p>The hive's sight decides what a worker is allowed to mine, and what the player is shown.
 *
 * <p>Sight lines never load terrain. Unloaded chunks count as blocking, so a scout's very long sight cannot make
 * the game stop and load the world.
 */
public final class HiveSight {
    /** The points just outside each face of a block, which is where it can be seen from. */
    private static final double[][] FACE_POINTS = {
            {0.55D, 0.0D, 0.0D}, {-0.55D, 0.0D, 0.0D},
            {0.0D, 0.55D, 0.0D}, {0.0D, -0.55D, 0.0D},
            {0.0D, 0.0D, 0.55D}, {0.0D, 0.0D, -0.55D}};

    /** One thing that sees: where from, and how far. */
    public record Eye(Vec3 position, double radius) {
    }

    private HiveSight() {
    }

    /** The eyes of the hive: the Heart's middle, and each of the owner's living units, each with its own radius. */
    public static List<Eye> eyes(ServerLevel level, ServerPlayer owner, HiveHeart heart) {
        HiveLevel hiveLevel = HiveLevels.get(heart.hiveLevel());
        List<Eye> eyes = new ArrayList<>();
        eyes.add(new Eye(heart.getBoundingBox().getCenter(), hiveLevel.sightRadius()));
        for (UUID id : HivemindManager.get(owner).allUnits()) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit) {
                eyes.add(new Eye(mob.getEyePosition(), hiveLevel.sightRadius(unit.kind())));
            }
        }
        return eyes;
    }

    /**
     * True if nothing opaque lies on the straight line between two points. Walks the line cell by cell without ever
     * loading a chunk: a cell in an unloaded chunk blocks the line.
     */
    private static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level, (world, pos) -> {
            LevelChunk chunk = world.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                return Boolean.FALSE;
            }
            BlockState state = chunk.getBlockState(pos);
            // Only a fully opaque cube stops sight. Leaves, glass, fences, slabs and the like are looked through.
            return state.isSolidRender(world, pos) ? Boolean.FALSE : null;
        }, world -> Boolean.TRUE);
    }

    /**
     * True if the hive can see this block: one of its eyes, within its radius, has a clear line to a point just
     * outside one of the block's faces. So an ore buried in solid stone is not seen, but one with an exposed face is.
     */
    public static boolean canSeeBlock(ServerLevel level, List<Eye> eyes, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (Eye eye : eyes) {
            if (eye.position().distanceToSqr(center) > eye.radius() * eye.radius()) {
                continue;
            }
            for (double[] offset : FACE_POINTS) {
                if (clear(level, eye.position(), center.add(offset[0], offset[1], offset[2]))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The entity ids of every mob the hive can see right now: within an eye's radius, and in a clear line from it. */
    public static Set<Integer> visibleMobs(ServerLevel level, List<Eye> eyes) {
        Set<Integer> visible = new HashSet<>();
        for (Eye eye : eyes) {
            AABB around = new AABB(eye.position(), eye.position()).inflate(eye.radius());
            for (Mob mob : level.getEntitiesOfClass(Mob.class, around, Entity::isAlive)) {
                Vec3 center = mob.getBoundingBox().getCenter();
                if (!visible.contains(mob.getId()) && eye.position().distanceToSqr(center) <= eye.radius() * eye.radius()
                        && clear(level, eye.position(), center)) {
                    visible.add(mob.getId());
                }
            }
        }
        return visible;
    }
}
