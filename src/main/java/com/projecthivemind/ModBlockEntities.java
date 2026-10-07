package com.projecthivemind;

import com.projecthivemind.block.DehydratorBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ProjectHivemind.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DehydratorBlockEntity>> DEHYDRATOR = BLOCK_ENTITY_TYPES.register("dehydrator",
            () -> BlockEntityType.Builder.of(DehydratorBlockEntity::new, ModBlocks.DEHYDRATOR.get()).build(null));

    private ModBlockEntities() {
    }
}
