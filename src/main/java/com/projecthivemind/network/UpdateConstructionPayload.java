package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: new settings for the construction whose block is here (the "Options" screen), as Construction#configTag writes them. The server checks them. */
public record UpdateConstructionPayload(BlockPos pos, CompoundTag config) implements CustomPacketPayload {
    public static final Type<UpdateConstructionPayload> TYPE = new Type<>(ProjectHivemind.id("update_construction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateConstructionPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, UpdateConstructionPayload::pos,
            ByteBufCodecs.COMPOUND_TAG, UpdateConstructionPayload::config,
            UpdateConstructionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
