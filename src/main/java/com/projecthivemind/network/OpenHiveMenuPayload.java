package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player pressed the inventory key and wants the hive menu. */
public record OpenHiveMenuPayload() implements CustomPacketPayload {
    public static final Type<OpenHiveMenuPayload> TYPE = new Type<>(ProjectHivemind.id("open_hive_menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenHiveMenuPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenHiveMenuPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
