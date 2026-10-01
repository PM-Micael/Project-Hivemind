package com.projecthivemind;

import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The area around a Hive Heart that the hive has infected. Two parts: a visible layer of creep on the surface, and
 * the rule that nothing spawns in the area (all heights, not just the surface).
 */
public final class HiveInfection {
    /** How far above and below the Heart creep is searched for when clearing. */
    private static final int CLEAR_HEIGHT = 12;

    private HiveInfection() {
    }

    /** Lay creep on the surface of the whole area. Skips the Heart's own column. */
    public static void spread(ServerLevel level, BlockPos center, int radius) {
        BlockState creep = ModBlocks.HIVE_CREEP.get().defaultBlockState();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                BlockState existing = level.getBlockState(pos);
                boolean free = existing.isAir() || (existing.canBeReplaced() && existing.getFluidState().isEmpty());
                if (free && creep.canSurvive(level, pos)) {
                    level.setBlock(pos, creep, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Remove the creep again, e.g. when the Heart is destroyed. */
    public static void clear(ServerLevel level, BlockPos center, int radius) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -CLEAR_HEIGHT; dy <= CLEAR_HEIGHT; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(ModBlocks.HIVE_CREEP.get())) {
                        level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /** True if this position is inside any Hive Heart's infected area, at any height. */
    public static boolean isInfected(LevelAccessor level, BlockPos pos) {
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
