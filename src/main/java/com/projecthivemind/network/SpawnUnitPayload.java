package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: spawn a unit of this kind at the Hive Heart. */
public record SpawnUnitPayload(UnitKind kind) implements CustomPacketPayload {
    public static final Type<SpawnUnitPayload> TYPE = new Type<>(ProjectHivemind.id("spawn_unit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpawnUnitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> UnitKind.values()[i], UnitKind::ordinal), SpawnUnitPayload::kind,
            SpawnUnitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
