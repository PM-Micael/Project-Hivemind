package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: scroll the hive storage in the open menu so it starts at this row. */
public record ScrollStoragePayload(int containerId, int row) implements CustomPacketPayload {
    public static final Type<ScrollStoragePayload> TYPE = new Type<>(ProjectHivemind.id("scroll_storage"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScrollStoragePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ScrollStoragePayload::containerId,
            ByteBufCodecs.VAR_INT, ScrollStoragePayload::row,
            ScrollStoragePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
