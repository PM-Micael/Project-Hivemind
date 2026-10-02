package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Server to client: open the book the scout is reading. The hivemind has no hands, so the book is sent as an item. */
public record OpenBookPayload(ItemStack book) implements CustomPacketPayload {
    public static final Type<OpenBookPayload> TYPE = new Type<>(ProjectHivemind.id("open_book"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBookPayload> STREAM_CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC, OpenBookPayload::book,
            OpenBookPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
