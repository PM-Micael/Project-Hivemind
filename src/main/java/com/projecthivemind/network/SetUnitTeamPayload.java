package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: move this unit into this team (0 is the first), or out of every team (-1). The server checks it is the sender's and the team exists. */
public record SetUnitTeamPayload(int unitId, int team) implements CustomPacketPayload {
    public static final Type<SetUnitTeamPayload> TYPE = new Type<>(ProjectHivemind.id("set_unit_team"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetUnitTeamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetUnitTeamPayload::unitId,
            ByteBufCodecs.VAR_INT, SetUnitTeamPayload::team,
            SetUnitTeamPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
