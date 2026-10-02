package com.projecthivemind.build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The shape of a tower or a shaft: where every block goes. See {@link TowerShape} for the two sizes. Either has walls
 * round the outside (or just a pillar in each corner), and one or two staircases winding round the inside edge.
 *
 * <p><b>A tower</b> rises from the block it is ordered on. The inside edge is a ring of cells; going once round it a
 * staircase climbs as many blocks as the ring has cells, one per cell, so the next turn is that far overhead. With two
 * staircases the second starts half a turn round, so they never share a cell at the same height. There is a door, 1 wide
 * and 2 high, in the middle of the south wall (and the north one for two staircases), and the first step is a cell or
 * two past it so that the way in is clear. At the top there is a flat deck over the inside with the walls rising a
 * block above it as a railing. The deck leaves out the cells over the last five steps of each staircase, which is where
 * you come up onto it: a tall mob climbing a step rises into the cell it just left, so the deck has to be well clear of
 * every step it can still be climbing.
 *
 * <p><b>A shaft</b> is dug down from the block, the same staircases running the other way, down round the edge of the
 * hole. The shaft is open at the top, level with the ground, and the walls line it from the ground down. At the bottom
 * there is a flat floor over the whole inside.
 *
 * <p>The plan is only a list of blocks. Whether each one is done is always read from the world, so a half-built tower,
 * or one built before a game restart, picks up where it was.
 */
public final class TowerPlan {
    /** The heights (or depths) a build can have: any whole number from the least to the most, as the player types it. */
    public static final int MIN_HEIGHT = 8;
    public static final int MAX_HEIGHT = 96;

    /** What is to be done at a block: put a plain block there, put a stair there, or just clear it out (digging). */
    public enum Kind {
        BLOCK, STAIR, DIG, TORCH
    }

    /** One block of the build. A stair faces the way it climbs; the layer is how far from the start it is. */
    public record Placement(BlockPos pos, Kind kind, @Nullable Direction facing, int layer) {
        public boolean stair() {
            return kind == Kind.STAIR;
        }

        public boolean torch() {
            return kind == Kind.TORCH;
        }

        public boolean dig() {
            return kind == Kind.DIG;
        }
    }

    private final TowerShape shape;
    private final TowerDirection direction;
    private final BlockPos base;
    private final int height;
    private final int groundY;
    private final boolean walls;
    private final boolean torches;
    private final List<Placement> placements = new ArrayList<>();

    /**
     * @param clicked   the block the build is ordered on: its base is centred on it. A tower stands on top of it, and a
     *                  shaft is dug down from it, so that its top row is the row of this block
     * @param height    from {@link #MIN_HEIGHT} to {@link #MAX_HEIGHT}: how far up or down
     * @param walls     true for walls all round, false for just a pillar in each of the four corners
     */
    public TowerPlan(BlockPos clicked, TowerShape shape, TowerDirection direction, int height, boolean walls) {
        this(clicked, shape, direction, height, walls, false);
    }

    /** As above; with {@code torches} a shaft with walls also gets a wall torch on the inside every few rows. */
    public TowerPlan(BlockPos clicked, TowerShape shape, TowerDirection direction, int height, boolean walls, boolean torches) {
        this.shape = shape;
        this.direction = direction;
        this.base = clicked.immutable();
        this.height = height;
        this.groundY = clicked.getY() + 1;
        this.walls = walls;
        this.torches = torches;
        if (direction == TowerDirection.UP) {
            planTower(walls);
        } else {
            planShaft(walls);
        }
    }

    /** How many steps a staircase has. The single one has one fewer so that it too ends in the middle of a side. */
    private int steps() {
        return shape == TowerShape.DOUBLE ? height : height - 1;
    }

    /** The ring index of staircase {@code staircase}'s step number {@code n}. */
    private int ringIndex(int staircase, int n) {
        int[][] ring = shape.ring();
        return (n + shape.startOffset() + staircase * ring.length / shape.staircases()) % ring.length;
    }

