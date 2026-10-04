package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: consume the item in the evolve slot of the open hive menu, completing its task. The server checks the item is valid. */
public record ConsumeEvolvePayload(int containerId) implements CustomPacketPayload {
    public static final Type<ConsumeEvolvePayload> TYPE = new Type<>(ProjectHivemind.id("consume_evolve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsumeEvolvePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConsumeEvolvePayload::containerId,
            ConsumeEvolvePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
