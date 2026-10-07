package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: move every full container in the hive's storage into the Fluids tab's input slots. */
public record PullFluidsPayload(int containerId) implements CustomPacketPayload {
    public static final Type<PullFluidsPayload> TYPE = new Type<>(ProjectHivemind.id("pull_fluids"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PullFluidsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PullFluidsPayload::containerId,
            PullFluidsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
