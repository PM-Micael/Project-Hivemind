package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: swap between the normal inventory and the hive (creative players only). */
public record ToggleInventoryModePayload() implements CustomPacketPayload {
    public static final Type<ToggleInventoryModePayload> TYPE = new Type<>(ProjectHivemind.id("toggle_inventory_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleInventoryModePayload> STREAM_CODEC =
            StreamCodec.unit(new ToggleInventoryModePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
