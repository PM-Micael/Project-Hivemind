package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: one unit's behaviour settings, from its page of the hive menu. What the numbers mean depends on the
 * kind of unit (see SoldierBehavior, WorkerBehavior, ScoutBehavior and CollectorBehavior): the checkboxes as flags, and
 * the radius beside each option that has one.
 *
 * @param unitId the unit's entity id; the server checks it is the sender's
 */
public record SetUnitBehaviorPayload(int unitId, int flags, List<Integer> radii) implements CustomPacketPayload {
    public static final Type<SetUnitBehaviorPayload> TYPE = new Type<>(ProjectHivemind.id("set_unit_behavior"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetUnitBehaviorPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetUnitBehaviorPayload::unitId,
            ByteBufCodecs.VAR_INT, SetUnitBehaviorPayload::flags,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(4)), SetUnitBehaviorPayload::radii,
            SetUnitBehaviorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
