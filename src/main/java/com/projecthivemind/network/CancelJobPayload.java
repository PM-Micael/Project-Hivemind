package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: end this unit's job for good (the player confirmed the cancel button on its page). */
public record CancelJobPayload(int unitId) implements CustomPacketPayload {
    public static final Type<CancelJobPayload> TYPE = new Type<>(ProjectHivemind.id("cancel_job"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CancelJobPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CancelJobPayload::unitId,
            CancelJobPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
