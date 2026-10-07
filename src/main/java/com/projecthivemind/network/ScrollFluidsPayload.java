package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: show the Fluids tab's columns from this one on. */
public record ScrollFluidsPayload(int containerId, int column) implements CustomPacketPayload {
    public static final Type<ScrollFluidsPayload> TYPE = new Type<>(ProjectHivemind.id("scroll_fluids"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScrollFluidsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ScrollFluidsPayload::containerId,
            ByteBufCodecs.VAR_INT, ScrollFluidsPayload::column,
            ScrollFluidsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
