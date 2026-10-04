package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: switch "resummon team units" on or off for the portal at this index of the hive's list (from the portal's right-click menu). */
public record TogglePortalResummonPayload(int index) implements CustomPacketPayload {
    public static final Type<TogglePortalResummonPayload> TYPE = new Type<>(ProjectHivemind.id("toggle_portal_resummon"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TogglePortalResummonPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TogglePortalResummonPayload::index,
            TogglePortalResummonPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
