package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the player's units, for the unit pages of the hive menu and the names over them. The client cannot
 * always see every unit (one far away is not tracked), so the list comes from the server, along with each unit's job,
 * health and, for a collector, its planting task.
 */
public record SyncUnitsPayload(List<Entry> units) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 64;

    /** A unit's health and the most it can have, in health points. */
    public record Vitals(float health, float maxHealth) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vitals> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Vitals::health,
                ByteBufCodecs.FLOAT, Vitals::maxHealth,
                Vitals::new);
    }

    /**
     * A collector's planting tasks: for crops, and for saplings, the item to plant (a registry name, empty for none) and the
     * soil blocks to plant it on.
     */
    public record Task(String seed, List<BlockPos> spots, String sapling, List<BlockPos> saplingSpots) {
        public static final Task NONE = new Task("", List.of(), "", List.of());
        public static final StreamCodec<RegistryFriendlyByteBuf, Task> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Task::seed,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(16)), Task::spots,
                ByteBufCodecs.STRING_UTF8, Task::sapling,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(16)), Task::saplingSpots,
                Task::new);
    }

    /**
     * One unit: its entity id, the ordinal of its UnitKind, what its job is (empty for no job), flags (see {@link #paused}
     * and {@link #resume}: whether the job is set aside, and whether it is taken up again when the unit is released), its
     * health, and its planting task.
     */
    public record Entry(int entityId, int kind, Component job, int flags, Vitals vitals, Task task) {
        public static final int PAUSED = 1;
        public static final int RESUME = 2;
        public static final int TEAM = 4;

        public static int flags(boolean paused, boolean resume, boolean team) {
            return (paused ? PAUSED : 0) | (resume ? RESUME : 0) | (team ? TEAM : 0);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Entry::entityId,
                ByteBufCodecs.VAR_INT, Entry::kind,
                ComponentSerialization.STREAM_CODEC, Entry::job,
                ByteBufCodecs.VAR_INT, Entry::flags,
                Vitals.STREAM_CODEC, Entry::vitals,
                Task.STREAM_CODEC, Entry::task,
                Entry::new);

        public boolean paused() {
            return (flags & PAUSED) != 0;
        }

        public boolean team() {
            return (flags & TEAM) != 0;
        }

        public boolean resume() {
            return (flags & RESUME) != 0;
        }

        public float health() {
            return vitals.health();
        }

        public float maxHealth() {
            return vitals.maxHealth();
        }

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
