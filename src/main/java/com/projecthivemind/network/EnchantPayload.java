package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player chose one of the three options (0 to 2) of the hive's enchanting station. The server checks the cost. */
public record EnchantPayload(int containerId, int option) implements CustomPacketPayload {
    public static final Type<EnchantPayload> TYPE = new Type<>(ProjectHivemind.id("enchant"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EnchantPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EnchantPayload::containerId,
            ByteBufCodecs.VAR_INT, EnchantPayload::option,
            EnchantPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
