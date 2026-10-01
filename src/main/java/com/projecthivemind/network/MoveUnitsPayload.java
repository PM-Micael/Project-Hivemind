package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: walk these units (by entity id) to this spot. The server checks the units are the sender's. */
public record MoveUnitsPayload(List<Integer> unitIds, double x, double y, double z) implements CustomPacketPayload {
    /** More than any hive will have; stops a bad client from sending an enormous list. */
    public static final int MAX_UNITS = 128;

    public static final Type<MoveUnitsPayload> TYPE = new Type<>(ProjectHivemind.id("move_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MoveUnitsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_UNITS)), MoveUnitsPayload::unitIds,
            ByteBufCodecs.DOUBLE, MoveUnitsPayload::x,
            ByteBufCodecs.DOUBLE, MoveUnitsPayload::y,
            ByteBufCodecs.DOUBLE, MoveUnitsPayload::z,
            MoveUnitsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
