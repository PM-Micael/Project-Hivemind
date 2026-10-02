package com.projecthivemind.build;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A classic staircase dug down into the ground, with no stairs in it: one block forward and one down at every step, three
 * blocks high so there is room to walk it. It starts at the block it was ordered on and goes on in one of the four directions
 * until the step's lowest block is at the stop height. With {@code torches} the workers also stand a torch on every few steps.
 *
 * <p>Like the tower plans this is only a recipe: whether a step is done is always read from the world, so a worker that is
 * interrupted, or the game closed, carries on exactly where it left off.
 */
public record StairDig(BlockPos start, Direction direction, int stopY, boolean torches) {
    /** How many blocks high each step is cleared: the one it goes down to, and two more for head room. */
    private static final int HEIGHT = 3;
    /** A torch goes on every this many steps, starting with the first. */
    private static final int TORCH_EVERY = 6;

    /** True for a block of the staircase that still has to be dug: not air, not a fluid, and not something that cannot be broken. */
    private static boolean needsDig(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /** The next block still to dig, as a dig order, or null when the whole staircase is dug. Blocks that cannot be dug are skipped. */
    @Nullable
    public UnitAction nextDig(ServerLevel level) {
        for (int step = 0; ; step++) {
            BlockPos floor = start.relative(direction, step).below(step);
            if (floor.getY() < stopY || floor.getY() <= level.getMinBuildHeight()) {
                return null;
            }
            // From the top down, so that what is above a block never rests on a hole.
            for (int up = HEIGHT - 1; up >= 0; up--) {
                BlockPos pos = floor.above(up);
                if (level.isLoaded(pos) && needsDig(level, pos, level.getBlockState(pos))) {
                    return new UnitAction(UnitAction.Kind.DIG, pos);
                }
            }
        }
    }

    /**
     * The first step, in order, that is dug out but has nothing solid to stand on: the block under its floor is air, a fluid or the
     * like. That is what a staircase finds when it breaks into a cave. Returns the step's floor cell, so the block to fill is the one
     * under it; null if every dug step has ground. Only steps up to the first one still to dig are looked at.
     */
    @Nullable
    public BlockPos nextFill(ServerLevel level) {
        for (int step = 0; ; step++) {
            BlockPos floor = start.relative(direction, step).below(step);
            if (floor.getY() < stopY || floor.getY() <= level.getMinBuildHeight() + 1 || !level.isLoaded(floor)) {
                return null;
            }
            for (int up = 0; up < HEIGHT; up++) {
                BlockPos pos = floor.above(up);
                if (needsDig(level, pos, level.getBlockState(pos))) {
                    return null;
                }
            }
            BlockPos under = floor.below();
            BlockState state = level.getBlockState(under);
            if (!state.isFaceSturdy(level, under, Direction.UP) && state.canBeReplaced()) {
                return floor;
            }
        }
    }

    /**
     * Where the next torch goes: the floor of the first torch step that is dug out and has no torch yet, or null. Only steps that
     * are completely dug are looked at, in order, so torches never get ahead of the digging.
     */
    @Nullable
    public BlockPos nextTorch(ServerLevel level) {
        for (int step = 0; ; step++) {
            BlockPos floor = start.relative(direction, step).below(step);
            if (floor.getY() < stopY || floor.getY() <= level.getMinBuildHeight() || !level.isLoaded(floor)) {
                return null;
            }
            for (int up = 0; up < HEIGHT; up++) {
                BlockPos pos = floor.above(up);
                if (needsDig(level, pos, level.getBlockState(pos))) {
                    return null;
                }
            }
            if (step % TORCH_EVERY == 0 && level.getBlockState(floor).isAir()
                    && level.getBlockState(floor.below()).isFaceSturdy(level, floor.below(), Direction.UP)) {
                return floor;
            }
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Start", NbtUtils.writeBlockPos(start));
        tag.putInt("Direction", direction.get2DDataValue());
        tag.putInt("StopY", stopY);
        tag.putBoolean("Torches", torches);
        return tag;
    }

    @Nullable
    public static StairDig load(CompoundTag tag) {
        return NbtUtils.readBlockPos(tag, "Start")
                .map(pos -> new StairDig(pos, Direction.from2DDataValue(tag.getInt("Direction")), tag.getInt("StopY"), tag.getBoolean("Torches")))
                .orElse(null);
    }
}
