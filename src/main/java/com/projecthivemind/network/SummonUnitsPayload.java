package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: summon these units through a portal (index into the list of portals) or the Hive Heart (-1). The units die and come
 * back through the portal one at a time. The server checks the units are the sender's.
 */
public record SummonUnitsPayload(int target, List<Integer> unitIds) implements CustomPacketPayload {
    public static final Type<SummonUnitsPayload> TYPE = new Type<>(ProjectHivemind.id("summon_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SummonUnitsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SummonUnitsPayload::target,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), SummonUnitsPayload::unitIds,
            SummonUnitsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
