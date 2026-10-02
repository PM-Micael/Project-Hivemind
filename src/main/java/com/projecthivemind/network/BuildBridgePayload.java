package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: these workers build a bridge to this block, out of {@code deck}, with fences of type {@code fence} (an item name;
 * empty for none) and, if {@code torches}, torches along it, {@code width} blocks wide (3 to 7). The server checks they are the sender's and that the items are right.
 */
public record BuildBridgePayload(List<Integer> unitIds, BlockPos dest, String deck, String fence, boolean torches, int width) implements CustomPacketPayload {
    public static final Type<BuildBridgePayload> TYPE = new Type<>(ProjectHivemind.id("build_bridge"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildBridgePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), BuildBridgePayload::unitIds,
            BlockPos.STREAM_CODEC, BuildBridgePayload::dest,
            ByteBufCodecs.STRING_UTF8, BuildBridgePayload::deck,
            ByteBufCodecs.STRING_UTF8, BuildBridgePayload::fence,
            ByteBufCodecs.BOOL, BuildBridgePayload::torches,
            ByteBufCodecs.VAR_INT, BuildBridgePayload::width,
            BuildBridgePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
