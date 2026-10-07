package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: the fluids the hive keeps, in the order of their meters, and how much of each (in the hive's units). */
public record SyncFluidsPayload(List<Entry> fluids) implements CustomPacketPayload {
    public record Entry(ResourceLocation id, int amount) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Entry::id,
                ByteBufCodecs.VAR_INT, Entry::amount,
                Entry::new);
    }

    public static final Type<SyncFluidsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_fluids"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncFluidsPayload> STREAM_CODEC = StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(256)), SyncFluidsPayload::fluids,
            SyncFluidsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
