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
public record BridgeJob(BlockPos start, BlockPos dest, Item deck, @Nullable Item fence, boolean torches, int width, boolean generator, int tunnelSize, boolean portal) {
    public BridgeJob {
        width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    public BridgeJob(BlockPos start, BlockPos dest, Item deck, @Nullable Item fence, boolean torches, int width) {
        this(start, dest, deck, fence, torches, width, false, 0, false);
    }

    public BridgeJob(BlockPos start, BlockPos dest, Item deck, @Nullable Item fence, boolean torches, int width, boolean generator) {
        this(start, dest, deck, fence, torches, width, generator, 0, false);
    }

    /** The longest bridge that can be ordered, in blocks along the ground. */
    public static final int MAX_LENGTH = 96;
    /** A torch goes on every this many blocks along the deck. */
    private static final int TORCH_EVERY = 6;

    public enum Kind {
        DECK, FENCE, TORCH, WALL, LAVA, WATER, DIG, SLAB, OBSIDIAN, IGNITE
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
        return PLACEMENT_CACHE.computeIfAbsent(this, BridgeJob::computePlacements);
    }

    private List<Placement> computePlacements() {
        if (generator) {
            return generatorPlacements();
        }
        if (portal) {
            return portalPlacements();
        }
        if (tunnelSize > 0) {
            return tunnelPlacements();
        }
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

    // ---- the cobblestone generator ----

    /**
     * A small cobblestone generator, as a recipe in the same form as a bridge (so it is built by the same workers, one block at a time). It is a row
     * of four cells dug into the ground from {@code floor}, running one way (the direction of {@code dest}); the top stays open. The first holds a
     * water source and the fourth a lava source. Under the second there is one more cell dug out, a pit: the water from the first spills into the second
     * and falls into the pit instead of flowing on to the lava, which would turn it to obsidian. Lava runs into the third cell, meets the water, and
     * stone forms there: that is the spot to mine, and it forms again. All the cells are walled in (ground that is already solid is left as it is,
     * anything else is filled with the wall block) and floored. The water and lava go in last, with the buckets.
     */
    public static BridgeJob generator(BlockPos floor, Item wall, net.minecraft.core.Direction direction) {
        return new BridgeJob(floor, floor.relative(direction, 3), wall, null, false, MIN_WIDTH, true);
    }

    /** The way the row of cells runs. */
    private net.minecraft.core.Direction generatorDirection() {
        return net.minecraft.core.Direction.fromDelta(Integer.signum(dest.getX() - start.getX()), 0, Integer.signum(dest.getZ() - start.getZ()));
    }

    private List<Placement> generatorPlacements() {
        net.minecraft.core.Direction along = generatorDirection() == null ? net.minecraft.core.Direction.EAST : generatorDirection();
        net.minecraft.core.Direction side = along.getClockWise();
        BlockPos[] cells = new BlockPos[4];
        for (int i = 0; i < 4; i++) {
            cells[i] = start.relative(along, i);
        }
        BlockPos pit = cells[1].below();
        // Where a worker stands to build it: beside the middle of the row, on the ground.
        Vec3 stand = Vec3.atBottomCenterOf(cells[1].relative(side, 2).above());
        List<Placement> result = new ArrayList<>();
        // 1. Dig out the four cells and the pit.
        for (BlockPos cell : cells) {
            result.add(new Placement(cell, Kind.DIG, stand));
        }
        result.add(new Placement(pit, Kind.DIG, stand));
        // 2. Floor under the cells and under the pit.
        for (int i : new int[] {0, 2, 3}) {
            result.add(new Placement(cells[i].below(), Kind.WALL, stand));
        }
        result.add(new Placement(pit.below(), Kind.WALL, stand));
        // 3. The pit's sides (its other two sides are the floors of the cells next to it).
        result.add(new Placement(pit.relative(side), Kind.WALL, stand));
        result.add(new Placement(pit.relative(side.getOpposite()), Kind.WALL, stand));
        // 4. The sides of the cells, and the two ends of the row.
        for (BlockPos cell : cells) {
            result.add(new Placement(cell.relative(side), Kind.WALL, stand));
            result.add(new Placement(cell.relative(side.getOpposite()), Kind.WALL, stand));
        }
        result.add(new Placement(cells[0].relative(along.getOpposite()), Kind.WALL, stand));
        result.add(new Placement(cells[3].relative(along), Kind.WALL, stand));
        // 5. A slab over the water cell, the cell next to it and the lava cell: not over the third cell, which is where the stone is mined.
        for (int i : new int[] {0, 1, 3}) {
            result.add(new Placement(cells[i].above(), Kind.SLAB, stand));
        }
        // 6. Only now the water (first cell) and the lava (fourth cell).
        result.add(new Placement(cells[0], Kind.WATER, stand));
        result.add(new Placement(cells[3], Kind.LAVA, stand));
        return result;
    }

    /** The spot in a generator where the stone forms: the third cell. */
    public BlockPos generatorSpot() {
        return start.relative(generatorDirection() == null ? net.minecraft.core.Direction.EAST : generatorDirection(), 2);
    }

    // ---- the nether portal ----

    /**
     * The smallest nether portal there is, as a recipe in the same form as a bridge: a frame of ten obsidian round an opening two wide and three
     * high (the four corners of the frame are not needed), standing on the ground with {@code ground} (the block clicked) under the left half of
     * its bottom edge. It runs along {@code along}. Once the frame is whole, the last step is lighting the opening with a flint and steel.
     */
    public static BridgeJob portal(BlockPos ground, net.minecraft.core.Direction along) {
        return new BridgeJob(ground, ground.relative(along, 1), net.minecraft.world.item.Items.OBSIDIAN, null, false, MIN_WIDTH, false, 0, true);
    }

    /** The way the portal's frame runs along the ground. */
    public net.minecraft.core.Direction portalDirection() {
        net.minecraft.core.Direction direction = net.minecraft.core.Direction.fromDelta(Integer.signum(dest.getX() - start.getX()), 0, Integer.signum(dest.getZ() - start.getZ()));
        return direction == null ? net.minecraft.core.Direction.EAST : direction;
    }

    /** The frame's blocks in the order they are placed (bottom, sides, top), as positions. */
    public List<BlockPos> portalFrame() {
        net.minecraft.core.Direction along = portalDirection();
        BlockPos bottom = start.above();
        List<BlockPos> frame = new ArrayList<>();
        for (int i = 0; i <= 1; i++) {
            frame.add(bottom.relative(along, i));
        }
        for (int up = 1; up <= 3; up++) {
            frame.add(bottom.relative(along, -1).above(up));
            frame.add(bottom.relative(along, 2).above(up));
        }
        for (int i = 0; i <= 1; i++) {
            frame.add(bottom.relative(along, i).above(4));
        }
        return frame;
    }

    /** The two-by-three opening, bottom row first: where the portal forms. */
    public List<BlockPos> portalOpening() {
        net.minecraft.core.Direction along = portalDirection();
        List<BlockPos> opening = new ArrayList<>();
        for (int up = 1; up <= 3; up++) {
            for (int i = 0; i <= 1; i++) {
                opening.add(start.above().relative(along, i).above(up));
            }
        }
        return opening;
    }

    private List<Placement> portalPlacements() {
        net.minecraft.core.Direction along = portalDirection();
        net.minecraft.core.Direction facing = along.getClockWise();
        // Where a worker stands: on the ground a couple of blocks in front of the middle of the frame (it can reach the top from there).
        Vec3 stand = Vec3.atBottomCenterOf(start.above().relative(along, 0).relative(facing, 2)).add(along.getStepX() * 0.5D, 0.0D, along.getStepZ() * 0.5D);
        List<Placement> result = new ArrayList<>();
        // 1. Clear the opening of whatever is in it (the worker digs it out).
        for (BlockPos cell : portalOpening()) {
            result.add(new Placement(cell, Kind.DIG, stand));
        }
        // 2. The frame.
        for (BlockPos pos : portalFrame()) {
            result.add(new Placement(pos, Kind.OBSIDIAN, stand));
        }
        // 3. Lit from the bottom of the opening.
        result.add(new Placement(portalOpening().get(0), Kind.IGNITE, stand));
        return result;
    }

    // ---- the tunnel ----

    /** The tunnel sizes, as (width, height) in blocks: 1x2 (one wide, two high), 2x2, 3x3 and 5x5. Indexed by the size number minus one. */
    private static final int[][] TUNNEL_SIZES = {{1, 2}, {2, 2}, {3, 3}, {5, 5}};
    public static final int TUNNEL_SIZE_COUNT = TUNNEL_SIZES.length;
    public static final int TUNNEL_MIN_LENGTH = 2;
    public static final int TUNNEL_MAX_LENGTH = 96;

    /**
     * A tunnel dug from {@code start} in a direction for {@code length} blocks: {@code size} (1 to 4, see {@link #TUNNEL_SIZES}) is the cross-section.
     * It is a recipe like the bridge's: the block at the start is the first one dug out, the floor is one block under the bottom of the tunnel, and
     * wherever that floor is missing the chosen block is placed to make a path.
     */
    public static BridgeJob tunnel(BlockPos start, net.minecraft.core.Direction direction, int length, Item block, int size) {
        int clamped = Math.max(TUNNEL_MIN_LENGTH, Math.min(TUNNEL_MAX_LENGTH, length));
        return new BridgeJob(start, start.relative(direction, clamped - 1), block, null, false, MIN_WIDTH, false, Math.max(1, Math.min(TUNNEL_SIZE_COUNT, size)), false);
    }

    public boolean isTunnel() {
        return tunnelSize > 0;
    }

    /** The way a tunnel runs. */
    public net.minecraft.core.Direction tunnelDirection() {
        net.minecraft.core.Direction direction = net.minecraft.core.Direction.fromDelta(Integer.signum(dest.getX() - start.getX()), 0, Integer.signum(dest.getZ() - start.getZ()));
        return direction == null ? net.minecraft.core.Direction.EAST : direction;
    }

    /** How many blocks long a tunnel is. */
    public int tunnelLength() {
        return Math.max(Math.abs(dest.getX() - start.getX()), Math.abs(dest.getZ() - start.getZ())) + 1;
    }

    private List<Placement> tunnelPlacements() {
        net.minecraft.core.Direction along = tunnelDirection();
        net.minecraft.core.Direction side = along.getClockWise();
        int tunnelWidth = TUNNEL_SIZES[tunnelSize - 1][0];
        int tunnelHeight = TUNNEL_SIZES[tunnelSize - 1][1];
        int low = -((tunnelWidth - 1) / 2);
        int high = tunnelWidth / 2;
        List<Placement> result = new ArrayList<>();
        for (int step = 0; step < tunnelLength(); step++) {
            BlockPos base = start.relative(along, step);
            // A worker builds a step from the one behind it, which is already dug and floored (the first from outside the start).
            Vec3 stand = Vec3.atBottomCenterOf(start.relative(along, step - 1));
            for (int offset = low; offset <= high; offset++) {
                for (int up = 0; up < tunnelHeight; up++) {
                    result.add(new Placement(base.relative(side, offset).above(up), Kind.DIG, stand));
                }
            }
            for (int offset = low; offset <= high; offset++) {
                result.add(new Placement(base.relative(side, offset).below(), Kind.WALL, stand));
            }
        }
        return result;
    }

    /** The placements of the biggest recipes are worked out once, not every time a worker looks: the last few jobs are kept. */
    private static final java.util.Map<BridgeJob, List<Placement>> PLACEMENT_CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<BridgeJob, List<Placement>>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<BridgeJob, List<Placement>> eldest) {
                    return size() > 16;
                }
            });


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
        tag.putBoolean("Generator", generator);
        tag.putInt("TunnelSize", tunnelSize);
        tag.putBoolean("Portal", portal);
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
        return start == null || dest == null || deck == null ? null : new BridgeJob(start, dest, deck, fence, tag.getBoolean("Torches"), tag.contains("Width") ? tag.getInt("Width") : 3, tag.getBoolean("Generator"), tag.getInt("TunnelSize"), tag.getBoolean("Portal"));
    }
}
