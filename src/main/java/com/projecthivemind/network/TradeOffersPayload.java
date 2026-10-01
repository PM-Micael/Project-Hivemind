package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.trading.MerchantOffers;

/** Server to client: the offers of the villager in the open trade menu, sent on opening and after every trade. */
public record TradeOffersPayload(int containerId, MerchantOffers offers) implements CustomPacketPayload {
    public static final Type<TradeOffersPayload> TYPE = new Type<>(ProjectHivemind.id("trade_offers"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TradeOffersPayload> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, TradeOffersPayload::containerId,
            MerchantOffers.STREAM_CODEC, TradeOffersPayload::offers,
            TradeOffersPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
