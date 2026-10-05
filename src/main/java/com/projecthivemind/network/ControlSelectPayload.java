package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player picked this slot (0 to 8) of the hotbar of the scout they control. */
public record ControlSelectPayload(int slot) implements CustomPacketPayload {
    public static final Type<ControlSelectPayload> TYPE = new Type<>(ProjectHivemind.id("control_select"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlSelectPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ControlSelectPayload::slot,
            ControlSelectPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