    private void planTower(boolean walls) {
        int radius = shape.radius();
        int[][] ring = shape.ring();
        int steps = steps();
        int insideCorner = -(radius - 1);

        // The ring cells under the deck's openings: the last five steps of every staircase.
        Set<Integer> openings = new HashSet<>();
        for (int staircase = 0; staircase < shape.staircases(); staircase++) {
            for (int n = steps - 5; n < steps; n++) {
                openings.add(ringIndex(staircase, n));
            }
        }

        for (int layer = 0; layer <= steps; layer++) {
            int y = groundY + layer;
            if (layer < steps) {
                for (int staircase = 0; staircase < shape.staircases(); staircase++) {
                    addStair(y, layer, ringIndex(staircase, layer), insideCorner, false);
                }
            }
            addWalls(y, layer, walls, true);
            if (hasCenterPillar() && layer < steps - 1) {
                // The deck above takes over from the top of the pillar.
                placements.add(new Placement(centerPos(y), Kind.BLOCK, null, layer));
            }
            if (layer == steps - 1) {
                for (int x = 0; x < shape.insideSize(); x++) {
                    for (int z = 0; z < shape.insideSize(); z++) {
                        int index = indexOf(ring, x, z);
                        if (index < 0 || !openings.contains(index)) {
                            placements.add(new Placement(new BlockPos(base.getX() + insideCorner + x, y, base.getZ() + insideCorner + z),
                                    Kind.BLOCK, null, layer));
                        }
                    }
                }
            }
        }
    }

    /**
     * The shaft. Layer 0 is the row of the clicked block, level with the ground, and each layer is one row down. The
     * steps go one a layer from there, and the floor is the row under the last one. Everything inside that is not a
     * step is dug out; the walls line the outside from the ground row down to the floor row.
     */
    private void planShaft(boolean walls) {
        int steps = steps();
        int insideCorner = -(shape.radius() - 1);
        int top = base.getY();
        Set<BlockPos> stairs = new HashSet<>();
        for (int layer = 0; layer < steps; layer++) {
            for (int staircase = 0; staircase < shape.staircases(); staircase++) {
                BlockPos pos = cellPos(insideCorner, ringIndex(staircase, layer), top - layer);
                stairs.add(pos);
            }
        }

        for (int layer = 0; layer <= steps; layer++) {
            int y = top - layer;
            boolean floorRow = layer == steps;
            // The digging first, so that it comes before the stairs and walls of the same layer.
            if (!floorRow) {
                for (int x = 0; x < shape.insideSize(); x++) {
                    for (int z = 0; z < shape.insideSize(); z++) {
                        BlockPos pos = new BlockPos(base.getX() + insideCorner + x, y, base.getZ() + insideCorner + z);
                        if (hasCenterPillar() && pos.equals(centerPos(y))) {
                            placements.add(new Placement(pos, Kind.BLOCK, null, layer));
                        } else if (!stairs.contains(pos)) {
                            placements.add(new Placement(pos, Kind.DIG, null, layer));
                        }
                    }
                }
                for (int staircase = 0; staircase < shape.staircases(); staircase++) {
                    addStair(y, layer, ringIndex(staircase, layer), insideCorner, true);
                }
            } else {
                // The floor: the whole inside, flat.
                for (int x = 0; x < shape.insideSize(); x++) {
                    for (int z = 0; z < shape.insideSize(); z++) {
                        placements.add(new Placement(new BlockPos(base.getX() + insideCorner + x, y, base.getZ() + insideCorner + z),
                                Kind.BLOCK, null, layer));
                    }
                }
            }
            addWalls(y, layer, walls, false);
            if (torches && walls && layer % TORCH_EVERY == 3 && layer < steps) {
                addTorch(y, layer, insideCorner);
            }
        }
    }

    /** A wall torch goes on every this many rows of a shaft. */
    private static final int TORCH_EVERY = 4;
    /** A shaft with pillars instead of walls still has a full wall for this many rows at the top: the way out from the stairs to the ground. */
    private static final int PILLAR_WALL_DEPTH = 2;

    /**
     * A torch on the inside of the wall, in the ring cell a quarter of a turn from where the stairs are this row, so it is never
     * where a stair is. It faces into the shaft, away from the wall it is on. A cell in a corner is on one of the two walls.
     */
    private void addTorch(int y, int layer, int insideCorner) {
        int[][] ring = shape.ring();
        int index = (ringIndex(0, layer) + ring.length / 4) % ring.length;
        int[] cell = ring[index];
        int last = shape.insideSize() - 1;
        Direction facing;
        if (cell[1] == 0) {
            facing = Direction.SOUTH;
        } else if (cell[1] == last) {
            facing = Direction.NORTH;
        } else if (cell[0] == 0) {
            facing = Direction.EAST;
        } else {
            facing = Direction.WEST;
        }
        placements.add(new Placement(cellPos(insideCorner, index, y), Kind.TORCH, facing, layer));
    }

