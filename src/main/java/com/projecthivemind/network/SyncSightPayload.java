package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the mobs the hive can see right now, by entity id. The client hides every other mob. */
public record SyncSightPayload(List<Integer> visibleMobs) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 1024;

    public static final Type<SyncSightPayload> TYPE = new Type<>(ProjectHivemind.id("sync_sight"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSightPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncSightPayload::visibleMobs,
            SyncSightPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
