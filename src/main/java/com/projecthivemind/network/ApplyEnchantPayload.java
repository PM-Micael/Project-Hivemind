package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player picked this available enchantment (its id) for the item in the hive's enchanting station. The server checks everything. */
public record ApplyEnchantPayload(int containerId, String enchantment) implements CustomPacketPayload {
    public static final Type<ApplyEnchantPayload> TYPE = new Type<>(ProjectHivemind.id("apply_enchant"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ApplyEnchantPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ApplyEnchantPayload::containerId,
            ByteBufCodecs.STRING_UTF8, ApplyEnchantPayload::enchantment,
            ApplyEnchantPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
