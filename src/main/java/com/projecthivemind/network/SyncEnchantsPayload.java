package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the levels of enchantments (their keys) that the hive has made available in its enchanting station, and those it could learn
 * now, because an enchanted book with exactly that level is in its storage.
 */
public record SyncEnchantsPayload(List<String> unlocked, List<String> ready) implements CustomPacketPayload {
    public static final Type<SyncEnchantsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_enchants"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncEnchantsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8192)), SyncEnchantsPayload::unlocked,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8192)), SyncEnchantsPayload::ready,
            SyncEnchantsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
