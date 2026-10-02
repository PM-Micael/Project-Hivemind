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
 * Client to server: the selected workers place a torch from the hive against this face of this block.
 *
 * @param unitIds the selected units, by entity id; the server picks the player's workers among them
 * @param pos     the block clicked
 * @param face    the face of it that was clicked
 */
public record PlaceTorchPayload(List<Integer> unitIds, BlockPos pos, Direction face) implements CustomPacketPayload {
    public static final Type<PlaceTorchPayload> TYPE = new Type<>(ProjectHivemind.id("place_torch"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceTorchPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), PlaceTorchPayload::unitIds,
            BlockPos.STREAM_CODEC, PlaceTorchPayload::pos,
            Direction.STREAM_CODEC, PlaceTorchPayload::face,
            PlaceTorchPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
