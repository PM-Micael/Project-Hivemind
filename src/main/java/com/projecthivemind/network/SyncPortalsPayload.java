package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the hive's portal network, for the Portals tab. The portals in order, how many may stand, and the summoning: which
 * portal it is at ({@link #NONE} for no summoning, -1 for the Hive Heart), the units still to come (their kind ordinals, in order), and
 * seconds to the next.
 */
public record SyncPortalsPayload(List<Portal> portals, int max, int summonTarget, List<Integer> queue, int secondsToNext) implements CustomPacketPayload {
    public static final int NONE = -2;
    public static final int MAX_ENTRIES = 64;

    /** One portal: the name of its dimension and its block. */
    public record Portal(String dimension, BlockPos pos, boolean resummon) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Portal> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Portal::dimension,
                BlockPos.STREAM_CODEC, Portal::pos,
                ByteBufCodecs.BOOL, Portal::resummon,
                Portal::new);
    }

    public static final Type<SyncPortalsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_portals"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPortalsPayload> STREAM_CODEC = StreamCodec.composite(
            Portal.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncPortalsPayload::portals,
            ByteBufCodecs.VAR_INT, SyncPortalsPayload::max,
            ByteBufCodecs.VAR_INT, SyncPortalsPayload::summonTarget,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_ENTRIES * 4)), SyncPortalsPayload::queue,
            ByteBufCodecs.VAR_INT, SyncPortalsPayload::secondsToNext,
            SyncPortalsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
