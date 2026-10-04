package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the hive's constructions, for highlighting their blocks and for the menu of a construction block.
 *
 * <p>Each one: the dimension and position of its block, its kind, state and worker numbers packed into one number (see {@link Info#pack}), and its
 * settings (see Construction#configTag), for the Options screen.
 */
public record SyncConstructionsPayload(List<Info> constructions) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 16;

    /**
     * @param packed the kind (the ordinal of Construction.Kind), the state (of Construction.State), how many workers are on it, the fewest it can
     *               be worked on with and the most it can have
     */
    public record Info(String dimension, BlockPos pos, int packed, CompoundTag config) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Info::dimension,
                BlockPos.STREAM_CODEC, Info::pos,
                ByteBufCodecs.VAR_INT, Info::packed,
                ByteBufCodecs.COMPOUND_TAG, Info::config,
                Info::new);

        public static int pack(int kind, int state, int workers, int min, int max) {
            return (kind & 3) | ((state & 3) << 2) | ((workers & 15) << 4) | ((min & 15) << 8) | ((max & 15) << 12);
        }

        public int kind() {
            return packed & 3;
        }

        public int state() {
            return (packed >> 2) & 3;
        }

        public int workers() {
            return (packed >> 4) & 15;
        }

        public int min() {
            return (packed >> 8) & 15;
        }

        public int max() {
            return (packed >> 12) & 15;
        }
    }

    public static final Type<SyncConstructionsPayload> TYPE = new Type<>(ProjectHivemind.id("sync_constructions"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncConstructionsPayload> STREAM_CODEC = StreamCodec.composite(
            Info.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), SyncConstructionsPayload::constructions,
            SyncConstructionsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
