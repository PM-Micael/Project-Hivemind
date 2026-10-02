package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: open the sign editor for the sign a scout has just put up. */
public record OpenSignPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<OpenSignPayload> TYPE = new Type<>(ProjectHivemind.id("open_sign"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSignPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenSignPayload::pos,
            OpenSignPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
