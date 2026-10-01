package com.projecthivemind.network;

import com.projecthivemind.HivemindStage;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the parts of the player's Hivemind state the client needs for UI and rendering. */
public record SyncHivemindPayload(HivemindStage stage, boolean hasWorker, boolean hasSoldier) implements CustomPacketPayload {
    public static final Type<SyncHivemindPayload> TYPE = new Type<>(ProjectHivemind.id("sync_hivemind"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHivemindPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> HivemindStage.values()[i], HivemindStage::ordinal), SyncHivemindPayload::stage,
            ByteBufCodecs.BOOL, SyncHivemindPayload::hasWorker,
            ByteBufCodecs.BOOL, SyncHivemindPayload::hasSoldier,
            SyncHivemindPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
