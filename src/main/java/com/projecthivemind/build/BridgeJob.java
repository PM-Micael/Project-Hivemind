package com.projecthivemind.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * A bridge from one place to another: a straight deck three to seven blocks wide, with an optional fence along both edges and optional torches
 * on it. Like the tower plans it is only a recipe: where everything goes is worked out here, and what is done is always read from the
 * world, so a half-built bridge, or one left when the game closed, carries on where it was.
 *
 * <p>The deck runs in a straight line on the ground plan from {@code start} to {@code dest}; its height goes in a straight line too, from
 * the start's to the destination's, one block at a time at most. Both ends are left open (no fence) so it can be walked onto. Torches
 * stand on top of the fence (or on the deck's edge, with no fence) every few blocks.
 *
 * @param start  the block the deck begins on: the one under the worker when it was ordered
 * @param dest   the block the deck ends on: the one that was clicked
 * @param deck   what the deck is made of
 * @param fence  the fence along the edges, or null for none
 * @param torches whether torches go along the way
 * @param width  how many blocks wide the deck is, {@link #MIN_WIDTH} to {@link #MAX_WIDTH}
 */
public record BridgeJob(BlockPos start, BlockPos dest, Item deck, @Nullable Item fence, boolean torches, int width) {
    public BridgeJob {
        width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    /** The longest bridge that can be ordered, in blocks along the ground. */
    public static final int MAX_LENGTH = 96;
    /** A torch goes on every this many blocks along the deck. */
    private static final int TORCH_EVERY = 6;

    public enum Kind {
        DECK, FENCE, TORCH
    }

    /**
     * One block of the bridge. {@code stand} is where a worker can stand to place it from: on the deck one step back, or beside the start.
     */
    public record Placement(BlockPos pos, Kind kind, Vec3 stand) {
    }

    /** How many blocks the bridge runs along the ground, counting both ends (the same as the number of steps). */
    public int length() {
        return Math.max(Math.abs(dest.getX() - start.getX()), Math.abs(dest.getZ() - start.getZ())) + 1;
    }

    /** The cells of the straight line on the ground, from the start to the destination: one for each step. */
    private List<int[]> line() {
        List<int[]> cells = new ArrayList<>();
        int steps = length() - 1;
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0D : (double) i / steps;
            cells.add(new int[] {(int) Math.round(start.getX() + (dest.getX() - start.getX()) * t),
                    (int) Math.round(start.getZ() + (dest.getZ() - start.getZ()) * t)});
        }
        return cells;
    }

    /** The height of the deck at each step: a straight line from the start's to the destination's. */
    private int deckY(int step) {
        int steps = length() - 1;
        return steps == 0 ? start.getY() : (int) Math.round(start.getY() + (dest.getY() - start.getY()) * ((double) step / steps));
    }

    /** True if the height difference can be walked: no more than one block up or down for each block along. */
    public boolean walkable() {
        return Math.abs(dest.getY() - start.getY()) <= length() - 1;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** The widths a bridge can have, in blocks. */
    public static final int MIN_WIDTH = 3;
    public static final int MAX_WIDTH = 7;

    /** Every block of the bridge in the order it is built: step by step along it, the deck first, then fences, then torches. */
    public List<Placement> placements() {
        List<int[]> line = line();
        int steps = line.size();
        // The deck is a square brush swept along the line: from this far behind to this far ahead of the line, on both axes. An even
        // width has one more block on the far side.
        int low = -((width - 1) / 2);
        int high = width / 2;

        // Each cell of the deck belongs to the first step that reaches it (that is the order it is built in), and takes its height from the
        // step whose middle is nearest to it, so a sloping bridge slopes evenly across its width.
        Map<Long, Integer> claimedBy = new HashMap<>();
        Map<Long, int[]> nearest = new HashMap<>();
        List<int[]> order = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            for (int ox = low; ox <= high; ox++) {
                for (int oz = low; oz <= high; oz++) {
                    int x = line.get(i)[0] + ox;
                    int z = line.get(i)[1] + oz;
                    long key = key(x, z);
                    if (claimedBy.putIfAbsent(key, i) == null) {
                        order.add(new int[] {x, z, i});
                    }
                    int distance = ox * ox + oz * oz;
                    int[] best = nearest.get(key);
                    if (best == null || distance < best[0]) {
                        nearest.put(key, new int[] {distance, i});
                    }
                }
            }
        }

        // The edge: deck cells with a side that is not deck. Both ends are left open, as far in as the brush reaches.
        int[] first = line.get(0);
        int[] last = line.get(steps - 1);
        int endOpen = Math.max(1, high);
        Set<Long> edge = new HashSet<>();
        for (int[] cell : order) {
            boolean open = !claimedBy.containsKey(key(cell[0] + 1, cell[1])) || !claimedBy.containsKey(key(cell[0] - 1, cell[1]))
                    || !claimedBy.containsKey(key(cell[0], cell[1] + 1)) || !claimedBy.containsKey(key(cell[0], cell[1] - 1));
            boolean atEnd = Math.max(Math.abs(cell[0] - first[0]), Math.abs(cell[1] - first[1])) <= endOpen
                    || Math.max(Math.abs(cell[0] - last[0]), Math.abs(cell[1] - last[1])) <= endOpen;
            if (open && !atEnd) {
                edge.add(key(cell[0], cell[1]));
            }
        }

        List<Placement> result = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            Vec3 stand = standFor(line, step);
            for (int[] cell : order) {
                if (cell[2] == step) {
                    result.add(new Placement(new BlockPos(cell[0], deckY(nearest.get(key(cell[0], cell[1]))[1]), cell[1]), Kind.DECK, stand));
                }
            }
            if (fence != null) {
                for (int[] cell : order) {
                    if (cell[2] == step && edge.contains(key(cell[0], cell[1]))) {
                        result.add(new Placement(new BlockPos(cell[0], deckY(nearest.get(key(cell[0], cell[1]))[1]) + 1, cell[1]), Kind.FENCE, stand));
                    }
                }
            }
            if (torches && step % TORCH_EVERY == TORCH_EVERY / 2 && step >= 2 && step <= steps - 3) {
                int[] center = line.get(step);
                int[] best = null;
                double bestDistance = Double.MAX_VALUE;
                for (int[] cell : order) {
                    if (edge.contains(key(cell[0], cell[1])) && Math.max(Math.abs(cell[0] - center[0]), Math.abs(cell[1] - center[1])) <= high + 1) {
                        double distance = Math.pow(cell[0] - center[0], 2) + Math.pow(cell[1] - center[1], 2);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = cell;
                        }
                    }
                }
                if (best != null) {
                    int y = deckY(nearest.get(key(best[0], best[1]))[1]) + (fence != null ? 2 : 1);
                    result.add(new Placement(new BlockPos(best[0], y, best[1]), Kind.TORCH, stand));
                }
            }
        }
        return result;
    }

    /** Where to stand to build this step: on the deck one step back (the first step is built from beside the start). */
    private Vec3 standFor(List<int[]> line, int step) {
        int back = Math.max(0, step - 1);
        return new Vec3(line.get(back)[0] + 0.5D, deckY(back) + 1.0D, line.get(back)[1] + 0.5D);
    }

    // ---- saving ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Start", NbtUtils.writeBlockPos(start));
        tag.put("Dest", NbtUtils.writeBlockPos(dest));
        tag.putString("Deck", BuiltInRegistries.ITEM.getKey(deck).toString());
        if (fence != null) {
            tag.putString("Fence", BuiltInRegistries.ITEM.getKey(fence).toString());
        }
        tag.putBoolean("Torches", torches);
        tag.putInt("Width", width);
        return tag;
    }

    @Nullable
    public static BridgeJob load(CompoundTag tag) {
        BlockPos start = NbtUtils.readBlockPos(tag, "Start").orElse(null);
        BlockPos dest = NbtUtils.readBlockPos(tag, "Dest").orElse(null);
        ResourceLocation deckId = ResourceLocation.tryParse(tag.getString("Deck"));
        Item deck = deckId == null ? null : BuiltInRegistries.ITEM.getOptional(deckId).orElse(null);
        ResourceLocation fenceId = tag.contains("Fence") ? ResourceLocation.tryParse(tag.getString("Fence")) : null;
        Item fence = fenceId == null ? null : BuiltInRegistries.ITEM.getOptional(fenceId).orElse(null);
        return start == null || dest == null || deck == null ? null : new BridgeJob(start, dest, deck, fence, tag.getBoolean("Torches"), tag.contains("Width") ? tag.getInt("Width") : 3);
    }
}
