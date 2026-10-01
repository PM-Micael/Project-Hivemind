package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: move the camera back above the Hive Heart. */
public record ReturnToHeartPayload() implements CustomPacketPayload {
    public static final Type<ReturnToHeartPayload> TYPE = new Type<>(ProjectHivemind.id("return_to_heart"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReturnToHeartPayload> STREAM_CODEC =
            StreamCodec.unit(new ReturnToHeartPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
