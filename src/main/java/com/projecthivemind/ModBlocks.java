package com.projecthivemind;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ProjectHivemind.MODID);

    /**
     * Infected ground: a full block that stands in for the ground it consumed. Placeholder look: crimson nylium.
     * It drops nothing when broken; the consumed block is restored only when the Heart is destroyed.
     */
    public static final DeferredBlock<Block> HIVE_CREEP = BLOCKS.registerSimpleBlock("hive_creep",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(0.6F)
                    .sound(SoundType.NYLIUM)
                    .noLootTable());

    private ModBlocks() {
    }
}
