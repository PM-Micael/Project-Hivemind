package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * The blocks the local player's units are currently working on, as last told by the server. The context menu offers
 * "Cancel actions" on exactly these. Plain data only.
 */
public final class ClientActions {
    private static Set<BlockPos> active = new HashSet<>();

    private ClientActions() {
    }

    public static void update(List<BlockPos> positions) {
        active = new HashSet<>(positions);
    }

    public static boolean isActive(BlockPos pos) {
        return active.contains(pos);
    }

    public static void reset() {
        active = new HashSet<>();
    }
}
