package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: how far around its scout each team keeps together, in blocks (one number for each team). */
public record SyncTeamPayload(List<Integer> radii) implements CustomPacketPayload {
    public static final Type<SyncTeamPayload> TYPE = new Type<>(ProjectHivemind.id("sync_team"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTeamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(8)), SyncTeamPayload::radii,
            SyncTeamPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
