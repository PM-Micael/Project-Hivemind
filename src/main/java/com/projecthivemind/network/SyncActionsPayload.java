package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: what the owner's units are currently working on, so "Cancel actions" is offered on exactly those.
 *
 * @param positions the blocks units are walking to, digging or interacting with
 * @param attacked  the entity ids of mobs units are attacking
 */
public record SyncActionsPayload(List<BlockPos> positions, List<Integer> attacked) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 256;

    public static final Type<SyncActionsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_actions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncActionsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncActionsPayload::positions,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncActionsPayload::attacked,
            SyncActionsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
