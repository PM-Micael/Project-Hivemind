package com.projecthivemind.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveWorker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * One construction of the hive: a bridge, a staircase dug down, or a tower or shaft. It is marked in the world by a construction block (at
 * {@link #anchor}), and the Heart keeps this: what is being built and with which settings, which workers are on it, and whether it is done. The
 * workers do the building with the same recipes as ever (see BridgeJob, StairDig and TowerBuild); this is only where their job comes from.
 */
public final class Construction {
    public enum Kind {
        BRIDGE, STAIRCASE, TOWER
    }

    /** What the highlight of the block shows: red when no one works on it, yellow while workers do, green once it is done. */
    public enum State {
        IDLE, WORKING, DONE
    }

    public static final int BRIDGE_MIN_WORKERS = 1;
    public static final int BRIDGE_MAX_WORKERS = 4;
    public static final int STAIRCASE_MIN_WORKERS = 1;
    public static final int STAIRCASE_MAX_WORKERS = 3;

    private final ResourceKey<Level> dimension;
    private final BlockPos anchor;
    private final Kind kind;
    @Nullable
    private BridgeJob bridge;
    @Nullable
    private StairDig stairs;
    @Nullable
    private TowerBuild tower;
    /** Which materials the tower may be made of, as ordered (see TowerSet#bit): kept so the order can be edited. */
    private int towerMaterials;
    private final Set<UUID> workers = new LinkedHashSet<>();
    private boolean done;
    /** The ground columns this construction uses, with a margin round them (see {@link #footprint}); worked out when first asked for. */
    @Nullable
    private Set<Long> footprint;

    private Construction(ResourceKey<Level> dimension, BlockPos anchor, Kind kind) {
        this.dimension = dimension;
        this.anchor = anchor.immutable();
        this.kind = kind;
    }

    public static Construction ofBridge(ResourceKey<Level> dimension, BlockPos anchor, BridgeJob bridge) {
        Construction construction = new Construction(dimension, anchor, Kind.BRIDGE);
        construction.bridge = bridge;
        return construction;
    }

    public static Construction ofStairs(ResourceKey<Level> dimension, BlockPos anchor, StairDig stairs) {
        Construction construction = new Construction(dimension, anchor, Kind.STAIRCASE);
        construction.stairs = stairs;
        return construction;
    }

    public static Construction ofTower(ResourceKey<Level> dimension, BlockPos anchor, TowerBuild tower, int materials) {
        Construction construction = new Construction(dimension, anchor, Kind.TOWER);
        construction.tower = tower;
        construction.towerMaterials = materials;
        return construction;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public Kind kind() {
        return kind;
    }

    @Nullable
    public BridgeJob bridge() {
        return bridge;
    }

    @Nullable
    public StairDig stairs() {
        return stairs;
    }

    @Nullable
    public TowerBuild tower() {
        return tower;
    }

    public boolean done() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    /** The fewest workers it can be worked on with. */
    public int minWorkers() {
        return switch (kind) {
            case BRIDGE -> BRIDGE_MIN_WORKERS;
            case STAIRCASE -> STAIRCASE_MIN_WORKERS;
            case TOWER -> tower.plan().shape().minWorkers();
        };
    }

    /** The most workers that can be on it at once. */
    public int maxWorkers() {
        return switch (kind) {
            case BRIDGE -> BRIDGE_MAX_WORKERS;
            case STAIRCASE -> STAIRCASE_MAX_WORKERS;
            case TOWER -> tower.plan().shape().maxWorkers();
        };
    }

    public Set<UUID> workers() {
        return workers;
    }

    public boolean hasWorker(UUID worker) {
        return workers.contains(worker);
    }

    public State state(int aliveWorkers) {
        return done ? State.DONE : aliveWorkers >= minWorkers() ? State.WORKING : State.IDLE;
    }

    // ---- the ground it uses ----

    /**
     * The columns of ground this construction uses, each with the columns round it (one block of margin), as keys (see HiveConstructions#columnKey).
     * Workers that flatten the ground leave these alone: a shaft would be filled in as a gap, and the ground under a bridge or round a tower
     * should stay as it is.
     */
    public Set<Long> footprint() {
        if (footprint == null) {
            Set<Long> columns = new java.util.HashSet<>();
            switch (kind) {
                case TOWER -> {
                    for (TowerPlan.Placement placement : tower.plan().placements()) {
                        addWithMargin(columns, placement.pos().getX(), placement.pos().getZ());
                    }
                }
                case BRIDGE -> {
                    for (BridgeJob.Placement placement : bridge.placements()) {
                        addWithMargin(columns, placement.pos().getX(), placement.pos().getZ());
                    }
                }
                case STAIRCASE -> {
                    // The corridor: one column further on for every step down (and a sensible most).
                    int steps = Math.min(128, Math.max(0, stairs.start().getY() - stairs.stopY()));
                    for (int step = 0; step <= steps; step++) {
                        BlockPos column = stairs.start().relative(stairs.direction(), step);
                        addWithMargin(columns, column.getX(), column.getZ());
                    }
                }
            }
            addWithMargin(columns, anchor.getX(), anchor.getZ());
            footprint = columns;
        }
        return footprint;
    }

    private static void addWithMargin(Set<Long> columns, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                columns.add(com.projecthivemind.HiveConstructions.columnKey(x + dx, z + dz));
            }
        }
    }

    // ---- the settings, as the order screens edit them ----

    /**
     * The settings as one tag, the same one {@link #applyConfig} takes: what a screen shows when the order is opened again. Bridge: Deck, Fence
     * (absent for none), Torches, Width. Staircase: Direction, StopY, Torches. Tower: Shape, Direction, Height, Walls, Torches, Materials.
     */
    public CompoundTag configTag() {
        CompoundTag tag = new CompoundTag();
        switch (kind) {
            case BRIDGE -> {
                tag.putString("Deck", BuiltInRegistries.ITEM.getKey(bridge.deck()).toString());
                tag.putBoolean("Generator", bridge.generator());
                tag.putBoolean("Portal", bridge.portal());
                if (bridge.isTunnel()) {
                    tag.putInt("TunnelSize", bridge.tunnelSize());
                    tag.putInt("Length", bridge.tunnelLength());
                    tag.putInt("Direction", bridge.tunnelDirection().get2DDataValue());
                }
                if (bridge.fence() != null) {
                    tag.putString("Fence", BuiltInRegistries.ITEM.getKey(bridge.fence()).toString());
                }
                tag.putBoolean("Torches", bridge.torches());
                tag.putInt("Width", bridge.width());
            }
            case STAIRCASE -> {
                tag.putInt("Direction", stairs.direction().get2DDataValue());
                tag.putInt("StopY", stairs.stopY());
                tag.putBoolean("Torches", stairs.torches());
                tag.put("Start", NbtUtils.writeBlockPos(stairs.start()));
            }
            case TOWER -> {
                TowerPlan plan = tower.plan();
                tag.putInt("Shape", plan.shape().ordinal());
                tag.putInt("BaseY", plan.base().getY());
                tag.putInt("Direction", plan.direction().ordinal());
                tag.putInt("Height", plan.height());
                tag.putBoolean("Walls", plan.walls());
                tag.putBoolean("Torches", plan.torches());
                tag.putInt("Materials", towerMaterials);
            }
        }
        return tag;
    }

    /**
     * Change the settings to these (a tag as {@link #configTag} writes). Checked the way the first order was: an empty result means it is done, and
     * otherwise it is the translation key of what was wrong. A finished construction that is changed is no longer finished: its workers check it again.
     */
    public Optional<String> applyConfig(CompoundTag tag, Container storage, int minBuildHeight, int maxBuildHeight) {
        switch (kind) {
            case BRIDGE -> {
                Item deck = item(tag.getString("Deck"));
                Item fence = tag.contains("Fence") ? item(tag.getString("Fence")) : null;
                if (deck == null || HiveWorker.fillBlock(deck) == null || (tag.contains("Fence") && (fence == null || HiveWorker.fenceBlock(fence) == null))) {
                    return Optional.of("message.projecthivemind.construction_bad_options");
                }
                if (bridge.generator() || bridge.portal()) {
                    return Optional.of("message.projecthivemind.construction_bad_options");
                }
                if (bridge.isTunnel()) {
                    int length = tag.getInt("Length");
                    int size = tag.getInt("TunnelSize");
                    if (length < BridgeJob.TUNNEL_MIN_LENGTH || length > BridgeJob.TUNNEL_MAX_LENGTH || size < 1 || size > BridgeJob.TUNNEL_SIZE_COUNT) {
                        return Optional.of("message.projecthivemind.construction_bad_options");
                    }
                    Direction way = Direction.from2DDataValue(tag.getInt("Direction") & 3);
                    bridge = BridgeJob.tunnel(bridge.start(), way, length, deck, size);
                    footprint = null;
                    done = false;
                    break;
                }
                bridge = new BridgeJob(bridge.start(), bridge.dest(), deck, fence, tag.getBoolean("Torches"), tag.getInt("Width"));
            }
            case STAIRCASE -> {
                int stopY = Math.max(minBuildHeight + 1, Math.min(tag.getInt("StopY"), stairs.start().getY()));
                stairs = new StairDig(stairs.start(), Direction.from2DDataValue(tag.getInt("Direction") & 3), stopY, tag.getBoolean("Torches"));
            }
            case TOWER -> {
                int height = tag.getInt("Height");
                TowerShape shape = TowerShape.byIndex(tag.getInt("Shape"));
                TowerDirection direction = TowerDirection.byIndex(tag.getInt("Direction"));
                BlockPos base = tower.plan().base();
                if (height < TowerPlan.MIN_HEIGHT || height > maxBuildHeight - minBuildHeight
                        || (direction == TowerDirection.UP && base.getY() + height + 2 >= maxBuildHeight)
                        || (direction == TowerDirection.DOWN && base.getY() - height < minBuildHeight)) {
                    return Optional.of("message.projecthivemind.tower_bad_site");
                }
                if (workers.size() > shape.maxWorkers()) {
                    return Optional.of("message.projecthivemind.construction_too_many_workers");
                }
                TowerPlan plan = new TowerPlan(base, shape, direction, height, true, tag.getBoolean("Torches"));
                int materials = tag.getInt("Materials") & ((1 << TowerMaterial.values().length) - 1);
                Optional<TowerSet> set = materials == 0 ? Optional.empty() : TowerSet.choose(materials, storage, plan.blockCount(), plan.stairCount());
                if (set.isEmpty()) {
                    return Optional.of("message.projecthivemind.tower_no_material");
                }
                TowerBuild previous = tower;
                tower = new TowerBuild(plan, set.get());
                // The layers already walled for water stay walled (same site, new settings).
                tower.inheritWetLayers(previous.wetLayers());
                towerMaterials = materials;
            }
        }
        done = false;
        footprint = null;
        return Optional.empty();
    }

    @Nullable
    private static Item item(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    // ---- saving ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.location().toString());
        tag.put("Anchor", NbtUtils.writeBlockPos(anchor));
        tag.putInt("Kind", kind.ordinal());
        tag.putBoolean("Done", done);
        ListTag list = new ListTag();
        workers.forEach(id -> list.add(NbtUtils.createUUID(id)));
        tag.put("Workers", list);
        switch (kind) {
            case BRIDGE -> tag.put("Bridge", bridge.save());
            case STAIRCASE -> tag.put("Stairs", stairs.save());
            case TOWER -> {
                tag.put("Tower", tower.save());
                tag.putInt("Materials", towerMaterials);
            }
        }
        return tag;
    }

    @Nullable
    public static Construction load(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("Dimension"));
        BlockPos anchor = NbtUtils.readBlockPos(tag, "Anchor").orElse(null);
        int kindIndex = tag.getInt("Kind");
        if (dimensionId == null || anchor == null || kindIndex < 0 || kindIndex >= Kind.values().length) {
            return null;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        Construction construction;
        switch (Kind.values()[kindIndex]) {
            case BRIDGE -> {
                BridgeJob job = tag.contains("Bridge") ? BridgeJob.load(tag.getCompound("Bridge")) : null;
                if (job == null) {
                    return null;
                }
                construction = ofBridge(dimension, anchor, job);
            }
            case STAIRCASE -> {
                StairDig dig = tag.contains("Stairs") ? StairDig.load(tag.getCompound("Stairs")) : null;
                if (dig == null) {
                    return null;
                }
                construction = ofStairs(dimension, anchor, dig);
            }
            default -> {
                TowerBuild build = tag.contains("Tower") ? TowerBuild.load(tag.getCompound("Tower")) : null;
                if (build == null) {
                    return null;
                }
                construction = ofTower(dimension, anchor, build, tag.getInt("Materials"));
            }
        }
        construction.done = tag.getBoolean("Done");
        for (Tag entry : tag.getList("Workers", Tag.TAG_INT_ARRAY)) {
            construction.workers.add(NbtUtils.loadUUID(entry));
        }
        return construction;
    }

    /** The workers listed, as a fresh list (so it can be walked while they are changed). */
    public List<UUID> workerList() {
        return new ArrayList<>(workers);
    }
}
