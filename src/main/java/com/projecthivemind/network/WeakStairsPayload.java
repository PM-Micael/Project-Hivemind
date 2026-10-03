package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the hive's tools are too weak to collect what a staircase digs through. The client asks the player whether to dig
 * anyway, and if so sends the same staircase order again with {@link DigStaircasePayload#confirmed()} set.
 */
public record WeakStairsPayload(DigStaircasePayload order) implements CustomPacketPayload {
    public static final Type<WeakStairsPayload> TYPE = new Type<>(ProjectHivemind.id("weak_stairs"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeakStairsPayload> STREAM_CODEC = StreamCodec.composite(
            DigStaircasePayload.STREAM_CODEC, WeakStairsPayload::order,
            WeakStairsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
