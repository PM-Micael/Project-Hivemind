package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: this worker builds the wall round the hive out of this block (an item name; empty to stop). The server checks it is the sender's. */
public record BuildWallPayload(int unitId, String item) implements CustomPacketPayload {
    public static final Type<BuildWallPayload> TYPE = new Type<>(ProjectHivemind.id("build_wall"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildWallPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BuildWallPayload::unitId,
            ByteBufCodecs.STRING_UTF8, BuildWallPayload::item,
            BuildWallPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
