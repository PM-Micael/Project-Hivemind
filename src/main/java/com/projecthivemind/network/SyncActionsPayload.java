package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the blocks the owner's units are currently working on, so "Cancel actions" is offered on them. */
public record SyncActionsPayload(List<BlockPos> positions) implements CustomPacketPayload {
    public static final int MAX_POSITIONS = 256;

    public static final Type<SyncActionsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_actions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncActionsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_POSITIONS)), SyncActionsPayload::positions,
            SyncActionsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
