package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Server to client: the hotbar of the scout being controlled. Each slot is the item it stands for, and how many of it the hive has in all
 * (in the scout's hand and in the hive's storage; 0 when it has run out, the slot then stays as it was). The selected slot is the one
 * in the scout's hand.
 */
public record ControlHotbarPayload(List<ItemStack> stacks, List<Integer> counts, int selected) implements CustomPacketPayload {
    public static final int SLOTS = 9;

    public static final Type<ControlHotbarPayload> TYPE = new Type<>(ProjectHivemind.id("control_hotbar"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlHotbarPayload> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(SLOTS)), ControlHotbarPayload::stacks,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(SLOTS)), ControlHotbarPayload::counts,
            ByteBufCodecs.VAR_INT, ControlHotbarPayload::selected,
            ControlHotbarPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
