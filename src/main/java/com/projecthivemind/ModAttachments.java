package com.projecthivemind;

import java.util.function.Supplier;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ProjectHivemind.MODID);

    public static final Supplier<AttachmentType<HivemindData>> HIVEMIND = ATTACHMENT_TYPES.register("hivemind",
            () -> AttachmentType.builder(() -> HivemindData.UNCHOSEN)
                    .serialize(HivemindData.CODEC)
                    .copyOnDeath()
                    .build());

    private ModAttachments() {
    }
}
