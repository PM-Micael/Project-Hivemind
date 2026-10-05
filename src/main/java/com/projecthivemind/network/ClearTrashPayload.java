package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: delete what is in the trash slot of the open hive menu. */
public record ClearTrashPayload(int containerId) implements CustomPacketPayload {
    public static final Type<ClearTrashPayload> TYPE = new Type<>(ProjectHivemind.id("clear_trash"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClearTrashPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ClearTrashPayload::containerId,
            ClearTrashPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
