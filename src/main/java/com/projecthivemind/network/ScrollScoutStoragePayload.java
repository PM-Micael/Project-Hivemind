package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: scroll the window onto the storage on the scouts' page of the open hive menu so it starts at this row. */
public record ScrollScoutStoragePayload(int containerId, int row) implements CustomPacketPayload {
    public static final Type<ScrollScoutStoragePayload> TYPE = new Type<>(ProjectHivemind.id("scroll_scout_storage"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScrollScoutStoragePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ScrollScoutStoragePayload::containerId,
            ByteBufCodecs.VAR_INT, ScrollScoutStoragePayload::row,
            ScrollScoutStoragePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
