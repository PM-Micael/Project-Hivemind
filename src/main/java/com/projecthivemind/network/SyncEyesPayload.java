package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: where the hive's eyes are (the Heart and each unit) and how far each sees. The client uses it to
 * work out which terrain is in sight and fog the rest.
 */
public record SyncEyesPayload(List<EyePoint> eyes) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 64;

    public record EyePoint(double x, double y, double z, float radius) {
        public static final StreamCodec<RegistryFriendlyByteBuf, EyePoint> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, EyePoint::x,
                ByteBufCodecs.DOUBLE, EyePoint::y,
                ByteBufCodecs.DOUBLE, EyePoint::z,
                ByteBufCodecs.FLOAT, EyePoint::radius,
                EyePoint::new);
    }

    public static final Type<SyncEyesPayload> TYPE = new Type<>(ProjectHivemind.id("sync_eyes"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncEyesPayload> STREAM_CODEC = StreamCodec.composite(
            EyePoint.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncEyesPayload::eyes,
            SyncEyesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
