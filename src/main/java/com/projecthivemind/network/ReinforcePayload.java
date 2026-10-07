package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: teleport every soldier to the Hive Heart (needs the Eye of Ender task). */
public record ReinforcePayload() implements CustomPacketPayload {
    public static final Type<ReinforcePayload> TYPE = new Type<>(ProjectHivemind.id("reinforce"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ReinforcePayload> STREAM_CODEC =
            StreamCodec.unit(new ReinforcePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
