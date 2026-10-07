package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: these workers dig a tunnel from this block, running in a direction (a horizontal direction's 2D data value) for {@code length} blocks,
 * (bit 2 of the direction: the tunnel goes on until it is cancelled) of size {@code size} (1 to 4: 1x2, 2x2, 3x3, 5x5), laying {@code block} (an item name) where its floor is missing. The server checks all of it.
 */
public record BuildTunnelPayload(List<Integer> unitIds, BlockPos site, int direction, int size, int length, String block) implements CustomPacketPayload {
    public static final Type<BuildTunnelPayload> TYPE = new Type<>(ProjectHivemind.id("build_tunnel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildTunnelPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), BuildTunnelPayload::unitIds,
            BlockPos.STREAM_CODEC, BuildTunnelPayload::site,
            ByteBufCodecs.VAR_INT, BuildTunnelPayload::direction,
            ByteBufCodecs.VAR_INT, BuildTunnelPayload::size,
            ByteBufCodecs.VAR_INT, BuildTunnelPayload::length,
            ByteBufCodecs.STRING_UTF8, BuildTunnelPayload::block,
            BuildTunnelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
