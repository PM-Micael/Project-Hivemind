package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.MobAction;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the player picked an option in a mob's context menu.
 *
 * @param unitIds  the selected units, by entity id; the server checks they are the sender's
 * @param targetId the mob's entity id
 */
public record MobActionPayload(List<Integer> unitIds, int targetId, MobAction action) implements CustomPacketPayload {
    public static final Type<MobActionPayload> TYPE = new Type<>(ProjectHivemind.id("mob_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MobActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), MobActionPayload::unitIds,
            ByteBufCodecs.VAR_INT, MobActionPayload::targetId,
            ByteBufCodecs.idMapper(i -> MobAction.values()[i], MobAction::ordinal), MobActionPayload::action,
            MobActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
