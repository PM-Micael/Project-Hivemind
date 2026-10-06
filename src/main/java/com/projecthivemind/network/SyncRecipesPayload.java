package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the ids of the recipes the hive crafted last (the newest first), for the row under the crafting grid. */
public record SyncRecipesPayload(List<String> recipes) implements CustomPacketPayload {
    public static final Type<SyncRecipesPayload> TYPE = new Type<>(ProjectHivemind.id("sync_recipes"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncRecipesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(16)), SyncRecipesPayload::recipes,
            SyncRecipesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
