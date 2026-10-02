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
 * Client to server: the selected scout uses the item in its hand on this face of this block (places it, reads it,
 * throws it...).
 *
 * @param unitIds the selected units, by entity id; the server picks the player's scout among them
 * @param pos     the block clicked
 * @param face    the face of it that was clicked
 */
public record ScoutUsePayload(List<Integer> unitIds, BlockPos pos, Direction face) implements CustomPacketPayload {
    public static final Type<ScoutUsePayload> TYPE = new Type<>(ProjectHivemind.id("scout_use"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScoutUsePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), ScoutUsePayload::unitIds,
            BlockPos.STREAM_CODEC, ScoutUsePayload::pos,
            Direction.STREAM_CODEC, ScoutUsePayload::face,
            ScoutUsePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
