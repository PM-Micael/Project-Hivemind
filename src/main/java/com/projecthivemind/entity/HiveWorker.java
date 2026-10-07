package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;
import com.projecthivemind.WorkerBehavior;
import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;
import com.projecthivemind.client.ClientSelection;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.level.Level;

/**
 * Worker unit. Looks like a skeleton but is a passive unit that only does what its owner commands: it walks where it
 * is sent, digs blocks with tools from the hive, and interacts with blocks. No sunburn, no combat AI, never despawns.
 */
public class HiveWorker extends Skeleton implements HiveUnit {
    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveWorker.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final String HEART_TAG = "HiveHeartId";

    @Nullable
    private UUID heartId;
    private static final String GEAR_VERSION_TAG = "GearVersion";

    @Nullable
    private UnitAction action;
    /** The job this unit is on or has set aside (see HiveUnit#job). Not saved. */
    @Nullable
    private UnitAction job;
    private boolean resumeJob = true;

    /** True if composters take this item (seeds, saplings, leaves, crops...). Works on the client too, where the data map may not be. */
    public static boolean compostable(net.minecraft.world.item.Item item) {
        return net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.containsKey(item)
                || net.minecraft.world.level.block.ComposterBlock.getValue(new net.minecraft.world.item.ItemStack(item)) > 0.0F;
    }

    /** The block this worker fills gaps in the ground with, when set to flatten it. Chosen in the hive menu; saved. */
    @Nullable
    private net.minecraft.world.item.Item fillItem;

    @Nullable
    public net.minecraft.world.item.Item fillItem() {
        return fillItem;
    }

    /** Choose the fill block: an item that is not a plain solid block clears the choice instead. */
    public void setFillItem(@Nullable net.minecraft.world.item.Item item) {
        this.fillItem = item != null && fillBlock(item) != null ? item : null;
    }

