package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the player's units, for the unit pages of the hive menu. The client cannot always see every unit
 * (one far away is not tracked), so the list comes from the server, along with each unit's job.
 */
public record SyncUnitsPayload(List<Entry> units) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 64;

    /**
     * One unit: its entity id, the ordinal of its UnitKind, what its job is (empty for no job), flags (see {@link #paused}
     * and {@link #resume}: whether the job is set
     * aside for now, and whether it is taken up again when the unit is released.
     */
    public record Entry(int entityId, int kind, Component job, int flags, float health, float maxHealth) {
        public static final int PAUSED = 1;
        public static final int RESUME = 2;

        public static int flags(boolean paused, boolean resume) {
            return (paused ? PAUSED : 0) | (resume ? RESUME : 0);
        }

        public boolean paused() {
            return (flags & PAUSED) != 0;
        }

        public boolean resume() {
            return (flags & RESUME) != 0;
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Entry::entityId,
                ByteBufCodecs.VAR_INT, Entry::kind,
                ComponentSerialization.STREAM_CODEC, Entry::job,
                ByteBufCodecs.VAR_INT, Entry::flags,
                ByteBufCodecs.FLOAT, Entry::health,
                ByteBufCodecs.FLOAT, Entry::maxHealth,
                Entry::new);

        public boolean hasJob() {
            return !job.getString().isEmpty();
        }
    }

    public static final Type<SyncUnitsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncUnitsPayload> STREAM_CODEC = StreamCodec.composite(
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncUnitsPayload::units,
            SyncUnitsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
