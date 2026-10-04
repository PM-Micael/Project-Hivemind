package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: put these workers on the construction whose block is here (the "Work" option). The server checks they are the sender's workers. */
public record AssignConstructionPayload(List<Integer> unitIds, BlockPos pos) implements CustomPacketPayload {
    public static final Type<AssignConstructionPayload> TYPE = new Type<>(ProjectHivemind.id("assign_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssignConstructionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), AssignConstructionPayload::unitIds,
            BlockPos.STREAM_CODEC, AssignConstructionPayload::pos,
            AssignConstructionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
