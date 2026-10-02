package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: these workers dig a staircase down from this block, going the way of horizontal direction {@code direction}
 * (a Direction's 2D data value), until the bottom of a step reaches height {@code stopY}. The server checks they are the sender's.
 */
public record DigStaircasePayload(List<Integer> unitIds, BlockPos pos, int direction, int stopY, boolean torches) implements CustomPacketPayload {
    public static final Type<DigStaircasePayload> TYPE = new Type<>(ProjectHivemind.id("dig_staircase"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DigStaircasePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), DigStaircasePayload::unitIds,
            BlockPos.STREAM_CODEC, DigStaircasePayload::pos,
            ByteBufCodecs.VAR_INT, DigStaircasePayload::direction,
            ByteBufCodecs.VAR_INT, DigStaircasePayload::stopY,
            ByteBufCodecs.BOOL, DigStaircasePayload::torches,
            DigStaircasePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
