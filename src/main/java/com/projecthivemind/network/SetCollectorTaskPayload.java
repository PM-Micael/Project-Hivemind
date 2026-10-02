package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: set up one of a collector's planting tasks. {@code op} 0 sets the item (an item name; empty for none), 1 adds a
 * soil block it plants on, 2 clears all of them, 3 removes one; add 10 to any for saplings instead of crops. The server checks the
 * collector is the sender's and the block is inside the
 * hive area.
 */
public record SetCollectorTaskPayload(int unitId, int op, String seed, BlockPos pos) implements CustomPacketPayload {
    public static final Type<SetCollectorTaskPayload> TYPE = new Type<>(ProjectHivemind.id("set_collector_task"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectorTaskPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectorTaskPayload::unitId,
            ByteBufCodecs.VAR_INT, SetCollectorTaskPayload::op,
            ByteBufCodecs.STRING_UTF8, SetCollectorTaskPayload::seed,
            BlockPos.STREAM_CODEC, SetCollectorTaskPayload::pos,
            SetCollectorTaskPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
