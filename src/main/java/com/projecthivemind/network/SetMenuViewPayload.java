package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: which groups of slots the hive menu's open tab is showing (see HiveMenu.visibleGroups). The server
 * needs it so that shift-click only sends items to slots the player can actually see.
 */
public record SetMenuViewPayload(int containerId, int groups) implements CustomPacketPayload {
    public static final Type<SetMenuViewPayload> TYPE = new Type<>(ProjectHivemind.id("set_menu_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetMenuViewPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetMenuViewPayload::containerId,
            ByteBufCodecs.VAR_INT, SetMenuViewPayload::groups,
            SetMenuViewPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
