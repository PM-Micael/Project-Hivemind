package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the Hive Heart's health and maximum, in health points, its armor points, its absorption (extra health points, shown as gold
 * hearts) and the hive's food level, for the bars on the screen; and where the Heart is and how far the hive area reaches from it, for the border
 * particles.
 */
public record SyncHeartHealthPayload(float health, float maxHealth, int armor, int food, int areaRadius, BlockPos center, float absorption, boolean dehydrators) implements CustomPacketPayload {
    public static final Type<SyncHeartHealthPayload> TYPE = new Type<>(ProjectHivemind.id("sync_heart_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHeartHealthPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeFloat(payload.health);
                buf.writeFloat(payload.maxHealth);
                buf.writeVarInt(payload.armor);
                buf.writeVarInt(payload.food);
                buf.writeVarInt(payload.areaRadius);
                buf.writeBlockPos(payload.center);
                buf.writeFloat(payload.absorption);
                buf.writeBoolean(payload.dehydrators);
            },
            buf -> new SyncHeartHealthPayload(buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBlockPos(), buf.readFloat(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
