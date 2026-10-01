package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the hive's tools are too weak to collect this block. The client asks the player whether to dig
 * anyway, and if so repeats the dig command with {@link BlockActionPayload#confirmed()} set.
 */
public record WeakToolPayload(List<Integer> unitIds, BlockPos pos) implements CustomPacketPayload {
    public static final Type<WeakToolPayload> TYPE = new Type<>(ProjectHivemind.id("weak_tool"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeakToolPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), WeakToolPayload::unitIds,
            BlockPos.STREAM_CODEC, WeakToolPayload::pos,
            WeakToolPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
