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

    /** Start building the wall round the hive out of this block (null stops it). Anything that is not a plain full block stops it too. */
    public void setWallItem(@Nullable net.minecraft.world.item.Item item) {
        this.wallItem = item != null && fillBlock(item) != null ? item : null;
        this.nextWallScan = 0;
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
            wallItem = null;
            return;
        }
        if (heart.getStorage().countItem(wallItem) <= 0) {
            // Out of the block: wait for the hive to get some.
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

    /** The bridge this worker is building, if it was given one. Saved. */
    @Nullable
    private com.projecthivemind.build.BridgeJob bridge;
    private int nextBridgeScan;
    private int bridgeStuck;

    public void setBridge(@Nullable com.projecthivemind.build.BridgeJob bridge) {
        this.bridge = bridge;
        this.nextBridgeScan = 0;
        this.bridgeStuck = 0;
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
        com.projecthivemind.build.BridgeJob job = bridge;
        com.projecthivemind.build.BridgeJob.Placement next = null;
        net.minecraft.world.level.block.state.BlockState state = null;
        net.minecraft.world.item.Item item = null;
        for (com.projecthivemind.build.BridgeJob.Placement placement : job.placements()) {
            if (!level.isLoaded(placement.pos())) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState existing = level.getBlockState(placement.pos());
            // Done once there is something there; a deck only replaces what a block can replace (air, water, plants).
            if (!existing.canBeReplaced() || (placement.kind() != com.projecthivemind.build.BridgeJob.Kind.DECK && !existing.isAir())) {
                continue;
            }
            item = switch (placement.kind()) {
                case DECK -> job.deck();
                case FENCE -> job.fence();
                case TORCH -> net.minecraft.world.item.Items.TORCH;
            };
            net.minecraft.world.level.block.Block block = placement.kind() == com.projecthivemind.build.BridgeJob.Kind.TORCH
                    ? net.minecraft.world.level.block.Blocks.TORCH
                    : placement.kind() == com.projecthivemind.build.BridgeJob.Kind.FENCE ? fenceBlock(item) : fillBlock(item);
            if (block == null || !level.isUnobstructed(block.defaultBlockState(), placement.pos(), net.minecraft.world.phys.shapes.CollisionContext.empty())) {
                continue;
            }
            state = block.defaultBlockState();
            next = placement;
            break;
        }
        if (next == null) {
            bridge = null;
            if (heart.getServer() != null && heart.ownerId() != null) {
                net.minecraft.server.level.ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
                if (owner != null) {
                    owner.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.projecthivemind.bridge_finished"), false);
                }
            }
            return;
        }
        if (heart.getStorage().countItem(item) <= 0) {
            // Out of what this block is made of: wait for the hive to get some.
            nextBridgeScan = this.tickCount + 40;
            return;
        }
        if (WorkerDigGoal.inDigReach(this, next.pos())) {
            this.getNavigation().stop();
            if (next.kind() == com.projecthivemind.build.BridgeJob.Kind.FENCE) {
                state = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, level, next.pos());
            }
            heart.getStorage().removeItemType(item, 1);
            level.setBlock(next.pos(), state, net.minecraft.world.level.block.Block.UPDATE_ALL);
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
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> behavior.stayInside() && !inTeam()));
        // The last thing a unit does: when idle and set to, walk about inside the border.
        this.goalSelector.addGoal(5, new WanderInsideGoal(this, () -> behavior.wander()));
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

    private int nextJobScan;
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
            // Not tickCount % N: use a deadline, so the timing never depends on the entity id.
            if (action == null && heart != null && (this.tickCount >= nextJobScan || !fellQueue.isEmpty() || flattenActive)) {
                nextJobScan = this.tickCount + JOB_SCAN_INTERVAL;
                findOwnWork(heart);
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
        staircase = null;
        wallItem = null;
        fellQueue.clear();
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
