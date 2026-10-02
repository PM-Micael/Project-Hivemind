package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: move the camera to this unit (a head was clicked on its page of the hive menu). */
public record FocusUnitPayload(int unitId) implements CustomPacketPayload {
    public static final Type<FocusUnitPayload> TYPE = new Type<>(ProjectHivemind.id("focus_unit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FocusUnitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FocusUnitPayload::unitId,
            FocusUnitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
