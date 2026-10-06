package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player clicked this recently crafted recipe (its id): set it in the crafting grid with what the hive's storage has. The server checks everything. */
public record PlaceRecipePayload(int containerId, String recipe) implements CustomPacketPayload {
    public static final Type<PlaceRecipePayload> TYPE = new Type<>(ProjectHivemind.id("place_recipe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlaceRecipePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlaceRecipePayload::containerId,
            ByteBufCodecs.STRING_UTF8, PlaceRecipePayload::recipe,
            PlaceRecipePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
