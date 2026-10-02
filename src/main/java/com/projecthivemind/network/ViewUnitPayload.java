package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the hive menu's page is now showing this unit. The server sends that unit's behaviour settings back
 * in the menu's synced values, tagged with the same number so the screen knows they are the ones it asked for.
 */
public record ViewUnitPayload(int containerId, int unitId, int seq) implements CustomPacketPayload {
    public static final Type<ViewUnitPayload> TYPE = new Type<>(ProjectHivemind.id("view_unit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ViewUnitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ViewUnitPayload::containerId,
            ByteBufCodecs.VAR_INT, ViewUnitPayload::unitId,
            ByteBufCodecs.VAR_INT, ViewUnitPayload::seq,
            ViewUnitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
