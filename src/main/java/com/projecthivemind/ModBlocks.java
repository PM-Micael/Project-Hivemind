package com.projecthivemind;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ProjectHivemind.MODID);

    // Placeholder: plain block that reuses the vanilla nether wart block texture until we have real art.
    public static final DeferredBlock<Block> HIVE_HEART = BLOCKS.registerSimpleBlock("hive_heart",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(5.0F, 1200.0F)
                    .sound(SoundType.WART_BLOCK)
                    .lightLevel(state -> 7)
                    .noLootTable());

    private ModBlocks() {
    }
}
