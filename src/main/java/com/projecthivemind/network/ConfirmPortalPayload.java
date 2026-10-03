package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the hive is at its portal limit, so placing another replaces the oldest. The client asks the player, and if they accept
 * sends the same order again with {@link PlacePortalPayload#confirmed()} set.
 */
public record ConfirmPortalPayload(PlacePortalPayload order) implements CustomPacketPayload {
    public static final Type<ConfirmPortalPayload> TYPE = new Type<>(ProjectHivemind.id("confirm_portal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmPortalPayload> STREAM_CODEC = StreamCodec.composite(
            PlacePortalPayload.STREAM_CODEC, ConfirmPortalPayload::order,
            ConfirmPortalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
