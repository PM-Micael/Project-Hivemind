package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player confirmed killing all their units (the Portals tab's safety button, for a unit that was lost somewhere). */
public record KillAllUnitsPayload() implements CustomPacketPayload {
    public static final Type<KillAllUnitsPayload> TYPE = new Type<>(ProjectHivemind.id("kill_all_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KillAllUnitsPayload> STREAM_CODEC = StreamCodec.unit(new KillAllUnitsPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
