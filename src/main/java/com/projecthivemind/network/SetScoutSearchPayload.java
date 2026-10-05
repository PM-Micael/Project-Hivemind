package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: what the player typed in the search box of the scouts' page of the open hive menu (empty to show everything). */
public record SetScoutSearchPayload(int containerId, String text) implements CustomPacketPayload {
    public static final Type<SetScoutSearchPayload> TYPE = new Type<>(ProjectHivemind.id("set_scout_search"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetScoutSearchPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetScoutSearchPayload::containerId,
            ByteBufCodecs.stringUtf8(64), SetScoutSearchPayload::text,
            SetScoutSearchPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
