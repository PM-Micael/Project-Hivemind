package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: choose the item one worker puts in composters (an item name; empty for none). */
public record SetWorkerCompostPayload(int unitId, String item) implements CustomPacketPayload {
    public static final Type<SetWorkerCompostPayload> TYPE = new Type<>(ProjectHivemind.id("set_worker_compost"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetWorkerCompostPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetWorkerCompostPayload::unitId,
            ByteBufCodecs.STRING_UTF8, SetWorkerCompostPayload::item,
            SetWorkerCompostPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
