package com.projecthivemind;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, ProjectHivemind.MODID);

    /**
     * Hidden stamp tying a soldier's equipment copy to the original piece in the Hive Heart. Both carry the same id,
     * so durability the copy loses can be charged to exactly that original.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> HIVE_LINK = COMPONENTS.register("hive_link",
            () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    private ModComponents() {
    }
}
