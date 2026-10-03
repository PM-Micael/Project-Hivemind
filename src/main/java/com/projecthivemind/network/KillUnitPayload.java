package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: kill this unit (the player confirmed the Kill unit button on its page). The server checks it is the sender's. */
public record KillUnitPayload(int unitId) implements CustomPacketPayload {
    public static final Type<KillUnitPayload> TYPE = new Type<>(ProjectHivemind.id("kill_unit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, KillUnitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KillUnitPayload::unitId,
            KillUnitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
