package com.projecthivemind.client;

import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.network.SyncConstructionsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** The hive's constructions as the server last told the client: for highlighting their blocks and for the menu of a construction block. */
public final class ClientConstructions {
    private static List<SyncConstructionsPayload.Info> constructions = List.of();

    private ClientConstructions() {
    }

    public static void update(List<SyncConstructionsPayload.Info> infos) {
        constructions = List.copyOf(infos);
    }

    public static List<SyncConstructionsPayload.Info> all() {
        return constructions;
    }

    /** The construction whose block is here, in the dimension the camera is in; or null. */
    @Nullable
    public static SyncConstructionsPayload.Info at(BlockPos pos) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        String dimension = Minecraft.getInstance().level.dimension().location().toString();
        for (SyncConstructionsPayload.Info info : constructions) {
            if (info.pos().equals(pos) && info.dimension().equals(dimension)) {
                return info;
            }
        }
        return null;
    }

    public static void reset() {
        constructions = List.of();
    }
}
