package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: send this scout (by entity id) to the location at this index of the hive's list, as a job. */
public record TravelToLocationPayload(int index, int scoutId) implements CustomPacketPayload {
    public static final Type<TravelToLocationPayload> TYPE = new Type<>(ProjectHivemind.id("travel_to_location"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TravelToLocationPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TravelToLocationPayload::index,
            ByteBufCodecs.VAR_INT, TravelToLocationPayload::scoutId,
            TravelToLocationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
