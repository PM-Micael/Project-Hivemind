package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.BlockAction;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the player picked an option in a block's context menu.
 *
 * @param unitIds   the selected units, by entity id; the server checks they are the sender's
 * @param confirmed the player has already seen and accepted the weak-tool warning for this dig
 */
public record BlockActionPayload(List<Integer> unitIds, BlockPos pos, BlockAction action, boolean confirmed) implements CustomPacketPayload {
    /** More than any hive will have; stops a bad client from sending an enormous list. */
    public static final int MAX_UNITS = 128;

    public static final Type<BlockActionPayload> TYPE = new Type<>(ProjectHivemind.id("block_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_UNITS)), BlockActionPayload::unitIds,
            BlockPos.STREAM_CODEC, BlockActionPayload::pos,
            ByteBufCodecs.idMapper(i -> BlockAction.values()[i], BlockAction::ordinal), BlockActionPayload::action,
            ByteBufCodecs.BOOL, BlockActionPayload::confirmed,
            BlockActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
