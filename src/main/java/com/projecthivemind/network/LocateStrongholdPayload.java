package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: find the stronghold nearest the Hive Heart and save it as a location (needs the Eye of Ender task). */
public record LocateStrongholdPayload() implements CustomPacketPayload {
    public static final Type<LocateStrongholdPayload> TYPE = new Type<>(ProjectHivemind.id("locate_stronghold"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LocateStrongholdPayload> STREAM_CODEC =
            StreamCodec.unit(new LocateStrongholdPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
