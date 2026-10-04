package com.projecthivemind;

import com.projecthivemind.block.ConstructionBlock;
import com.projecthivemind.block.HivePortalBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ProjectHivemind.MODID);

    /** The hive portal a scout places (from hive level 3): units are summoned through it. It has no item and drops nothing. */
    public static final DeferredBlock<Block> HIVE_PORTAL = BLOCKS.register("hive_portal",
            () -> new HivePortalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.CRIMSON_NYLIUM)
                    .strength(3.0F)
                    .sound(SoundType.SCULK_CATALYST)
                    .lightLevel(state -> 10)));

    /** The block that marks a construction (see ConstructionBlock). Placed by the hive, no item, drops nothing. */
    public static final DeferredBlock<Block> CONSTRUCTION = BLOCKS.register("construction",
            () -> new ConstructionBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(2.0F)
                    .sound(SoundType.SCAFFOLDING)
                    .noOcclusion()));

    private ModBlocks() {
    }
}
