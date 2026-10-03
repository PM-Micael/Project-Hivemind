package com.projecthivemind.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A tower being built: the plan, what it is made of, and which worker is on which block. Lives on the Heart (it is not
 * saved; after a restart the tower is simply ordered again, and what was already built is recognised from the world).
 *
 * <p>Workers climb the tower's own staircases as they build it. A worker can place a block that is within reach of
 * somewhere it can stand: a step that is already built, or the ground by the tower. So the staircases go up first, a
 * step at a time, and the walls follow wherever a worker can get to.
 */
public final class TowerBuild {
    /** How far from a spot a worker can place from, measured eye to block centre. */
    public static final double STAND_REACH = 5.0D;
    /** How far from the worker's eye a block can be when it is actually placed (a little slack for it standing off). */
    public static final double PLACE_REACH = 6.0D;
    private static final double EYE_HEIGHT = 1.62D;
    private static final int CLAIM_TICKS = 400;

    /** What a worker has been given to do: place this block, standing here. */
    public record Job(TowerPlan.Placement placement, Vec3 stand) {
    }

    public enum Result {
        PLACED, NO_MATERIAL, SKIPPED
    }

    private record Claim(UUID worker, long until) {
    }

    private final TowerPlan plan;
    private final TowerSet set;
    private final Map<BlockPos, Claim> claims = new HashMap<>();
    /** Blocks that cannot be built because something unbreakable is there. They are left out. */
    private final Set<BlockPos> skipped = new HashSet<>();
    /** How many times the water (or lava) at a spot of the hole was removed, and how many times before giving up on it. */
    private final java.util.Map<BlockPos, Integer> waterClears = new java.util.HashMap<>();
    private static final int MAX_WATER_CLEARS = 4;
    private long lastWaitNotice = Long.MIN_VALUE;

    public TowerBuild(TowerPlan plan, TowerSet set) {
        this.plan = plan;
        this.set = set;
    }

    /** How far from a spot a worker can place from. The same for towers and shafts: workers always have to work round their reach. */
    public double standReach() {
        return STAND_REACH;
    }

    /** How far from its eye a block can be when a worker places it. */
    public double placeReach() {
        return PLACE_REACH;
    }

    /** Written for the Heart's saved data: what was ordered, which is all that is needed to make the plan again. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", NbtUtils.writeBlockPos(plan.base()));
        tag.putInt("Shape", plan.shape().ordinal());
        tag.putInt("Direction", plan.direction().ordinal());
        tag.putInt("Height", plan.height());
        tag.putBoolean("Walls", plan.walls());
        tag.putBoolean("Torches", plan.torches());
        tag.put("WallItems", itemNames(set.walls()));
        tag.put("StairItems", itemNames(set.stairs()));
        return tag;
    }

    private static ListTag itemNames(List<Item> items) {
        ListTag list = new ListTag();
        for (Item item : items) {
            list.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(item).toString()));
        }
        return list;
    }

    private static List<Item> items(ListTag names) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(names.getString(i));
            if (id != null) {
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(items::add);
            }
        }
        return items;
    }

    /** Read back by {@link #save}, or null if it cannot be. What was already built is read from the world as ever. */
    @Nullable
    public static TowerBuild load(CompoundTag tag) {
        BlockPos pos = NbtUtils.readBlockPos(tag, "Pos").orElse(null);
        if (pos == null) {
            return null;
        }
        TowerPlan plan = new TowerPlan(pos, TowerShape.byIndex(tag.getInt("Shape")), TowerDirection.byIndex(tag.getInt("Direction")),
                tag.getInt("Height"), tag.getBoolean("Walls"), tag.getBoolean("Torches"));
        List<Item> walls = items(tag.getList("WallItems", Tag.TAG_STRING));
        List<Item> stairs = items(tag.getList("StairItems", Tag.TAG_STRING));
        return walls.isEmpty() || stairs.isEmpty() ? null : new TowerBuild(plan, new TowerSet(walls, stairs));
    }

    public TowerPlan plan() {
        return plan;
    }

    public TowerSet set() {
        return set;
    }

