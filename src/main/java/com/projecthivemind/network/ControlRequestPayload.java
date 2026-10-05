package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: take control of this scout (its entity id), or, with -1, let go of the one being controlled. */
public record ControlRequestPayload(int scoutId) implements CustomPacketPayload {
    public static final Type<ControlRequestPayload> TYPE = new Type<>(ProjectHivemind.id("control_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ControlRequestPayload::scoutId,
            ControlRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
