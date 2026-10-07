package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: a selected scout is to place a dehydrator against this face of this block. */
public record PlaceDehydratorPayload(List<Integer> unitIds, BlockPos pos, Direction face) implements CustomPacketPayload {
    public static final Type<PlaceDehydratorPayload> TYPE = new Type<>(ProjectHivemind.id("place_dehydrator"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceDehydratorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), PlaceDehydratorPayload::unitIds,
            BlockPos.STREAM_CODEC, PlaceDehydratorPayload::pos,
            Direction.STREAM_CODEC, PlaceDehydratorPayload::face,
            PlaceDehydratorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
