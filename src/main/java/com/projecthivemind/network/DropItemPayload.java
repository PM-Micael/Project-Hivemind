package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the selected scouts each drop one item from their hand (the drop key). */
public record DropItemPayload(List<Integer> unitIds) implements CustomPacketPayload {
    public static final Type<DropItemPayload> TYPE = new Type<>(ProjectHivemind.id("drop_item"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DropItemPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), DropItemPayload::unitIds,
            DropItemPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
