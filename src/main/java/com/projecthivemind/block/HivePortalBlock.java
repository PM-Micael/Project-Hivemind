package com.projecthivemind.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The hive portal: one solid block with a faint glow and drifting spores. It has no behaviour of its own: the Hive Heart keeps the list
 * of portals and the summoning (see HivePortals), and forgets a portal whose block is gone.
 */
public class HivePortalBlock extends Block {
    public HivePortalBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.CRIMSON_SPORE, pos.getX() + random.nextDouble(), pos.getY() + 1.0D, pos.getZ() + random.nextDouble(),
                    0.0D, 0.03D, 0.0D);
        }
        if (random.nextInt(8) == 0) {
            level.addParticle(ParticleTypes.PORTAL, pos.getX() + random.nextDouble(), pos.getY() + 0.5D + random.nextDouble(), pos.getZ() + random.nextDouble(),
                    (random.nextDouble() - 0.5D) * 0.5D, (random.nextDouble() - 0.5D) * 0.5D, (random.nextDouble() - 0.5D) * 0.5D);
        }
    }
}
