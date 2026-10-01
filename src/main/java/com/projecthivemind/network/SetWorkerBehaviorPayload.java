package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the worker behaviour settings from the menu's Behavior tab (see WorkerBehavior). */
public record SetWorkerBehaviorPayload(int flags, int unitAreaRadius) implements CustomPacketPayload {
    public static final Type<SetWorkerBehaviorPayload> TYPE = new Type<>(ProjectHivemind.id("set_worker_behavior"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetWorkerBehaviorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetWorkerBehaviorPayload::flags,
            ByteBufCodecs.VAR_INT, SetWorkerBehaviorPayload::unitAreaRadius,
            SetWorkerBehaviorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
