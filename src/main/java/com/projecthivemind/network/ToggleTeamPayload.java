package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: add this unit to the team, or take it out if it is in it. The server checks it is the sender's. */
public record ToggleTeamPayload(int unitId) implements CustomPacketPayload {
    public static final Type<ToggleTeamPayload> TYPE = new Type<>(ProjectHivemind.id("toggle_team"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleTeamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ToggleTeamPayload::unitId,
            ToggleTeamPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
