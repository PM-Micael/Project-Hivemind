package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the Hive Heart's health and maximum, in health points, and its armor points, for the bars on the screen. */
public record SyncHeartHealthPayload(float health, float maxHealth, int armor) implements CustomPacketPayload {
    public static final Type<SyncHeartHealthPayload> TYPE = new Type<>(ProjectHivemind.id("sync_heart_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHeartHealthPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, SyncHeartHealthPayload::health,
            ByteBufCodecs.FLOAT, SyncHeartHealthPayload::maxHealth,
            ByteBufCodecs.VAR_INT, SyncHeartHealthPayload::armor,
            SyncHeartHealthPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
