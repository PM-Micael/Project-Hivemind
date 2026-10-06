package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player clicked this enchantment (its id) on the Evolve tab: a book with it in the hive's storage is consumed to make it available. The server checks everything. */
public record ConsumeEnchantPayload(int containerId, String enchantment) implements CustomPacketPayload {
    public static final Type<ConsumeEnchantPayload> TYPE = new Type<>(ProjectHivemind.id("consume_enchant"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConsumeEnchantPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ConsumeEnchantPayload::containerId,
            ByteBufCodecs.STRING_UTF8, ConsumeEnchantPayload::enchantment,
            ConsumeEnchantPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
