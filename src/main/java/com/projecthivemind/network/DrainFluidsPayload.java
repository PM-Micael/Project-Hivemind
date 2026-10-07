package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: empty every meter of the hive's fluids (the player has confirmed). */
public record DrainFluidsPayload(int containerId) implements CustomPacketPayload {
    public static final Type<DrainFluidsPayload> TYPE = new Type<>(ProjectHivemind.id("drain_fluids"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DrainFluidsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DrainFluidsPayload::containerId,
            DrainFluidsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
