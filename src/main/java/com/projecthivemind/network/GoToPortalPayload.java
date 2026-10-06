package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: bring the camera to the portal at this index of the hive's list (the Go button on the Portals tab). */
public record GoToPortalPayload(int index) implements CustomPacketPayload {
    public static final Type<GoToPortalPayload> TYPE = new Type<>(ProjectHivemind.id("go_to_portal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GoToPortalPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GoToPortalPayload::index,
            GoToPortalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
