package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the places the hive knows about, in order, for the Locations tab. */
public record SyncLocationsPayload(List<Location> locations) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 64;

    /** One location: the ordinal of its kind (see HiveLocations.Kind) and its spot. */
    public record Location(int kind, BlockPos pos) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Location> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Location::kind,
                BlockPos.STREAM_CODEC, Location::pos,
                Location::new);
    }

    public static final Type<SyncLocationsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_locations"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncLocationsPayload> STREAM_CODEC = StreamCodec.composite(
            Location.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncLocationsPayload::locations,
            SyncLocationsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
