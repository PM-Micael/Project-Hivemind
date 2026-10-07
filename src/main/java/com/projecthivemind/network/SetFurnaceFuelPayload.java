package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: choose the fuel the furnace's fuel slot is kept filled with from the hive's storage (an item name; empty for none). */
public record SetFurnaceFuelPayload(int containerId, String item) implements CustomPacketPayload {
    public static final Type<SetFurnaceFuelPayload> TYPE = new Type<>(ProjectHivemind.id("set_furnace_fuel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetFurnaceFuelPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetFurnaceFuelPayload::containerId,
            ByteBufCodecs.STRING_UTF8, SetFurnaceFuelPayload::item,
            SetFurnaceFuelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
