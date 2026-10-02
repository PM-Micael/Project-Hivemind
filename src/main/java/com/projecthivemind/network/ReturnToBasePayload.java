package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: this unit goes back to the hive, to the nearest point inside the border. The server checks it is the sender's. */
public record ReturnToBasePayload(int unitId) implements CustomPacketPayload {
    public static final Type<ReturnToBasePayload> TYPE = new Type<>(ProjectHivemind.id("return_to_base"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReturnToBasePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ReturnToBasePayload::unitId,
            ReturnToBasePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
