package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the music disc the hive's jukebox is playing (its item name), or empty for none. Sent when it changes, and now and then. */
public record SyncMusicPayload(String disc) implements CustomPacketPayload {
    public static final Type<SyncMusicPayload> TYPE = new Type<>(ProjectHivemind.id("sync_music"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMusicPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SyncMusicPayload::disc,
            SyncMusicPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
