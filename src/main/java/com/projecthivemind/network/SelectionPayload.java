package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the units the player has selected, by entity id. Selection lives on the client, but units the
 * player has selected must ignore the hive's default behaviour, so the server needs to know.
 */
public record SelectionPayload(List<Integer> unitIds) implements CustomPacketPayload {
    public static final Type<SelectionPayload> TYPE = new Type<>(ProjectHivemind.id("selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), SelectionPayload::unitIds,
            SelectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
