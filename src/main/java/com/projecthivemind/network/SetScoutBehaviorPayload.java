package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the scout behaviour settings from the menu's Behavior tab (see ScoutBehavior). */
public record SetScoutBehaviorPayload(int flags, int unitAreaRadius) implements CustomPacketPayload {
    public static final Type<SetScoutBehaviorPayload> TYPE = new Type<>(ProjectHivemind.id("set_scout_behavior"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetScoutBehaviorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetScoutBehaviorPayload::flags,
            ByteBufCodecs.VAR_INT, SetScoutBehaviorPayload::unitAreaRadius,
            SetScoutBehaviorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
