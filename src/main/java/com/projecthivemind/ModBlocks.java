package com.projecthivemind;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ProjectHivemind.MODID);

    // Placeholder look: reuses the vanilla nether wart block texture until we have real art.
    public static final DeferredBlock<HiveCreepBlock> HIVE_CREEP = BLOCKS.registerBlock("hive_creep", HiveCreepBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(0.1F)
                    .sound(SoundType.WART_BLOCK)
                    .noOcclusion()
                    .replaceable()
                    .noLootTable()
                    .pushReaction(PushReaction.DESTROY));

    private ModBlocks() {
    }
}
