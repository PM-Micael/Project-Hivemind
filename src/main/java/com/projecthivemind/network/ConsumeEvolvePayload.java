package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: consume the item for this task (an index of EvolveTask) from the hive's storage, completing it. The server checks the item is there. */
public record ConsumeEvolvePayload(int containerId, int task) implements CustomPacketPayload {
    public static final Type<ConsumeEvolvePayload> TYPE = new Type<>(ProjectHivemind.id("consume_evolve"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsumeEvolvePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConsumeEvolvePayload::containerId,
            ByteBufCodecs.VAR_INT, ConsumeEvolvePayload::task,
            ConsumeEvolvePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
