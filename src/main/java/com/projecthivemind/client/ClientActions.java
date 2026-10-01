package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * What the local player's units are currently working on, as last told by the server: blocks, and mobs under attack.
 * The context menus offer "Cancel actions" on exactly these. Plain data only.
 */
public final class ClientActions {
    private static Set<BlockPos> blocks = new HashSet<>();
    private static Set<Integer> attacked = new HashSet<>();

    private ClientActions() {
    }

    public static void update(List<BlockPos> blockPositions, List<Integer> attackedMobs) {
        blocks = new HashSet<>(blockPositions);
        attacked = new HashSet<>(attackedMobs);
    }

    /** Blocks units are walking to, digging or interacting with. */
    public static Set<BlockPos> blocks() {
        return Set.copyOf(blocks);
    }

    /** Entity ids of mobs units are attacking. */
    public static Set<Integer> attackedMobs() {
        return Set.copyOf(attacked);
    }

    public static boolean isActive(BlockPos pos) {
        return blocks.contains(pos);
    }

    public static boolean isAttacked(int entityId) {
        return attacked.contains(entityId);
    }

    public static void reset() {
        blocks = new HashSet<>();
        attacked = new HashSet<>();
    }
}
