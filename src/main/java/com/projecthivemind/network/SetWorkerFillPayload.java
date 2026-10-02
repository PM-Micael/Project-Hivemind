package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: choose the block one worker fills gaps in the ground with (an item name; empty for none). */
public record SetWorkerFillPayload(int unitId, String item) implements CustomPacketPayload {
    public static final Type<SetWorkerFillPayload> TYPE = new Type<>(ProjectHivemind.id("set_worker_fill"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetWorkerFillPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetWorkerFillPayload::unitId,
            ByteBufCodecs.STRING_UTF8, SetWorkerFillPayload::item,
            SetWorkerFillPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