    /** Whether a block has already been built, judged from the world. */
    public boolean isDone(ServerLevel level, TowerPlan.Placement placement) {
        if (skipped.contains(placement.pos())) {
            return true;
        }
        BlockState state = level.getBlockState(placement.pos());
        if (placement.dig()) {
            // Water in the way is not "dug": it has to be taken out.
            return (state.isAir() || state.canBeReplaced()) && state.getFluidState().isEmpty();
        }
        if (placement.torch()) {
            // Done once there is anything in the cell: the torch, or something that took its place.
            return !state.isAir();
        }
        if (placement.stair()) {
            return state.getBlock() instanceof StairBlock && state.getValue(StairBlock.FACING) == placement.facing();
        }
        return set.isWallBlock(state);
    }

    public boolean isComplete(ServerLevel level) {
        for (TowerPlan.Placement placement : plan.placements()) {
            if (!isDone(level, placement)) {
                return false;
            }
        }
        return true;
    }

    /** Places a worker can stand to build from: the built steps, and the ground by the tower and in it. */
    private List<Vec3> stations(ServerLevel level) {
        List<Vec3> stations = new ArrayList<>();
        BlockPos base = plan.base();
        double ground = plan.groundY();
        if (plan.direction() == TowerDirection.UP && plan.shape() == TowerShape.DOUBLE) {
            // The middle of a double tower is open ground. A shaft has a hole there, and a single staircase a pillar.
            stations.add(new Vec3(base.getX() + 0.5D, ground, base.getZ() + 0.5D));
        }
        int radius = plan.shape().radius();
        stations.add(new Vec3(base.getX() + 0.5D, ground, base.getZ() + radius + 1.5D));
        stations.add(new Vec3(base.getX() + 0.5D, ground, base.getZ() - radius - 0.5D));
        for (TowerPlan.Placement placement : plan.placements()) {
            if (placement.stair() && isDone(level, placement)) {
                BlockPos pos = placement.pos();
                stations.add(new Vec3(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D));
            }
        }
        return stations;
    }

