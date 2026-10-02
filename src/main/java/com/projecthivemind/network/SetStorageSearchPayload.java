package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: what the player typed in the storage search box of the open menu (empty to show everything). */
public record SetStorageSearchPayload(int containerId, String text) implements CustomPacketPayload {
    public static final Type<SetStorageSearchPayload> TYPE = new Type<>(ProjectHivemind.id("set_storage_search"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetStorageSearchPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetStorageSearchPayload::containerId,
            ByteBufCodecs.stringUtf8(64), SetStorageSearchPayload::text,
            SetStorageSearchPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
