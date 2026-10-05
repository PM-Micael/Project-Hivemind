package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server, every tick while a scout is controlled: what the player is pressing and where they are looking. The
 * server drives the scout with it. {@code flags} holds the buttons, see the FLAG constants.
 */
public record ControlInputPayload(float forward, float strafe, int flags, float yaw, float pitch) implements CustomPacketPayload {
    public static final int JUMP = 1;
    public static final int SNEAK = 2;
    public static final int SPRINT = 4;
    /** The attack button is held: break the block, hit the mob. */
    public static final int ATTACK = 8;
    /** The use button is held: use the item, open the block. */
    public static final int USE = 16;

    public static final Type<ControlInputPayload> TYPE = new Type<>(ProjectHivemind.id("control_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlInputPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, ControlInputPayload::forward,
            ByteBufCodecs.FLOAT, ControlInputPayload::strafe,
            ByteBufCodecs.VAR_INT, ControlInputPayload::flags,
            ByteBufCodecs.FLOAT, ControlInputPayload::yaw,
            ByteBufCodecs.FLOAT, ControlInputPayload::pitch,
            ControlInputPayload::new);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
