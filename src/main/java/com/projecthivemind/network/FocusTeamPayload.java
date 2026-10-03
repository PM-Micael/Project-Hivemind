package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: move the camera to this team's scout, or to the next unit of the team if it has none (a team hotkey was pressed). */
public record FocusTeamPayload(int team) implements CustomPacketPayload {
    public static final Type<FocusTeamPayload> TYPE = new Type<>(ProjectHivemind.id("focus_team"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FocusTeamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FocusTeamPayload::team,
            FocusTeamPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
