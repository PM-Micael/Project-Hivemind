package com.projecthivemind.block;

import javax.annotation.Nullable;

import com.projecthivemind.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.serialization.MapCodec;

/**
 * The dehydrator a scout places once the hive has consumed a sponge. It does nothing itself: its block entity turns the ground under it into
 * dehydrated creep, which spreads to blocks that touch fluids and drains them into the hive's fluid meters (see {@link DehydratorBlockEntity}).
 */
public class DehydratorBlock extends BaseEntityBlock {
    public static final MapCodec<DehydratorBlock> CODEC = simpleCodec(DehydratorBlock::new);

    public DehydratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DehydratorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.DEHYDRATOR.get(), DehydratorBlockEntity::tick);
    }
}
