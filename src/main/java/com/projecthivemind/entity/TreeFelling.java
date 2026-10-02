package com.projecthivemind.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Bringing a whole tree down. A worker set to fell trees digs the bottom log of a natural tree as an ordinary dig order; when that log
 * breaks, {@link #plan} lists the rest of the tree (every log joined to it, then the leaves hanging on them), and the worker takes it a
 * segment at a time. Everything that comes out goes into the hive's inventory. Only inside the hive area, and only natural logs, so nothing the player built
 * out of logs is touched, and only leaves that nothing else holds up (no other log close by), so a neighbouring tree keeps its own.
 */
public final class TreeFelling {
    /** The most logs, and the most leaves, one tree can have taken down at once. */
    private static final int MAX_LOGS = 400;
    private static final int MAX_LEAVES = 600;
    /** A leaf stays if a log it could hang on is this close (leaves decay when no log is within 6, so a few blocks is the practical reach). */
    private static final int HOLD_RADIUS = 4;

    private TreeFelling() {
    }

    /** True if this is a log of a natural tree. */
    public static boolean isNaturalLog(BlockState state) {
        return state.is(BlockTags.OVERWORLD_NATURAL_LOGS);
    }

    /** True for a log of a natural tree or a leaf that grew (not one a player placed): what felling a tree takes. */
    public static boolean isTreeBlock(BlockState state) {
        return isNaturalLog(state) || isNaturalLeaf(state);
    }

    /** True if this block of a tree is still to be taken: a natural log, or a natural leaf that no other log holds up. */
    public static boolean stillToTake(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return isNaturalLog(state) || (isNaturalLeaf(state) && !heldUpByAnotherLog(level, pos));
    }

    /** True for a leaf that grew, not one a player placed: leaves from the world are not persistent. */
    private static boolean isNaturalLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) && !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
    }

    /**
     * Whether this log is the foot of a tree worth felling: a natural log with no log under it, that is part of a tree with at least one
     * natural leaf (so a lone log lying about is not taken for a tree).
     */
    public static boolean isTreeFoot(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isNaturalLog(state) || isNaturalLog(level.getBlockState(pos.below()))) {
            return false;
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(pos);
        seen.add(pos);
        while (!queue.isEmpty() && seen.size() < 80) {
            BlockPos at = queue.poll();
            for (BlockPos near : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                BlockState nearState = level.getBlockState(near);
                if (isNaturalLeaf(nearState)) {
                    return true;
                }
                if (isNaturalLog(nearState) && seen.add(near.immutable())) {
                    queue.add(near.immutable());
                }
            }
        }
        return false;
    }

    /**
     * The blocks of a tree that has this log for its foot, in the order they are to be taken: the foot, its logs from the
     * bottom up, then the leaves that hang on them, nearest the trunk first. The worker digs them one by one, each as long as it takes with
     * its tool, as a player would (see {@link HiveWorker}). Only blocks inside {@code area} are listed.
     */
    public static List<BlockPos> plan(ServerLevel level, AABB area, BlockPos broken) {

        // Every log joined to the one that was cut: through the blocks around it (a trunk, its branches and any second trunk).
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> logSet = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(broken);
        logSet.add(broken);
        while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos at = queue.poll();
            for (BlockPos near : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                if (!logSet.contains(near) && area.contains(near.getX() + 0.5D, near.getY() + 0.5D, near.getZ() + 0.5D)
                        && isNaturalLog(level.getBlockState(near))) {
                    queue.add(near.immutable());
                    logSet.add(near.immutable());
                    logs.add(near.immutable());
                }
            }
        }
        logs.sort(java.util.Comparator.comparingInt(BlockPos::getY));

        // The leaves that hang on those logs: those within reach of one of them, through other leaves.
        Set<BlockPos> leafSet = new HashSet<>();
        List<BlockPos> leaves = new ArrayList<>();
        ArrayDeque<BlockPos> leafQueue = new ArrayDeque<>();
        List<BlockPos> sources = new ArrayList<>(logs);
        sources.add(broken);
        for (BlockPos source : sources) {
            for (BlockPos near : BlockPos.betweenClosed(source.offset(-1, -1, -1), source.offset(1, 1, 1))) {
                if (leafSet.add(near.immutable())) {
                    leafQueue.add(near.immutable());
                }
            }
        }
        int steps = 0;
        while (!leafQueue.isEmpty() && leaves.size() < MAX_LEAVES && steps++ < 4000) {
            BlockPos at = leafQueue.poll();
            if (!area.contains(at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D) || !isNaturalLeaf(level.getBlockState(at))) {
                continue;
            }
            leaves.add(at);
            for (BlockPos near : new BlockPos[] {at.above(), at.below(), at.north(), at.south(), at.east(), at.west()}) {
                if (leafSet.add(near)) {
                    leafQueue.add(near);
                }
            }
        }
        List<BlockPos> result = new ArrayList<>();
        result.add(broken);
        result.addAll(logs);
        result.addAll(leaves);
        return result;
    }


    /** True if some natural log is still near enough to hold this leaf on. */
    private static boolean heldUpByAnotherLog(ServerLevel level, BlockPos leaf) {
        for (BlockPos near : BlockPos.betweenClosed(leaf.offset(-HOLD_RADIUS, -HOLD_RADIUS, -HOLD_RADIUS), leaf.offset(HOLD_RADIUS, HOLD_RADIUS, HOLD_RADIUS))) {
            if (isNaturalLog(level.getBlockState(near))) {
                return true;
            }
        }
        return false;
    }

    /** Take one block: what it drops goes into the hive, and it is gone. */
    private static boolean take(ServerLevel level, HiveHeart heart, BlockPos pos, @Nullable Entity breaker, ItemStack tool, boolean withSound) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        HiveDrops.store(level, heart, pos, state, level.getBlockEntity(pos), breaker, tool);
        if (withSound) {
            level.destroyBlock(pos, false, breaker);
        } else {
            // A tree has a great many leaves: no sound or particles for each, or the game would stutter.
            level.removeBlock(pos, false);
        }
        return true;
    }
}
