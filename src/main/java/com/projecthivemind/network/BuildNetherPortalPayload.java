package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: these workers build the smallest nether portal, standing on this block, and light it. The server checks they are the sender's and the site is fit. */
public record BuildNetherPortalPayload(List<Integer> unitIds, BlockPos site) implements CustomPacketPayload {
    public static final Type<BuildNetherPortalPayload> TYPE = new Type<>(ProjectHivemind.id("build_nether_portal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildNetherPortalPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), BuildNetherPortalPayload::unitIds,
            BlockPos.STREAM_CODEC, BuildNetherPortalPayload::site,
            BuildNetherPortalPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
