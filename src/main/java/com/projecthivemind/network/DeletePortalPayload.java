package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: take down the portal at this index of the hive's list (the player confirmed it on the portal menu). */
public record DeletePortalPayload(int index) implements CustomPacketPayload {
    public static final Type<DeletePortalPayload> TYPE = new Type<>(ProjectHivemind.id("delete_portal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePortalPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DeletePortalPayload::index,
            DeletePortalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
