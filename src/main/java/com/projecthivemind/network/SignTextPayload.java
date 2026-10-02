package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the text written on the sign a scout put up (four lines). */
public record SignTextPayload(BlockPos pos, List<String> lines) implements CustomPacketPayload {
    public static final Type<SignTextPayload> TYPE = new Type<>(ProjectHivemind.id("sign_text"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SignTextPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SignTextPayload::pos,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(4)), SignTextPayload::lines,
            SignTextPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
