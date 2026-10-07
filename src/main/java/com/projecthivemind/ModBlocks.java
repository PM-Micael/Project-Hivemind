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

    /** The hive portal a scout places (from hive level 2): units are summoned through it. It has no item and drops nothing. */
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

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ProjectHivemind.MODID);

    /** Blast resistance of the creep blocks: as high as bedrock's, so explosions (creepers, TNT, and the rest) never destroy them. */
    private static final float BLAST_PROOF = 3600000.0F;

    /** The block the Heart's creep is made of: it spreads under the hive area, turning what is there (and filling what is empty) into this. */
    public static final DeferredBlock<Block> CREEP_BLOCK = BLOCKS.register("creep_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.CRIMSON_NYLIUM)
                    .strength(1.0F, BLAST_PROOF)
                    .sound(SoundType.NETHER_WART)));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> CREEP_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(CREEP_BLOCK);

    /** The creep takes a form from what it consumes: dirt, grass and stone each have their own, and anything else (or empty space) is the plain one. */
    public static final DeferredBlock<Block> CREEP_DIRT = BLOCKS.register("creep_dirt",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.CRIMSON_NYLIUM).strength(0.8F, BLAST_PROOF).sound(SoundType.NETHER_WART)));
    public static final DeferredBlock<Block> CREEP_GRASS = BLOCKS.register("creep_grass",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.CRIMSON_NYLIUM).strength(0.9F, BLAST_PROOF).sound(SoundType.NETHER_WART)));
    public static final DeferredBlock<Block> CREEP_STONE = BLOCKS.register("creep_stone",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.CRIMSON_NYLIUM).strength(2.0F, BLAST_PROOF).sound(SoundType.NETHER_WART)));
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> CREEP_DIRT_ITEM = ITEMS.registerSimpleBlockItem(CREEP_DIRT);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> CREEP_GRASS_ITEM = ITEMS.registerSimpleBlockItem(CREEP_GRASS);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> CREEP_STONE_ITEM = ITEMS.registerSimpleBlockItem(CREEP_STONE);

    /** Every kind of creep block. */
    public static final net.minecraft.tags.TagKey<Block> CREEP = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, ProjectHivemind.id("creep"));

    /** The creep block that a block turns into. */
    public static Block creepFor(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || state.is(net.minecraft.world.level.block.Blocks.PODZOL)
                || state.is(net.minecraft.world.level.block.Blocks.MYCELIUM)) {
            return CREEP_GRASS.get();
        }
        if (state.is(net.minecraft.world.level.block.Blocks.DIRT) || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT) || state.is(net.minecraft.world.level.block.Blocks.MUD)) {
            return CREEP_DIRT.get();
        }
        if (state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                || state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE) || state.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)) {
            return CREEP_STONE.get();
        }
        return CREEP_BLOCK.get();
    }

    /** The hive relays: where the Heart gives and takes redstone signals (see HiveRelayBlock). One block for each thing a relay does. */
    private static DeferredBlock<Block> relay(com.projecthivemind.block.RelayChannel channel) {
        return BLOCKS.register("hive_relay_" + channel.id(), () -> new com.projecthivemind.block.HiveRelayBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.CRIMSON_NYLIUM)
                .strength(1.5F)
                .sound(SoundType.NETHER_WART)
                .lightLevel(state -> state.getValue(com.projecthivemind.block.HiveRelayBlock.POWERED) ? 7 : 0), channel));
    }

    public static final DeferredBlock<Block> RELAY_HOSTILE = relay(com.projecthivemind.block.RelayChannel.HOSTILE);
    public static final DeferredBlock<Block> RELAY_ANY = relay(com.projecthivemind.block.RelayChannel.ANY);
    public static final DeferredBlock<Block> RELAY_HEALTH = relay(com.projecthivemind.block.RelayChannel.HEALTH);
    public static final DeferredBlock<Block> RELAY_RECALL = relay(com.projecthivemind.block.RelayChannel.RECALL);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> RELAY_HOSTILE_ITEM = ITEMS.registerSimpleBlockItem(RELAY_HOSTILE);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> RELAY_ANY_ITEM = ITEMS.registerSimpleBlockItem(RELAY_ANY);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> RELAY_HEALTH_ITEM = ITEMS.registerSimpleBlockItem(RELAY_HEALTH);
    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> RELAY_RECALL_ITEM = ITEMS.registerSimpleBlockItem(RELAY_RECALL);

    private ModBlocks() {
    }
}
