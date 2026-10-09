package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: forget the location at this index of the hive's list. */
public record DeleteLocationPayload(int index) implements CustomPacketPayload {
    public static final Type<DeleteLocationPayload> TYPE = new Type<>(ProjectHivemind.id("delete_location"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteLocationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DeleteLocationPayload::index,
            DeleteLocationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
