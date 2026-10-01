package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the soldier behaviour settings from the menu's Behavior tab (see SoldierBehavior). */
public record SetBehaviorPayload(int flags, int unitAreaRadius) implements CustomPacketPayload {
    public static final Type<SetBehaviorPayload> TYPE = new Type<>(ProjectHivemind.id("set_behavior"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetBehaviorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetBehaviorPayload::flags,
            ByteBufCodecs.VAR_INT, SetBehaviorPayload::unitAreaRadius,
            SetBehaviorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
