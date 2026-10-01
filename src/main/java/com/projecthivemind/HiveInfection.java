package com.projecthivemind;

import java.util.List;
import java.util.Map;

import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The area around a Hive Heart that the hive has infected. Two parts: creep, a full block that consumes the ground
 * of the area, and the rule that nothing spawns in the area (all heights, not just the surface).
 *
 * <p>Every block the creep consumes is remembered by the Heart, so destroying the Heart puts the ground back.
 */
public final class HiveInfection {
    /**
     * A reason for creep to leave a ground block alone. When any rule protects a block, a spread skips it. New
     * protections are added to {@link #PROTECTION_RULES}.
     */
    @FunctionalInterface
    public interface ProtectionRule {
        boolean protects(ServerLevel level, BlockPos ground, BlockState groundState);
    }

    private static final List<ProtectionRule> PROTECTION_RULES = List.of(
            HiveInfection::dirtWithGrowable);

    private HiveInfection() {
    }

    // ---- protection rules ----

    /**
     * Dirt-type ground with something growing on it: a sapling, a grown tree (its trunk), or a planted crop. Farmland
     * counts as dirt here because that is where seeds are planted.
     */
    private static boolean dirtWithGrowable(ServerLevel level, BlockPos ground, BlockState groundState) {
        if (!groundState.is(BlockTags.DIRT) && !groundState.is(Blocks.FARMLAND)) {
            return false;
        }
        BlockState above = level.getBlockState(ground.above());
        return above.is(BlockTags.SAPLINGS) || above.is(BlockTags.OVERWORLD_NATURAL_LOGS) || above.is(BlockTags.CROPS);
    }

    // ---- spreading ----

    /** Turn the ground of the whole area into creep, except where a protection rule says to leave it. */
    public static void spread(ServerLevel level, HiveHeart heart) {
        BlockPos center = heart.blockPosition();
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockState creep = ModBlocks.HIVE_CREEP.get().defaultBlockState();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos ground = findGround(level, center.getX() + dx, center.getZ() + dz);
                BlockState groundState = level.getBlockState(ground);
                if (canConsume(level, ground, groundState)) {
                    heart.recordConsumed(ground, groundState);
                    // Full update, so anything that can no longer stand on the new ground (grass, flowers) lets go.
                    level.setBlock(ground, creep, Block.UPDATE_ALL);
                }
            }
        }
    }

    /**
     * The ground block of a column: the top block, looking through the trunk of a tree so the soil under it is what
     * gets judged. Leaves are already ignored by the heightmap.
     */
    private static BlockPos findGround(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        while (pos.getY() > level.getMinBuildHeight() && level.getBlockState(pos).is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
            pos.move(Direction.DOWN);
        }
        return pos.immutable();
    }

    private static boolean canConsume(ServerLevel level, BlockPos ground, BlockState state) {
        // Things creep cannot meaningfully consume at all, whatever the rules say.
        if (state.isAir() || state.is(ModBlocks.HIVE_CREEP.get()) || !state.getFluidState().isEmpty()
                || state.hasBlockEntity() || state.getDestroySpeed(level, ground) < 0.0F) {
            return false;
        }
        for (ProtectionRule rule : PROTECTION_RULES) {
            if (rule.protects(level, ground, state)) {
                return false;
            }
        }
        return true;
    }

    /** Give the ground back, e.g. when the Heart is destroyed: every consumed block returns where its creep still is. */
    public static void clear(ServerLevel level, HiveHeart heart) {
        for (Map.Entry<BlockPos, BlockState> consumed : heart.consumedBlocks().entrySet()) {
            if (level.getBlockState(consumed.getKey()).is(ModBlocks.HIVE_CREEP.get())) {
                level.setBlock(consumed.getKey(), consumed.getValue(), Block.UPDATE_ALL);
            }
        }
        heart.consumedBlocks().clear();
    }

    /**
     * The hive area of a Heart as a box: the same square the creep marks out, measured from the Heart's position, at
     * every height. It does not depend on the creep: breaking the creep does not shrink it. Collectors gather items
     * in it and soldiers defend it. It grows with the hive's level.
     */
    public static AABB areaBox(ServerLevel level, HiveHeart heart) {
        int radius = HiveLevels.get(heart.hiveLevel()).infectionRadius();
        BlockPos center = heart.blockPosition();
        return new AABB(center.getX() - radius, level.getMinBuildHeight(), center.getZ() - radius,
                center.getX() + radius + 1, level.getMaxBuildHeight(), center.getZ() + radius + 1);
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
