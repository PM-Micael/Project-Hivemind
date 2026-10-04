package com.projecthivemind.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block that marks a construction (a bridge, a staircase down, a tower or a shaft): it is placed where the work starts, and the hive's Heart
 * keeps what the construction is, which workers are on it and how far it has got (see Constructions). The block is only half a block high, so
 * nothing standing where it is placed is shut in. It drops nothing, and if it is broken the construction is dropped with it.
 */
public class ConstructionBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 8.0D, 16.0D);

    public ConstructionBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
