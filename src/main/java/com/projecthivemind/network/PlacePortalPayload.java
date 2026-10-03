package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the selected scout places a hive portal on this face of this block.
 *
 * @param confirmed the player has already accepted the question about replacing the portal that stands
 */
public record PlacePortalPayload(List<Integer> unitIds, BlockPos pos, Direction face, boolean confirmed) implements CustomPacketPayload {
    public static final Type<PlacePortalPayload> TYPE = new Type<>(ProjectHivemind.id("place_portal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlacePortalPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), PlacePortalPayload::unitIds,
            BlockPos.STREAM_CODEC, PlacePortalPayload::pos,
            Direction.STREAM_CODEC, PlacePortalPayload::face,
            ByteBufCodecs.BOOL, PlacePortalPayload::confirmed,
            PlacePortalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
