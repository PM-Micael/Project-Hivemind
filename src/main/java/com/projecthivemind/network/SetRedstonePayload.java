package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the Redstone tab's settings (bits 0 to 3: hostile mob, any mob, health, recall) and the health percentage. The server checks them. */
public record SetRedstonePayload(int containerId, int flags, int percent) implements CustomPacketPayload {
    public static final Type<SetRedstonePayload> TYPE = new Type<>(ProjectHivemind.id("set_redstone"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRedstonePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetRedstonePayload::containerId,
            ByteBufCodecs.VAR_INT, SetRedstonePayload::flags,
            ByteBufCodecs.VAR_INT, SetRedstonePayload::percent,
            SetRedstonePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
