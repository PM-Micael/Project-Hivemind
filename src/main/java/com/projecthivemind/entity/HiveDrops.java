package com.projecthivemind.entity;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Where the drops of a block a worker breaks go: straight into the hive's inventory, instead of onto the ground. */
public final class HiveDrops {
    private HiveDrops() {
    }

    /**
     * Put what the block would drop into the hive's storage. What does not fit is dropped in the world as usual, so nothing is
     * lost to a full hive. Call this before the block is removed (the drops are worked out from the block as it stands).
     */
    public static void store(ServerLevel level, HiveHeart heart, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity,
            @Nullable Entity breaker, ItemStack tool) {
        for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, breaker, tool)) {
            ItemStack leftover = heart.getStorage().addItem(drop);
            if (!leftover.isEmpty()) {
                Block.popResource(level, pos, leftover);
            }
        }
        storeExperience(level, heart, pos, state, tool);
    }
    /**
     * The experience a block gives when it is mined (ores, spawners...) goes to the hivemind's experience bar. The block is asked to spawn it as it would for a
     * player (silk touch gives none), and the orbs that come out are taken at once.
     */
    private static void storeExperience(ServerLevel level, HiveHeart heart, BlockPos pos, BlockState state, ItemStack tool) {
        if (heart.ownerId() == null || level.getServer() == null) {
            return;
        }
        net.minecraft.server.level.ServerPlayer owner = level.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null) {
            return;
        }
        state.spawnAfterBreak(level, pos, tool, true);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos).inflate(1.0D);
        for (net.minecraft.world.entity.ExperienceOrb orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box, net.minecraft.world.entity.Entity::isAlive)) {
            com.projecthivemind.HivemindManager.giveHiveExperience(owner, heart, orb.getValue());
            orb.discard();
        }
    }
}
