package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the Hive Heart's health and maximum, in health points, its armor points and the hive's food level, for
 * the bars on the screen; and where the Heart is and how far the hive area reaches from it, for the border particles.
 */
public record SyncHeartHealthPayload(float health, float maxHealth, int armor, int food, int areaRadius, BlockPos center) implements CustomPacketPayload {
    public static final Type<SyncHeartHealthPayload> TYPE = new Type<>(ProjectHivemind.id("sync_heart_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHeartHealthPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, SyncHeartHealthPayload::health,
            ByteBufCodecs.FLOAT, SyncHeartHealthPayload::maxHealth,
            ByteBufCodecs.VAR_INT, SyncHeartHealthPayload::armor,
            ByteBufCodecs.VAR_INT, SyncHeartHealthPayload::food,
            ByteBufCodecs.VAR_INT, SyncHeartHealthPayload::areaRadius,
            BlockPos.STREAM_CODEC, SyncHeartHealthPayload::center,
            SyncHeartHealthPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
