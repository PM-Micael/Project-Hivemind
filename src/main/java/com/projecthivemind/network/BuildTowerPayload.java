package com.projecthivemind.network;

import java.util.List;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: build a tower, or dig a shaft, on this block with these workers (see TowerPlan).
 *
 * @param unitIds   the selected units, by entity id; the server checks they are the sender's workers
 * @param pos       the block the build is on
 * @param materials which TowerMaterials may be used, one bit each (see TowerSet#bit)
 * @param height    how high or deep, one of TowerPlan.HEIGHTS (the server checks)
 * @param options   the rest packed into one number: see {@link #pack}
 */
public record BuildTowerPayload(List<Integer> unitIds, BlockPos pos, int materials, int height, int options) implements CustomPacketPayload {
    public static final Type<BuildTowerPayload> TYPE = new Type<>(ProjectHivemind.id("build_tower"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildTowerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(BlockActionPayload.MAX_UNITS)), BuildTowerPayload::unitIds,
            BlockPos.STREAM_CODEC, BuildTowerPayload::pos,
            ByteBufCodecs.VAR_INT, BuildTowerPayload::materials,
            ByteBufCodecs.VAR_INT, BuildTowerPayload::height,
            ByteBufCodecs.VAR_INT, BuildTowerPayload::options,
            BuildTowerPayload::new);

    /** Pack the walls choice, the direction (0 up, 1 down) and the shape's index: bit 0, bit 1, and the bits above. */
    public static int pack(boolean walls, int direction, int shape) {
        return (walls ? 1 : 0) | ((direction & 1) << 1) | (shape << 2);
    }

    /** True for walls all round, false for just four corner pillars. */
    public boolean walls() {
        return (options & 1) != 0;
    }

    /** The index of a TowerDirection. */
    public int direction() {
        return (options >> 1) & 1;
    }

    /** The index of a TowerShape. */
    public int shape() {
        return options >> 2;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
