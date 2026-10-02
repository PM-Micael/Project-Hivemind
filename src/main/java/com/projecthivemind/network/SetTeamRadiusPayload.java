package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: how far around its scout this team keeps together, in blocks (the server keeps it in range). */
public record SetTeamRadiusPayload(int team, int radius) implements CustomPacketPayload {
    public static final Type<SetTeamRadiusPayload> TYPE = new Type<>(ProjectHivemind.id("set_team_radius"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTeamRadiusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTeamRadiusPayload::team,
            ByteBufCodecs.VAR_INT, SetTeamRadiusPayload::radius,
            SetTeamRadiusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