    /**
     * The single staircase has a pillar up the middle of it: the middle of its inside is one cell, and left empty it is a
     * hole that anything walking the stairs can fall down.
     */
    private boolean hasCenterPillar() {
        return shape == TowerShape.SPIRAL;
    }

    private BlockPos centerPos(int y) {
        return new BlockPos(base.getX(), y, base.getZ());
    }

    private BlockPos cellPos(int insideCorner, int ringIndex, int y) {
        int[] cell = shape.ring()[ringIndex];
        return new BlockPos(base.getX() + insideCorner + cell[0], y, base.getZ() + insideCorner + cell[1]);
    }

    /**
     * A stair, facing the way it climbs. In a tower that is on to the next cell round the ring; in a shaft the stairs
     * go down the ring, so climbing is back to the previous cell.
     */
    private void addStair(int y, int layer, int ringIndex, int insideCorner, boolean down) {
        int[][] ring = shape.ring();
        int[] cell = ring[ringIndex];
        int[] toward = ring[(ringIndex + (down ? ring.length - 1 : 1)) % ring.length];
        Direction facing = Direction.fromDelta(toward[0] - cell[0], 0, toward[1] - cell[1]);
        placements.add(new Placement(cellPos(insideCorner, ringIndex, y), Kind.STAIR, facing, layer));
    }

    /**
     * The outside of the build in one row: the wall all round, or just the corners. A tower has its doors in this row
     * low down. In a shaft that has no walls (corners only) the ground that would have been the wall is dug out too, so
     * the hole is the full size, with just the pillars standing in it.
     */
    private void addWalls(int y, int layer, boolean walls, boolean tower) {
        int radius = shape.radius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                BlockPos pos = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
                if (tower) {
                    boolean door = dx == 0 && layer <= 1 && (dz == radius || (shape == TowerShape.DOUBLE && dz == -radius));
                    if ((walls || corner) && !door) {
                        placements.add(new Placement(pos, Kind.BLOCK, null, layer));
                    }
                } else if (walls || corner || layer < PILLAR_WALL_DEPTH) {
                    placements.add(new Placement(pos, Kind.BLOCK, null, layer));
                } else if (layer < steps()) {
                    placements.add(new Placement(pos, Kind.DIG, null, layer));
                }
            }
        }
    }

    /** The index of an inside cell in the ring, or -1 for a cell in the middle. */
    private static int indexOf(int[][] ring, int x, int z) {
        for (int i = 0; i < ring.length; i++) {
            if (ring[i][0] == x && ring[i][1] == z) {
                return i;
            }
        }
        return -1;
    }

    public boolean torches() {
        return torches;
    }

    /** True for walls all round, false for just corner pillars. */
    public boolean walls() {
        return walls;
    }

    public TowerShape shape() {
        return shape;
    }

    public TowerDirection direction() {
        return direction;
    }

    public BlockPos base() {
        return base;
    }

    public int height() {
        return height;
    }

    /** The height a worker stands at on the ground beside the build (the block above the clicked one). */
    public int groundY() {
        return groundY;
    }

    public List<Placement> placements() {
        return placements;
    }

    /** How many plain blocks (walls, deck, floor) the build takes. */
    public int blockCount() {
        return (int) placements.stream().filter(p -> p.kind() == Kind.BLOCK).count();
    }

    /** How many stairs the build takes. */
    public int stairCount() {
        return (int) placements.stream().filter(Placement::stair).count();
    }

    /** How many blocks have to be dug out (a shaft only). */
    public int digCount() {
        return (int) placements.stream().filter(Placement::dig).count();
    }

    /** The counts for a build, without keeping the plan: blocks, stairs and blocks to dig. Used by the order screen. */
    public static int[] counts(TowerShape shape, TowerDirection direction, int height, boolean walls) {
        TowerPlan plan = new TowerPlan(BlockPos.ZERO, shape, direction, height, walls);
        return new int[] {plan.blockCount(), plan.stairCount(), plan.digCount()};
    }
}
