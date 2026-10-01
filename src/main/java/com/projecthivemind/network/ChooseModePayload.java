package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player picked Steve (false) or Hivemind (true). */
public record ChooseModePayload(boolean hivemind) implements CustomPacketPayload {
    public static final Type<ChooseModePayload> TYPE = new Type<>(ProjectHivemind.id("choose_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChooseModePayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, ChooseModePayload::hivemind, ChooseModePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