    /**
     * The block this item places as a fill: a plain full block with nothing special about it. Nothing that falls, holds items or
     * is a block entity, so a gap filled with it stays filled and a worker can place it with no more than its own hands.
     */
    @Nullable
    public static net.minecraft.world.level.block.Block fillBlock(net.minecraft.world.item.Item item) {
        if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
            return null;
        }
        net.minecraft.world.level.block.Block block = blockItem.getBlock();
        net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
        boolean plain = !(block instanceof net.minecraft.world.level.block.EntityBlock)
                && !(block instanceof net.minecraft.world.level.block.FallingBlock)
                && state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && state.getFluidState().isEmpty();
        return plain ? block : null;
    }

    /** Take a torch from the hive and stand it at the spot; false if the hive has none or it cannot stand there. */
    private boolean placeTorch(HiveHeart heart, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState torch = net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState();
        if (!torch.canSurvive(this.level(), pos) || heart.getStorage().countItem(net.minecraft.world.item.Items.TORCH) <= 0) {
            return false;
        }
        heart.getStorage().removeItemType(net.minecraft.world.item.Items.TORCH, 1);
        this.level().setBlock(pos, torch, net.minecraft.world.level.block.Block.UPDATE_ALL);
        this.level().playSound(null, pos, torch.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.8F);
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

    /** The block this worker is building the border wall out of, if it was given the job. Saved. */
    @Nullable
    private net.minecraft.world.item.Item wallItem;
    private int nextWallScan;
    /** The spot this worker keeps mining: whenever there is a block there it digs it (a cobblestone generator's stone, say). Saved. */
    @Nullable
    private BlockPos repeatDig;
    private int nextRepeatScan;

    @Nullable
    public BlockPos repeatDig() {
        return repeatDig;
    }

    public void setRepeatDig(@Nullable BlockPos pos) {
        this.repeatDig = pos;
        this.nextRepeatScan = 0;
    }

    /** One look at the spot: dig what is there, if the hive's tools can; otherwise wait for a block to form (or a tool to come). */
    private void tickRepeatDig(HiveHeart heart) {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) this.level();
        nextRepeatScan = this.tickCount + 10;
        if (!level.isLoaded(repeatDig)) {
            nextRepeatScan = this.tickCount + 40;
            return;
        }
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(repeatDig);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, repeatDig) < 0.0F) {
            return;
        }
        if (!com.projecthivemind.HiveActions.toolsCanHarvest(heart, state)) {
            nextRepeatScan = this.tickCount + 40;
            if (heart.getServer() != null && heart.ownerId() != null
                    && this.level().getGameTime() - lastToolNotice >= 300L) {
                lastToolNotice = this.level().getGameTime();
                net.minecraft.server.level.ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
                if (owner != null) {
                    owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.projecthivemind.tower_needs_tool", state.getBlock().getName()), false);
                }
            }
            return;
        }
        setAction(new UnitAction(UnitAction.Kind.DIG, repeatDig));
        nextRepeatScan = this.tickCount + 2;
    }

    private long lastToolNotice = Long.MIN_VALUE;
    /** How many scans in a row found nothing to do for the wall, and how many it takes (about 12 seconds) before the wall is taken to be done. */
    private int wallIdleScans;
    private static final int WALL_IDLE_SCANS_TO_FINISH = 6;

    /** Start building the wall round the hive out of this block (null stops it). Anything that is not a plain full block stops it too. */
    public void setWallItem(@Nullable net.minecraft.world.item.Item item) {
        this.wallItem = item != null && fillBlock(item) != null ? item : null;
        this.nextWallScan = 0;
        this.wallIdleScans = 0;
    }

    @Nullable
    public net.minecraft.world.item.Item wallItem() {
        return wallItem;
    }

    /**
     * One step of the wall, when the worker is free: first the ground in the outer rings that has to be dug away (as an ordinary dig
     * order, its drops going into the hive), then the wall itself, one block at a time from the hive's stock, walking to where it can
     * reach. Does nothing, and the job ends, once there is nothing left to do.
     */
    private void tickWall(HiveHeart heart) {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) this.level();
        net.minecraft.world.level.block.Block block = fillBlock(wallItem);
        if (block == null) {
            wallItem = null;
            return;
        }
        nextWallScan = this.tickCount + 5;
        net.minecraft.core.BlockPos dig = BorderWall.nextDig(level, heart, this.position());
        if (dig != null) {
            setAction(new UnitAction(UnitAction.Kind.DIG, dig));
            return;
        }
        net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
        net.minecraft.core.BlockPos place = BorderWall.nextPlace(level, heart, this.position(), state);
        if (place == null) {
            // Nothing found is not yet the same as nothing left: the last gap may be filled by someone standing in it, and part of the wall may
            // be in chunks that are not loaded. The job only ends once the wall has looked whole for a good while, with all of it loaded.
            if (BorderWall.hasUnloadedColumns(level, heart) || ++wallIdleScans < WALL_IDLE_SCANS_TO_FINISH) {
                nextWallScan = this.tickCount + 40;
                return;
            }
            wallItem = null;
            wallIdleScans = 0;
            return;
        }
        wallIdleScans = 0;
        if (heart.getStorage().countItem(wallItem) <= 0) {
            // Out of the block: wait for the hive to get some.
            notifyMissingBlock(heart, wallItem);
            nextWallScan = this.tickCount + 40;
            return;
        }
        if (WorkerDigGoal.inDigReach(this, place)) {
            heart.getStorage().removeItemType(wallItem, 1);
            level.setBlock(place, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
            level.playSound(null, place, state.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.8F);
            this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        } else {
            this.getNavigation().moveTo(place.getX() + 0.5D, place.getY(), place.getZ() + 0.5D, 1.0D);
        }
    }

    /** When the owner was last told in chat that this block was missing, by hive and block: so a crew of workers does not repeat it. */
    private static final java.util.Map<String, Long> MISSING_NOTICES = new java.util.HashMap<>();

    /** Tell the owner, in chat, that a construction cannot go on because the hive has none of this block (at most every 15 seconds for each). */
    private void notifyMissingBlock(HiveHeart heart, net.minecraft.world.item.Item item) {
        if (heart.getServer() == null || heart.ownerId() == null) {
            return;
        }
        String key = heart.getUUID() + "/" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        long now = this.level().getGameTime();
        Long last = MISSING_NOTICES.get(key);
        if (last != null && now - last < 300L) {
            return;
        }
        MISSING_NOTICES.put(key, now);
        net.minecraft.server.level.ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner != null) {
            owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.projecthivemind.construction_missing", item.getDescription()), false);
        }
    }

    /** When (worker tick) the flint and steel in the hand is put away again, or 0. Not saved. */
    private int flintUntil;

    private void putAwayFlint() {
        flintUntil = 0;
        if (this.getMainHandItem().is(net.minecraft.world.item.Items.FLINT_AND_STEEL)) {
            this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    /** True if every block of the portal's frame is obsidian. */
    private static boolean portalFrameWhole(net.minecraft.server.level.ServerLevel level, com.projecthivemind.build.BridgeJob job) {
        for (BlockPos pos : job.portalFrame()) {
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Light a portal's opening: the worker takes the flint and steel from the hive's storage into its hand and uses it on the top of the bottom
     * of the frame, as a player does, so the fire goes in the bottom of the opening and the portal forms. The flint and steel wears, and goes back
     * to the hive (it is the stack in the storage that is worn; the worker's hand only shows one).
     */
    private void igniteWithFlintAndSteel(HiveHeart heart, net.minecraft.server.level.ServerLevel level, BlockPos firePos) {
        net.minecraft.world.item.ItemStack tool = net.minecraft.world.item.ItemStack.EMPTY;
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = heart.getStorage().getItem(i);
            if (stack.is(net.minecraft.world.item.Items.FLINT_AND_STEEL)) {
                tool = stack;
                break;
            }
        }
        if (tool.isEmpty()) {
            return;
        }
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL));
        flintUntil = this.tickCount + 20;
        this.getLookControl().setLookAt(net.minecraft.world.phys.Vec3.atCenterOf(firePos));
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        net.minecraft.world.level.block.state.BlockState fire = net.minecraft.world.level.block.BaseFireBlock.getState(level, firePos);
        level.playSound(null, firePos, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F,
                level.getRandom().nextFloat() * 0.4F + 0.8F);
        level.setBlock(firePos, fire, 11);
        tool.hurtAndBreak(1, level, this, broken -> { });
        heart.getStorage().setChanged();
    }

    /** The bridge this worker is building, if it was given one. Saved. */
    @Nullable
    private com.projecthivemind.build.BridgeJob bridge;
    private int nextBridgeScan;
    private int bridgeStuck;
    /** How far into the bridge's list of blocks everything before it is known to be done: a long job is not looked through from its start each time. */
    private int bridgeCursor;

    public void setBridge(@Nullable com.projecthivemind.build.BridgeJob bridge) {
        this.bridge = bridge;
        this.nextBridgeScan = 0;
        this.bridgeStuck = 0;
        this.bridgeCursor = 0;
    }

    /** The block this item places as a fence: any wooden or nether brick fence. Null for anything else. */
    @Nullable
    public static net.minecraft.world.level.block.Block fenceBlock(net.minecraft.world.item.Item item) {
        return item instanceof net.minecraft.world.item.BlockItem blockItem && blockItem.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                ? blockItem.getBlock() : null;
    }

    /**
     * One step of the bridge when the worker is free: the first block still missing, in building order. Within reach it is placed,
     * from the hive's stock; otherwise the worker walks to the deck beside it (and, if the walk is not getting there, is carried the last
     * of the way). Out of material it waits. The job ends when nothing is missing.
     */
    private void tickBridge(HiveHeart heart) {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) this.level();
        nextBridgeScan = this.tickCount + 5;
        if (flintUntil != 0 && this.tickCount >= flintUntil) {
            putAwayFlint();
        }
        com.projecthivemind.build.BridgeJob job = bridge;
        com.projecthivemind.build.BridgeJob.Placement next = null;
        net.minecraft.world.level.block.state.BlockState state = null;
        net.minecraft.world.item.Item item = null;
        // A generator's digging is only checked until its water is in: after that the stone that forms in its cells must not be dug up as unfinished work.
        java.util.List<net.minecraft.world.item.Item> missingBuckets = new java.util.ArrayList<>();
        boolean fluidsStarted = false;
        if (job.generator()) {
            for (com.projecthivemind.build.BridgeJob.Placement placement : job.placements()) {
                if (placement.kind() == com.projecthivemind.build.BridgeJob.Kind.WATER && level.isLoaded(placement.pos())
                        && level.getFluidState(placement.pos()).is(net.minecraft.tags.FluidTags.WATER)) {
                    fluidsStarted = true;
                }
            }
        }
        java.util.List<com.projecthivemind.build.BridgeJob.Placement> all = job.placements();
        int from = Math.min(bridgeCursor, all.size());
        boolean leading = true;
        for (int index = from; index < all.size(); index++) {
            com.projecthivemind.build.BridgeJob.Placement placement = all.get(index);
            if (!level.isLoaded(placement.pos())) {
                leading = false;
                continue;
            }
            net.minecraft.world.level.block.state.BlockState atSpot = level.getBlockState(placement.pos());
            // A frame block of a portal with ground (or anything else) where it goes is dug out first.
            boolean blockedFrame = placement.kind() == com.projecthivemind.build.BridgeJob.Kind.OBSIDIAN && !atSpot.is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
                    && !atSpot.canBeReplaced() && atSpot.getFluidState().isEmpty() && atSpot.getDestroySpeed(level, placement.pos()) >= 0.0F;
            if (placement.kind() == com.projecthivemind.build.BridgeJob.Kind.DIG || blockedFrame) {
                // A cell to be dug out: done when it is empty (or holds a fluid, or can not be dug); otherwise the worker digs it, and looks again.
                net.minecraft.world.level.block.state.BlockState cell = level.getBlockState(placement.pos());
                if (fluidsStarted || cell.isAir() || !cell.getFluidState().isEmpty() || cell.getDestroySpeed(level, placement.pos()) < 0.0F
                        || cell.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) || cell.is(net.minecraft.tags.BlockTags.FIRE)) {
                    if (leading) {
                        bridgeCursor = index + 1;
                    }
                    continue;
                }
                if (WorkerDigGoal.inDigReach(this, placement.pos())) {
                    setAction(new UnitAction(UnitAction.Kind.DIG, placement.pos()));
                    bridgeStuck = 0;
                } else if (++bridgeStuck > 60) {
                    // The walk is not getting there: be carried the last of the way.
                    this.getNavigation().stop();
                    this.moveTo(placement.stand().x, placement.stand().y, placement.stand().z, this.getYRot(), this.getXRot());
                    bridgeStuck = 0;
                } else {
                    this.getNavigation().moveTo(placement.stand().x, placement.stand().y, placement.stand().z, 1.0D);
                }
                nextBridgeScan = this.tickCount + 5;
                return;
            }
            net.minecraft.world.level.block.state.BlockState existing = level.getBlockState(placement.pos());
            // Done once there is something there; a deck (and a wall) only replaces what a block can replace (air, water, plants). The generator's
            // lava and water count as done when that fluid is there, or when something solid has taken the place (lava made into obsidian, say).
            com.projecthivemind.build.BridgeJob.Kind kind = placement.kind();
            boolean done;
            if (kind == com.projecthivemind.build.BridgeJob.Kind.LAVA || kind == com.projecthivemind.build.BridgeJob.Kind.WATER) {
                done = level.getFluidState(placement.pos()).is(kind == com.projecthivemind.build.BridgeJob.Kind.LAVA
                        ? net.minecraft.tags.FluidTags.LAVA : net.minecraft.tags.FluidTags.WATER)
                        || (!existing.isAir() && !existing.canBeReplaced());
            } else if (kind == com.projecthivemind.build.BridgeJob.Kind.OBSIDIAN) {
                done = existing.is(net.minecraft.world.level.block.Blocks.OBSIDIAN);
            } else if (kind == com.projecthivemind.build.BridgeJob.Kind.IGNITE) {
                // Lit: the portal is there (or the fire that did not make one, which is all a flint and steel can do).
                done = existing.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) || existing.is(net.minecraft.tags.BlockTags.FIRE);
            } else if (kind == com.projecthivemind.build.BridgeJob.Kind.DECK || kind == com.projecthivemind.build.BridgeJob.Kind.WALL
                    || kind == com.projecthivemind.build.BridgeJob.Kind.SLAB) {
                done = !existing.canBeReplaced();
            } else {
                done = !existing.canBeReplaced() || !existing.isAir();
            }
            if (done) {
                if (leading) {
                    bridgeCursor = index + 1;
                }
                continue;
            }
            leading = false;
            item = switch (kind) {
                case DECK, WALL -> job.deck();
                case FENCE -> job.fence();
                case TORCH -> net.minecraft.world.item.Items.TORCH;
                case LAVA -> net.minecraft.world.item.Items.LAVA_BUCKET;
                case WATER -> net.minecraft.world.item.Items.WATER_BUCKET;
                case SLAB -> net.minecraft.world.item.Items.COBBLESTONE_SLAB;
                case OBSIDIAN -> net.minecraft.world.item.Items.OBSIDIAN;
                case IGNITE -> net.minecraft.world.item.Items.FLINT_AND_STEEL;
                case DIG -> null;
            };
            net.minecraft.world.level.block.Block block = switch (kind) {
                case TORCH -> net.minecraft.world.level.block.Blocks.TORCH;
                case FENCE -> fenceBlock(item);
                case LAVA -> net.minecraft.world.level.block.Blocks.LAVA;
                case WATER -> net.minecraft.world.level.block.Blocks.WATER;
                case SLAB -> net.minecraft.world.level.block.Blocks.COBBLESTONE_SLAB;
                case OBSIDIAN -> net.minecraft.world.level.block.Blocks.OBSIDIAN;
                case IGNITE -> net.minecraft.world.level.block.Blocks.FIRE;
                default -> fillBlock(item);
            };
            if (kind == com.projecthivemind.build.BridgeJob.Kind.IGNITE && !portalFrameWhole(level, job)) {
                // Not lit until the frame is whole (a block of it may be waiting for a worker to dig the way clear).
                continue;
            }
            if (block == null || !level.isUnobstructed(block.defaultBlockState(), placement.pos(), net.minecraft.world.phys.shapes.CollisionContext.empty())) {
                continue;
            }
            if ((kind == com.projecthivemind.build.BridgeJob.Kind.WATER || kind == com.projecthivemind.build.BridgeJob.Kind.LAVA)
                    && heart.getStorage().countItem(item) <= 0) {
                // No bucket for this fluid right now: the other one can go in with the bucket the hive does have; this one is waited for.
                missingBuckets.add(item);
                continue;
            }
            state = block.defaultBlockState();
            next = placement;
            break;
        }
        if (next == null && !missingBuckets.isEmpty()) {
            // Everything else is done and the only work left needs a bucket the hive does not have: tell the player which, and wait for it.
            for (net.minecraft.world.item.Item bucket : missingBuckets) {
                notifyMissingBlock(heart, bucket);
            }
            nextBridgeScan = this.tickCount + 40;
            return;
        }
        if (next == null && from > 0) {
            // Finished going by the cursor: look through all of it once more before it counts as done.
            bridgeCursor = 0;
            nextBridgeScan = this.tickCount + 1;
            return;
        }
        if (next == null) {
            putAwayFlint();
            bridge = null;
            if (heart.constructions().of(this.getUUID()) != null) {
                com.projecthivemind.HiveConstructions.markDone(heart, this.getUUID());
            } else if (heart.getServer() != null && heart.ownerId() != null) {
                // A bridge from before there were construction blocks: told as it was.
                net.minecraft.server.level.ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
                if (owner != null) {
                    owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.projecthivemind.bridge_finished"), false);
                }
            }
            return;
        }
        if (heart.getStorage().countItem(item) <= 0) {
            // Out of what this block is made of: wait for the hive to get some.
            notifyMissingBlock(heart, item);
            nextBridgeScan = this.tickCount + 40;
            return;
        }
        if (WorkerDigGoal.inDigReach(this, next.pos())) {
            this.getNavigation().stop();
            if (next.kind() == com.projecthivemind.build.BridgeJob.Kind.IGNITE) {
                igniteWithFlintAndSteel(heart, level, next.pos());
                bridgeStuck = 0;
                nextBridgeScan = this.tickCount + 2;
                return;
            }
            if (next.kind() == com.projecthivemind.build.BridgeJob.Kind.FENCE) {
                state = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, level, next.pos());
            }
            heart.getStorage().removeItemType(item, 1);
            level.setBlock(next.pos(), state, net.minecraft.world.level.block.Block.UPDATE_ALL);
            if (item == net.minecraft.world.item.Items.LAVA_BUCKET || item == net.minecraft.world.item.Items.WATER_BUCKET) {
                // The bucket is emptied into the world and goes back to the hive.
                net.minecraft.world.item.ItemStack empty = heart.getStorage().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
                if (!empty.isEmpty()) {
                    net.minecraft.world.level.block.Block.popResource(level, next.pos(), empty);
                }
            }
            level.playSound(null, next.pos(), state.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.8F);
            this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            bridgeStuck = 0;
            nextBridgeScan = this.tickCount + 2;
        } else if (++bridgeStuck > 40) {
            // The walk is not getting there: be carried the last of the way, to the deck beside the block.
            this.getNavigation().stop();
            this.moveTo(next.stand().x, next.stand().y, next.stand().z, this.getYRot(), this.getXRot());
            bridgeStuck = 0;
        } else {
            this.getNavigation().moveTo(next.stand().x, next.stand().y, next.stand().z, 1.0D);
        }
    }

    /**
     * What larger task this worker has been given, in words for the hive menu (a bridge, the border wall or a staircase), or null. These
     * are not single dig orders, so they have no job of their own to show; this is shown in its place, and cancelling the job ends them.
     */
    @Nullable
    public net.minecraft.network.chat.Component taskText() {
        if (repeatDig != null) {
            return net.minecraft.network.chat.Component.translatable("job.projecthivemind.repeat_dig");
        }
        if (bridge != null) {
            return net.minecraft.network.chat.Component.translatable("job.projecthivemind.bridge");
        }
        if (wallItem != null) {
            return net.minecraft.network.chat.Component.translatable("job.projecthivemind.wall");
        }
        if (staircase != null) {
            return net.minecraft.network.chat.Component.translatable("job.projecthivemind.staircase");
        }
        return null;
    }

    /** The blocks of the tree this worker is felling, still to dig, in order: its logs from the bottom, then its leaves. Not saved. */
    private final java.util.ArrayDeque<BlockPos> fellQueue = new java.util.ArrayDeque<>();
    /** The block it last set out to dig and how many times running, so one it can never get at is given up on. */
    @Nullable
    private BlockPos lastFell;
    private int fellRepeats;


    /** The staircase this worker is digging down, if it was given one. Saved. */
    @Nullable
    private com.projecthivemind.build.StairDig staircase;
    private int nextStairScan;

    public void setStaircase(@Nullable com.projecthivemind.build.StairDig staircase) {
        this.staircase = staircase;
        this.nextStairScan = 0;
    }

    /** The staircase this worker is digging, if any (a construction puts it there; see putOnConstruction). */
    @Nullable
    public com.projecthivemind.build.StairDig staircase() {
        return staircase;
    }

    /** The bridge this worker is building, if any. */
    @Nullable
    public com.projecthivemind.build.BridgeJob bridge() {
        return bridge;
    }

    /** True while this worker has work on a construction: a bridge, a staircase, or a tower or shaft. That outranks staying inside the border. */
    public boolean onConstruction() {
        return bridge != null || staircase != null || hasConstructionTower();
    }

    /** True if this worker is on a tower or shaft: the tower is built through its orders. */
    public boolean hasConstructionTower() {
        return action != null && action.kind() == UnitAction.Kind.BUILD;
    }

    /**
     * Put this worker on its construction's job, and take it off anything else of the kind: the bridge or staircase is set (once; the same recipe
     * is left alone), and for a tower the worker is given the build order when it is free and not selected. A construction without the workers it
     * needs (`active` false) is not worked on.
     */
    public void putOnConstruction(com.projecthivemind.build.Construction construction, boolean active, boolean selected) {
        UnitAction mine = new UnitAction(UnitAction.Kind.BUILD, construction.anchor());
        switch (construction.kind()) {
            case BRIDGE -> {
                staircase = null;
                dropBuildOrder(mine);
                if (bridge != construction.bridge()) {
                    setBridge(construction.bridge());
                }
            }
            case STAIRCASE -> {
                bridge = null;
                dropBuildOrder(mine);
                if (staircase != construction.stairs()) {
                    setStaircase(construction.stairs());
                }
            }
            case TOWER -> {
                bridge = null;
                staircase = null;
                // The build order is given even to a tower that has too few workers to be worked on: the worker then waits on it (see
                // WorkerBuildGoal), and it shows as its job, which can be cancelled.
                if (!selected && (action == null || (action.kind() == UnitAction.Kind.BUILD && !action.equals(mine)))) {
                    setAction(mine);
                }
            }
        }
    }

    /** Drop a build order that is not `keep` (or any, with null): the worker is on another construction, or this one has none to work on. */
    private void dropBuildOrder(@Nullable UnitAction keep) {
        if (action != null && action.kind() == UnitAction.Kind.BUILD && !action.equals(keep)) {
            setAction(null);
            this.getNavigation().stop();
        }
    }

    /** End whatever this worker was doing for a construction: the bridge, the staircase, the tower or shaft order. */
    public void clearConstructionWork() {
        staircase = null;
        bridge = null;
        if (job != null && job.kind() == UnitAction.Kind.BUILD) {
            job = null;
        }
        if (action != null && action.kind() == UnitAction.Kind.BUILD) {
            action = null;
            this.getNavigation().stop();
        }
    }

    /** This unit's own settings, edited from the hive menu's page for its kind. */
    private WorkerBehavior behavior = WorkerBehavior.DEFAULT;
    private int gearVersion;
    private final GearMirror gearMirror = new GearMirror();

    public HiveWorker(EntityType<? extends HiveWorker> type, Level level) {
        super(type, level);
        // Water is no obstacle to plan around: workers wade through it.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 0.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: skeleton goals flee the sun and shoot players.
        // No FloatGoal: a worker sinks in water and walks along the bottom, instead of bobbing on the surface.
        this.goalSelector.addGoal(1, new LeavePortalGoal(this));

        // Above everything else: a unit told to stay inside the hive border does.
        // (Not while the worker is on a construction: that comes first, even if it is outside the border.)
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> behavior.stayInside() && !inTeam() && !onConstruction()));
        // The last thing a unit does: when idle and set to, walk about inside the border.
        this.goalSelector.addGoal(5, new WanderInsideGoal(this, () -> behavior.wander()));
        // After that, when idle inside the border and not wandering: stand in the Heart.
        this.goalSelector.addGoal(6, new GatherAtHeartGoal(this, () -> behavior.stayAtHeart() && !behavior.wander()));
        // A team member stays inside the team's area around its scout: before everything but floating.
        this.goalSelector.addGoal(0, new TeamFollowGoal(this));
        // The highest priority a worker has: run from hostile mobs, when set to.
        this.goalSelector.addGoal(0, new WorkerFleeGoal(this));
        this.goalSelector.addGoal(2, new WorkerDigGoal(this));
        this.goalSelector.addGoal(2, new InteractBlockGoal(this));
        this.goalSelector.addGoal(2, new WorkerTorchGoal(this));
        this.goalSelector.addGoal(2, new WorkerBuildGoal(this));
        this.goalSelector.addGoal(2, new WorkerFillGoal(this));
    }

    /** True while this unit follows a team scout: it then goes where the scout goes and need not stay inside the border. (A team with no scout alive leaves its members to the hive's ordinary rules.) */
    private boolean inTeam() {
        HiveHeart heart = findHeart();
        return heart != null && heart.teamLeader(this) != null;
    }

    /** The hive's tools changed: the tool in hand is put away, and the digging goal picks the best of what the hive has now at once. */
    @Override
    public void onGearChanged(HiveHeart heart, int changed) {
        if ((changed & 2) != 0) {
            gearMirror.reset();
            this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    /** Carries a walk order a long way, past what one path can reach. */
    private final WalkProgress walkProgress = new WalkProgress();

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    @Nullable
    @Override
    public HiveHeart findHeart() {
        return HiveHeart.find(this.level(), heartId);
    }

    /** Call just before deliberately changing this worker's gear, so the swap is not read as damage or breakage. */
    public void resetGearMirror() {
        gearMirror.reset();
    }

    /** Ticks between looks for work. Looking is the expensive part, so an idle worker does it every couple of seconds. */
    private static final int JOB_SCAN_INTERVAL = 40;
    private int jobScanBackoff = 1;

    private int nextJobScan;
    /** The plants and crops the last look round the hive found, so the next job comes from this list and not from another look at the whole area. */
    private final java.util.ArrayList<BlockPos> harvestQueue = new java.util.ArrayList<>();

    public java.util.List<BlockPos> harvestQueue() {
        return harvestQueue;
    }

    /** A dig is done: look for the next job at once, instead of waiting out the interval. */
    public void rescanSoon() {
        nextJobScan = 0;
    }
    /** True while it is cutting a rise off the ground (flatten): it looks for the next block at once, not every two seconds. */
    private boolean flattenActive;
    /** Take a set-aside job up again once the unit has nothing to do and the player has let go of it. */
    private void resumeJobIfFree(@Nullable HiveHeart heart) {
        if (action == null && job != null && resumeJob && heart != null && !heart.isUnitSelected(this.getId())) {
            setAction(job);
        }
    }


    /** The pathfinder does not know the Heart's body is solid, so a unit would press against it and never get past: units walk through it. */
    @Override
    public boolean canCollideWith(net.minecraft.world.entity.Entity other) {
        return !(other instanceof HiveHeart) && super.canCollideWith(other);
    }

    /** Short, so a unit can use a portal again soon after coming through one (an order into it works at once). */
    @Override
    public int getDimensionChangingDelay() {
        return 40;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            HiveHeart heart = findHeart();
            if (heart != null) {
                // The tool in hand is a copy of one in the hive: wear on it is charged to the original.
                gearMirror.tick(this, heart);
            }
            if (action != null && action.kind() == UnitAction.Kind.WALK && this.getNavigation().isDone() && !walkProgress.keepWalking(this, action)) {
                action = null;
            }
            resumeJobIfFree(heart);
            // A staircase being dug is carried on, one block at a time, whenever the worker is free; it comes before its own work.
            if (action == null && staircase != null && heart != null && !heart.isUnitSelected(this.getId()) && this.tickCount >= nextStairScan) {
                nextStairScan = this.tickCount + 10;
                // Nowhere to stand (the staircase broke into a cave): cobblestone from the hive goes under the step, to keep the formation.
                net.minecraft.core.BlockPos hole = staircase.nextFill((net.minecraft.server.level.ServerLevel) this.level());
                if (hole != null && heart.getStorage().countItem(net.minecraft.world.item.Items.COBBLESTONE) > 0) {
                    net.minecraft.core.BlockPos under = hole.below();
                    if (WorkerDigGoal.inDigReach(this, under)) {
                        heart.getStorage().removeItemType(net.minecraft.world.item.Items.COBBLESTONE, 1);
                        net.minecraft.world.level.block.state.BlockState cobble = net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
                        this.level().setBlock(under, cobble, net.minecraft.world.level.block.Block.UPDATE_ALL);
                        this.level().playSound(null, under, cobble.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.8F);
                        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    } else {
                        // Go to the step before, which has ground, to reach it.
                        net.minecraft.core.BlockPos before = hole.relative(staircase.direction().getOpposite()).above();
                        this.getNavigation().moveTo(before.getX() + 0.5D, before.getY(), before.getZ() + 0.5D, 1.0D);
                    }
                    nextStairScan = this.tickCount + 5;
                    return;
                }
                // A torch on the way first, if the staircase is to have them, there is one in the hive, and the spot is in reach.
                net.minecraft.core.BlockPos torch = staircase.torches() ? staircase.nextTorch((net.minecraft.server.level.ServerLevel) this.level()) : null;
                if (torch != null && WorkerDigGoal.inDigReach(this, torch) && placeTorch(heart, torch)) {
                    nextStairScan = this.tickCount + 5;
                    return;
                }
                UnitAction next = staircase.nextDig((net.minecraft.server.level.ServerLevel) this.level());
                if (next == null) {
                    staircase = null;
                    com.projecthivemind.HiveConstructions.markDone(heart, this.getUUID());
                } else {
                    setAction(next);
                }
            }
            // A bridge, if the worker was given one: after a staircase, before its own work.
            if (action == null && bridge != null && heart != null && !heart.isUnitSelected(this.getId()) && this.tickCount >= nextBridgeScan) {
                tickBridge(heart);
            }
            // The border wall, if the worker was given it: second to a staircase, before its own work.
            if (action == null && wallItem != null && heart != null && !heart.isUnitSelected(this.getId()) && this.tickCount >= nextWallScan) {
                tickWall(heart);
            }
            // A spot it was set to keep mining, once any bigger task is done.
            if (action == null && repeatDig != null && heart != null && bridge == null && wallItem == null && staircase == null && this.tickCount >= nextRepeatScan) {
                tickRepeatDig(heart);
            }
            // Not tickCount % N: use a deadline, so the timing never depends on the entity id.
            if (action == null && heart != null && (this.tickCount >= nextJobScan || !fellQueue.isEmpty() || flattenActive)) {
                findOwnWork(heart);
                // With nothing found the next look waits longer (up to 8 times as long), a little differently for each worker; work found starts it over.
                jobScanBackoff = this.action() != null ? 1 : Math.min(jobScanBackoff * 2, 8);
                nextJobScan = this.tickCount + JOB_SCAN_INTERVAL * jobScanBackoff + this.random.nextInt(JOB_SCAN_INTERVAL);
            }
        }
    }


    /**
     * The next block of the tree being felled, as a dig order, or null if the tree is down. Blocks already gone, and leaves another tree's
     * logs now hold up, are passed over; one that has been set out for three times without coming down (out of reach, say) is given up on.
     */
    @Nullable
    private UnitAction nextFellOrder() {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) this.level();
        while (!fellQueue.isEmpty()) {
            BlockPos next = fellQueue.peek();
            if (!level.isLoaded(next) || !TreeFelling.stillToTake(level, next)) {
                fellQueue.poll();
                continue;
            }
            if (next.equals(lastFell)) {
                fellRepeats++;
            } else {
                lastFell = next;
                fellRepeats = 1;
            }
            if (fellRepeats > 3) {
                fellQueue.poll();
                lastFell = null;
                continue;
            }
            return new UnitAction(UnitAction.Kind.DIG, next);
        }
        return null;
    }

    /** With no orders and not selected, look for work the hive's worker settings allow. */
    private void findOwnWork(HiveHeart heart) {
        if (heart.isUnitSelected(this.getId()) || heart.level() != this.level()) {
            return;
        }
        // A tree being felled is finished before anything else: its next block is dug, as long as that takes with the tool in hand.
        if (!behavior.fellTrees() && !behavior.chopLogs()) {
            fellQueue.clear();
        }
        UnitAction continuing = nextFellOrder();
        if (continuing != null) {
            setAction(continuing);
            return;
        }
        // A grown crop in the hive area comes first, if the worker is set to harvest.
        if (behavior.harvestCrops() || behavior.clearPlants()) {
            UnitAction harvest = WorkerAutoJobs.findHarvest(this, heart);
            if (harvest != null) {
                setAction(harvest);
                return;
            }
        }
        // Felling trees inside the border: start on the nearest tree. Its foot is dug first, then the rest of it, block by block.
        if (behavior.fellTrees() || behavior.chopLogs()) {
            UnitAction foot = WorkerAutoJobs.findFelling(this, heart);
            if (foot != null) {
                fellQueue.clear();
                // The tree's blocks: within the hive area if it is a tree the worker fells there, otherwise round the tree's own foot.
                net.minecraft.server.level.ServerLevel fellLevel = (net.minecraft.server.level.ServerLevel) this.level();
                net.minecraft.world.phys.AABB bounds = behavior.fellTrees() && HiveArea.containsCube(heart, foot.pos().getX() + 0.5D, foot.pos().getY() + 0.5D, foot.pos().getZ() + 0.5D)
                        ? HiveArea.areaBox(fellLevel, heart) : new net.minecraft.world.phys.AABB(foot.pos()).inflate(10.0D, 0.0D, 10.0D).expandTowards(0.0D, 28.0D, 0.0D);
                fellQueue.addAll(TreeFelling.plan(fellLevel, bounds, foot.pos()));
                lastFell = null;
                setAction(foot);
                return;
            }
        }
        // Flattening the ground also cuts a rise of stone, dirt and grass down to the Heart's floor, up to 4 blocks of it.
        flattenActive = false;
        if (behavior.flattenGround() || behavior.flattenTeam()) {
            UnitAction cut = WorkerAutoJobs.findFlattenDig(this, heart);
            if (cut != null) {
                flattenActive = true;
                setAction(cut);
                return;
            }
        }
        if (!behavior.any()) {
            return;
        }
        UnitAction job = WorkerAutoJobs.findJob(this, heart);
        if (job != null) {
            setAction(job);
        }
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    /** Units give no experience when they die. */
    @Override
    protected void dropExperience(@Nullable Entity killer) {
    }

    // ---- outline: white, yellow when selected, visible through walls, for the owner only ----

    @Override
    public boolean isCurrentlyGlowing() {
        return this.level().isClientSide ? ClientSelection.shouldGlow(ownerId()) : super.isCurrentlyGlowing();
    }

    @Override
    public int getTeamColor() {
        return this.level().isClientSide ? ClientSelection.outlineColor(this.getId()) : super.getTeamColor();
    }

    @Override
    public UnitKind kind() {
        return UnitKind.WORKER;
    }

    @Nullable
    @Override
    public UUID ownerId() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    @Override
    public void setOwnerId(@Nullable UUID ownerId) {
        this.entityData.set(DATA_OWNER, Optional.ofNullable(ownerId));
    }

    public WorkerBehavior behavior() {
        return behavior;
    }

    @Override
    public int behaviorFlags() {
        return behavior.flags();
    }

    @Override
    public int[] behaviorRadii() {
        int[] radii = new int[4];
        System.arraycopy(behavior.radii(), 0, radii, 0, behavior.radii().length);
        return radii;
    }

    @Override
    public void setBehavior(int flags, int[] radii) {
        this.behavior = WorkerBehavior.from(flags, radii);
    }

    @Nullable
    @Override
    public UnitAction action() {
        return action;
    }

    @Override
    public void setAction(@Nullable UnitAction next) {
        // A job ending, or being cancelled, ends the job. Giving the unit another order does not: it is set aside, and
        // comes back when the unit is released. Giving it a new job replaces the old one.
        if (next == null && action != null && action.equals(job)) {
            job = null;
        }
        if (next != null && next.kind().isJob()) {
            job = next;
        }
        this.action = next;
    }

    @Nullable
    @Override
    public UnitAction job() {
        return job;
    }

    @Override
    public boolean resumeJob() {
        return resumeJob;
    }

    @Override
    public void cancelJob() {
        // Cancelling takes the worker off its construction too (the construction itself stays, for others to work on).
        HiveHeart constructionHeart = findHeart();
        if (constructionHeart != null) {
            com.projecthivemind.HiveConstructions.leave(constructionHeart, this.getUUID());
        }
        staircase = null;
        wallItem = null;
        fellQueue.clear();
        repeatDig = null;
        bridge = null;
        // Whatever it was walking to for them, it stops.
        this.getNavigation().stop();
        UnitAction ended = job;
        job = null;
        if (ended != null && ended.equals(action)) {
            action = null;
            this.getNavigation().stop();
        }
    }

    @Override
    public void setResumeJob(boolean resume) {
        this.resumeJob = resume;
    }

    @Override
    public int gearVersion() {
        return gearVersion;
    }

    @Override
    public void setGearVersion(int version) {
        this.gearVersion = version;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        tag.put("Behavior", behavior.save());
        // The job, and whether the unit was on it, so that it carries on after the game has been closed.
        if (job != null) {
            tag.put("Job", job.save());
            tag.putBoolean("JobActive", job.equals(action));
        }
        tag.putBoolean("ResumeJob", resumeJob);
        if (bridge != null) {
            tag.put("Bridge", bridge.save());
        }
        if (repeatDig != null) {
            tag.put("RepeatDig", net.minecraft.nbt.NbtUtils.writeBlockPos(repeatDig));
        }
        if (wallItem != null) {
            tag.putString("WallItem", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(wallItem).toString());
        }
        if (staircase != null) {
            tag.put("Staircase", staircase.save());
        }
        if (fillItem != null) {
            tag.putString("FillItem", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(fillItem).toString());
        }
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
        tag.putInt(GEAR_VERSION_TAG, gearVersion);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        job = tag.contains("Job") ? UnitAction.load(tag.getCompound("Job")) : null;
        resumeJob = !tag.contains("ResumeJob") || tag.getBoolean("ResumeJob");
        bridge = tag.contains("Bridge") ? com.projecthivemind.build.BridgeJob.load(tag.getCompound("Bridge")) : null;
        staircase = tag.contains("Staircase") ? com.projecthivemind.build.StairDig.load(tag.getCompound("Staircase")) : null;
        net.minecraft.resources.ResourceLocation fillId = tag.contains("FillItem") ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("FillItem")) : null;
        setFillItem(fillId == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(fillId).orElse(null));
        repeatDig = tag.contains("RepeatDig") ? net.minecraft.nbt.NbtUtils.readBlockPos(tag, "RepeatDig").orElse(null) : null;
        net.minecraft.resources.ResourceLocation wallId = tag.contains("WallItem") ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("WallItem")) : null;
        setWallItem(wallId == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(wallId).orElse(null));
        if (job != null && tag.getBoolean("JobActive")) {
            action = job;
        }
        if (tag.contains("Behavior")) {
            behavior = WorkerBehavior.load(tag.getCompound("Behavior"));
        }
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        gearVersion = tag.getInt(GEAR_VERSION_TAG);
    }
}
