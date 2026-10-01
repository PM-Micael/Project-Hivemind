package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the collector range setting from the menu's Behavior tab (see CollectorBehavior). */
public record SetCollectorBehaviorPayload(int extraRange) implements CustomPacketPayload {
    public static final Type<SetCollectorBehaviorPayload> TYPE = new Type<>(ProjectHivemind.id("set_collector_behavior"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCollectorBehaviorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetCollectorBehaviorPayload::extraRange,
            SetCollectorBehaviorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
