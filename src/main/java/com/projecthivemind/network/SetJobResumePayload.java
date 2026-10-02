package com.projecthivemind.network;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: whether this unit goes back to its set-aside job when it is released (the job's tick box). */
public record SetJobResumePayload(int unitId, boolean resume) implements CustomPacketPayload {
    public static final Type<SetJobResumePayload> TYPE = new Type<>(ProjectHivemind.id("set_job_resume"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetJobResumePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetJobResumePayload::unitId,
            ByteBufCodecs.BOOL, SetJobResumePayload::resume,
            SetJobResumePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
