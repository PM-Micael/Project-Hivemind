package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: for each team, how far around its scout it keeps together, and how far its soldiers go after hostile mobs, in blocks. */
public record SyncTeamPayload(List<Integer> radii, List<Integer> attackRadii) implements CustomPacketPayload {
    public static final Type<SyncTeamPayload> TYPE = new Type<>(ProjectHivemind.id("sync_team"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTeamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(8)), SyncTeamPayload::radii,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(8)), SyncTeamPayload::attackRadii,
            SyncTeamPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
