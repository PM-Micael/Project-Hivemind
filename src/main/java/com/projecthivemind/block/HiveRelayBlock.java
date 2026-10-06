package com.projecthivemind.block;

import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A hive relay: where the Heart meets redstone. The Heart is not a block, so it cannot power or be powered itself; a relay placed inside its border
 * does it for it (once the hive has consumed a redstone block). An output relay gives a full signal like a block of redstone while its condition
 * holds (see {@link RelayChannel}); the input relay takes a signal from what is next to it and, when one comes, tells the Heart.
 */
public class HiveRelayBlock extends Block {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** How often an output relay looks at the Heart again, in ticks. */
    private static final int INTERVAL = 10;

    private final RelayChannel channel;

    public HiveRelayBlock(Properties properties, RelayChannel channel) {
        super(properties);
        this.channel = channel;
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    public RelayChannel channel() {
        return channel;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return channel.output();
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return channel.output() && state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && channel.output() && !oldState.is(this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!channel.output()) {
            return;
        }
        HiveHeart heart = HiveHeart.redstoneHeartAt(level, pos);
        boolean on = heart != null && heart.signal(channel);
        if (on != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, on), Block.UPDATE_ALL);
        }
        level.scheduleTick(pos, this, INTERVAL);
    }

    /** The input relay: a signal reaching it (from the side it did not have one before) calls the soldiers back. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean movedByPiston) {
        if (level.isClientSide || channel != RelayChannel.RECALL) {
            return;
        }
        boolean signal = level.hasNeighborSignal(pos);
        if (signal != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, signal), Block.UPDATE_CLIENTS);
            if (signal) {
                HiveHeart heart = HiveHeart.redstoneHeartAt(level, pos);
                if (heart != null) {
                    heart.recallSoldiers();
                }
            }
        }
    }
}
