package com.projecthivemind.network;

import com.projecthivemind.HivemindStage;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: what the client needs to know about its player for UI, input and rendering.
 *
 * @param normalInventory the (creative) player is currently using the normal inventory, not the hive
 * @param canSwapInventory the player is allowed to swap between the normal inventory and the hive
 */
public record SyncHivemindPayload(HivemindStage stage, boolean normalInventory, boolean canSwapInventory) implements CustomPacketPayload {
    public static final Type<SyncHivemindPayload> TYPE = new Type<>(ProjectHivemind.id("sync_hivemind"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHivemindPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> HivemindStage.values()[i], HivemindStage::ordinal), SyncHivemindPayload::stage,
            ByteBufCodecs.BOOL, SyncHivemindPayload::normalInventory,
            ByteBufCodecs.BOOL, SyncHivemindPayload::canSwapInventory,
            SyncHivemindPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
