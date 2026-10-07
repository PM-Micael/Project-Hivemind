package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the Heart tab's settings: the food levels at or below which the hive drinks honey and eats from its food slot (0 for never), and whether honey goes first. */
public record SetHeartSettingsPayload(int containerId, int honeyBelow, int slotBelow, boolean honeyFirst, boolean perfect) implements CustomPacketPayload {
    public static final Type<SetHeartSettingsPayload> TYPE = new Type<>(ProjectHivemind.id("set_heart_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetHeartSettingsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetHeartSettingsPayload::containerId,
            ByteBufCodecs.VAR_INT, SetHeartSettingsPayload::honeyBelow,
            ByteBufCodecs.VAR_INT, SetHeartSettingsPayload::slotBelow,
            ByteBufCodecs.BOOL, SetHeartSettingsPayload::honeyFirst,
            ByteBufCodecs.BOOL, SetHeartSettingsPayload::perfect,
            SetHeartSettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
