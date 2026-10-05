package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: these workers build a small cobblestone generator on top of this block. The server checks they are the sender's and the site is fit. */
public record BuildGeneratorPayload(List<Integer> unitIds, BlockPos site) implements CustomPacketPayload {
    public static final Type<BuildGeneratorPayload> TYPE = new Type<>(ProjectHivemind.id("build_generator"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildGeneratorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), BuildGeneratorPayload::unitIds,
            BlockPos.STREAM_CODEC, BuildGeneratorPayload::site,
            BuildGeneratorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
