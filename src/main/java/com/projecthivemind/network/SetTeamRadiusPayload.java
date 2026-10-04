package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: how far around its scout this team keeps together, in blocks, or (with {@code attack}) how far from its scout the team's
 * soldiers go after hostile mobs. The server keeps each in range.
 */
public record SetTeamRadiusPayload(int team, int radius, boolean attack) implements CustomPacketPayload {
    public static final Type<SetTeamRadiusPayload> TYPE = new Type<>(ProjectHivemind.id("set_team_radius"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTeamRadiusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTeamRadiusPayload::team,
            ByteBufCodecs.VAR_INT, SetTeamRadiusPayload::radius,
            ByteBufCodecs.BOOL, SetTeamRadiusPayload::attack,
            SetTeamRadiusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
