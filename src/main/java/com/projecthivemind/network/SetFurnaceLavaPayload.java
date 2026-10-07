package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: whether the furnace burns lava from the hive's fluids. */
public record SetFurnaceLavaPayload(int containerId, boolean on) implements CustomPacketPayload {
    public static final Type<SetFurnaceLavaPayload> TYPE = new Type<>(ProjectHivemind.id("set_furnace_lava"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetFurnaceLavaPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetFurnaceLavaPayload::containerId,
            ByteBufCodecs.BOOL, SetFurnaceLavaPayload::on,
            SetFurnaceLavaPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
