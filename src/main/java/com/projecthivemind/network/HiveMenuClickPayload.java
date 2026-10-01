package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.ClickType;

/**
 * Client to server: a click in the hive menu. The hivemind is a spectator, and the server ignores vanilla container
 * clicks from spectators, so the hive menu sends its clicks here instead and the server applies the safe ones itself.
 */
public record HiveMenuClickPayload(int containerId, int slot, int button, ClickType clickType) implements CustomPacketPayload {
    public static final Type<HiveMenuClickPayload> TYPE = new Type<>(ProjectHivemind.id("hive_menu_click"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HiveMenuClickPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, HiveMenuClickPayload::containerId,
            ByteBufCodecs.INT, HiveMenuClickPayload::slot,
            ByteBufCodecs.INT, HiveMenuClickPayload::button,
            ByteBufCodecs.idMapper(i -> ClickType.values()[i], ClickType::ordinal), HiveMenuClickPayload::clickType,
            HiveMenuClickPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