    /**
     * The next block for this worker, or null if there is nothing it can do right now. Blocks low in the tower come
     * first. A block is only given out if it is within reach of somewhere the worker can stand, and the nearest such
     * spot is where it is told to go. Blocks someone else is on are left alone.
     */
    @Nullable
    public Job claimNext(ServerLevel level, Mob worker) {
        long now = level.getGameTime();
        claims.values().removeIf(claim -> claim.until() <= now);
        List<Vec3> stations = null;
        Job best = null;
        double bestScore = Double.MAX_VALUE;
        AABB workerBox = worker.getBoundingBox();
        double standReach = standReach();

        for (TowerPlan.Placement placement : plan.placements()) {
            if (isDone(level, placement)) {
                continue;
            }
            Claim claim = claims.get(placement.pos());
            if (claim != null && !claim.worker().equals(worker.getUUID())) {
                continue;
            }
            // A torch waits for the wall it goes on.
            if (placement.torch() && !level.getBlockState(placement.pos().relative(placement.facing().getOpposite()))
                    .isFaceSturdy(level, placement.pos().relative(placement.facing().getOpposite()), placement.facing())) {
                continue;
            }
            // Not a block the worker is standing in.
            if (new AABB(placement.pos()).intersects(workerBox)) {
                continue;
            }
            if (best != null && placement.layer() * 1000.0D > bestScore) {
                // Everything from here on is higher than what was already found.
                break;
            }
            if (stations == null) {
                stations = stations(level);
            }
            Vec3 target = Vec3.atCenterOf(placement.pos());
            Vec3 nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (Vec3 station : stations) {
                Vec3 eye = station.add(0.0D, EYE_HEIGHT, 0.0D);
                if (eye.distanceToSqr(target) <= standReach * standReach) {
                    double distance = worker.position().distanceToSqr(station);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = station;
                    }
                }
            }
            if (nearest == null) {
                continue;
            }
            double score = placement.layer() * 1000.0D + Math.sqrt(nearestDistance);
            if (score < bestScore) {
                bestScore = score;
                best = new Job(placement, nearest);
            }
        }
        if (best != null) {
            claims.put(best.placement().pos(), new Claim(worker.getUUID(), now + CLAIM_TICKS));
        }
        return best;
    }

    /** Let go of the claim on a block once it has been dealt with (dug out). */
    public void releaseClaim(BlockPos pos) {
        claims.remove(pos);
    }

    /** Leave a block out of the build: it cannot be built or dug (something unbreakable is there). */
    public void skip(BlockPos pos) {
        skipped.add(pos);
        claims.remove(pos);
    }

    /** Give back whatever this worker had claimed. */
    public void release(UUID worker) {
        claims.values().removeIf(claim -> claim.worker().equals(worker));
    }

    /**
     * Place the block now, taking what it is made of from the hive's storage. Whatever is in the way is broken first
     * (its drops are left for the collectors); something that cannot be broken is left out of the tower.
     */
    public Result place(ServerLevel level, HiveHeart heart, Mob worker, TowerPlan.Placement placement) {
        BlockPos pos = placement.pos();
        if (placement.dig()) {
            // Digging needs no material: the block is broken, and its drops go into the hive.
            BlockState existing = level.getBlockState(pos);
            if (!existing.getFluidState().isEmpty() && (existing.isAir() || existing.canBeReplaced())) {
                // Water (or lava) where the hole goes: the worker removes it. If it keeps flowing back, it is left after a few tries.
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                if (waterClears.merge(pos.immutable(), 1, Integer::sum) >= MAX_WATER_CLEARS) {
                    skipped.add(pos);
                }
                worker.swing(InteractionHand.MAIN_HAND);
                claims.remove(pos);
                return Result.PLACED;
            }
            if (!existing.isAir() && !existing.canBeReplaced()) {
                if (existing.getDestroySpeed(level, pos) < 0.0F || existing.hasBlockEntity()) {
                    skipped.add(pos);
                    return Result.SKIPPED;
                }
                // What a shaft's digging breaks goes into the hive's inventory.
                com.projecthivemind.entity.HiveDrops.store(level, heart, pos, existing, level.getBlockEntity(pos), worker, net.minecraft.world.item.ItemStack.EMPTY);
                level.destroyBlock(pos, false, worker);
                heart.food().exhaust(com.projecthivemind.HiveFood.BREAK_BLOCK);
            }
            worker.swing(InteractionHand.MAIN_HAND);
            claims.remove(pos);
            return Result.PLACED;
        }
        if (placement.torch()) {
            // A torch comes from the hive's torches, not from what the tower is made of; with none it is left out.
            BlockState torch = net.minecraft.world.level.block.Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, placement.facing());
            if (heart.getStorage().countItem(Items.TORCH) <= 0 || !level.getBlockState(pos).isAir() || !torch.canSurvive(level, pos)) {
                skipped.add(pos);
                return Result.SKIPPED;
            }
            heart.getStorage().removeItemType(Items.TORCH, 1);
            level.setBlock(pos, torch, Block.UPDATE_ALL);
            level.playSound(null, pos, torch.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
            worker.swing(InteractionHand.MAIN_HAND);
            claims.remove(pos);
            return Result.PLACED;
        }
        Item item = placement.stair() ? set.availableStair(heart.getStorage()) : set.availableWall(heart.getStorage());
        if (item == null || TowerSet.count(heart.getStorage(), item) <= 0 || !(item instanceof BlockItem blockItem)) {
            return Result.NO_MATERIAL;
        }

        BlockState existing = level.getBlockState(pos);
        if (!existing.canBeReplaced()) {
            if (existing.getDestroySpeed(level, pos) < 0.0F || existing.hasBlockEntity()) {
                skipped.add(pos);
                return Result.SKIPPED;
            }
            com.projecthivemind.entity.HiveDrops.store(level, heart, pos, existing, level.getBlockEntity(pos), worker, net.minecraft.world.item.ItemStack.EMPTY);
            level.destroyBlock(pos, false, worker);
            heart.food().exhaust(com.projecthivemind.HiveFood.BREAK_BLOCK);
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (placement.stair()) {
            state = state.setValue(StairBlock.FACING, placement.facing());
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
        heart.getStorage().removeItemType(item, 1);
        level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        worker.swing(InteractionHand.MAIN_HAND);
        claims.remove(pos);
        return Result.PLACED;
    }

    /** Says whether it is time to tell the player the build is waiting for material (at most every 15 seconds). */
    public boolean shouldNotifyWaiting(long gameTime) {
        if (gameTime - lastWaitNotice >= 300L) {
            lastWaitNotice = gameTime;
            return true;
        }
        return false;
    }
}
