package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: buy the offer at this index from the villager a scout is trading with. */
public record TradePayload(int containerId, int offerIndex) implements CustomPacketPayload {
    public static final Type<TradePayload> TYPE = new Type<>(ProjectHivemind.id("trade"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TradePayload::containerId,
            ByteBufCodecs.VAR_INT, TradePayload::offerIndex,
            TradePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
