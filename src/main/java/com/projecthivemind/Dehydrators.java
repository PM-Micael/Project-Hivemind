package com.projecthivemind;

import com.projecthivemind.block.DehydratorBlockEntity;
import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/** Placing a dehydrator (see DehydratorBlockEntity for what it does afterwards). */
public final class Dehydrators {
    private Dehydrators() {
    }

    /** Why a dehydrator cannot go on this face of this block, as a message key; null if it can. */
    public static String problem(ServerLevel level, HiveHeart heart, BlockPos clicked, Direction face) {
        if (!heart.dehydratorsAllowed()) {
            return "message.projecthivemind.dehydrator_locked";
        }
        BlockPos target = clicked.relative(face);
        // The dehydrator goes in the space against the clicked face: air, a plant, or a fluid it takes the place of.
        if (!level.getBlockState(target).canBeReplaced()) {
            return "message.projecthivemind.dehydrator_no_room";
        }
        if (!DehydratorBlockEntity.convertible(level, clicked)) {
            return "message.projecthivemind.dehydrator_not_solid";
        }
        if (!DehydratorBlockEntity.touchesFluid(level, clicked)) {
            return "message.projecthivemind.dehydrator_no_fluid";
        }
        return null;
    }

    /** A scout has come to place a dehydrator against this face of this block. Returns false if it cannot go there. */
    public static boolean place(ServerLevel level, HiveHeart heart, BlockPos clicked, Direction face) {
        if (problem(level, heart, clicked, face) != null) {
            return false;
        }
        BlockPos target = clicked.relative(face);
        level.setBlock(target, ModBlocks.DEHYDRATOR.get().defaultBlockState(), 3);
        if (DehydratorBlockEntity.at(level, target) != null) {
            DehydratorBlockEntity.at(level, target).begin(level, clicked);
        }
        level.playSound(null, target, SoundEvents.SPONGE_ABSORB, SoundSource.BLOCKS, 1.0F, 0.8F);
        return true;
    }
}
