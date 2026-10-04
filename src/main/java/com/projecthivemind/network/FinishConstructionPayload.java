package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player confirmed Finish on the construction whose block is here. The server only does it for a construction that is done. */
public record FinishConstructionPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<FinishConstructionPayload> TYPE = new Type<>(ProjectHivemind.id("finish_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FinishConstructionPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FinishConstructionPayload::pos,
            FinishConstructionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
