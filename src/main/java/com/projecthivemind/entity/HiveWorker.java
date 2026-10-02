package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

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
import net.minecraft.world.entity.ai.goal.FloatGoal;
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

    /** The staircase this worker is digging down, if it was given one. Saved. */
    @Nullable
    private com.projecthivemind.build.StairDig staircase;
    private int nextStairScan;
    /** The dig order the staircase last gave this worker, so the digging goal can tell a staircase block from any other. */
    @Nullable
    private UnitAction stairAction;

    /** True while the worker is on a block of its staircase: what it breaks goes into the hive. */
    public boolean onStaircase() {
        return staircase != null && stairAction != null && stairAction.equals(action);
    }

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
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: skeleton goals flee the sun and shoot players.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Above everything else: a unit told to stay inside the hive border does.
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> behavior.stayInside()));
        // The last thing a unit does: when idle and set to, walk about inside the border.
        this.goalSelector.addGoal(5, new WanderInsideGoal(this, () -> behavior.wander()));
        this.goalSelector.addGoal(1, new WorkerDigGoal(this));
        this.goalSelector.addGoal(1, new InteractBlockGoal(this));
        this.goalSelector.addGoal(1, new WorkerBuildGoal(this));
        this.goalSelector.addGoal(1, new WorkerFillGoal(this));
    }

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
    /** Take a set-aside job up again once the unit has nothing to do and the player has let go of it. */
    private void resumeJobIfFree(@Nullable HiveHeart heart) {
        if (action == null && job != null && resumeJob && heart != null && !heart.isUnitSelected(this.getId())) {
            setAction(job);
        }
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
            if (action != null && action.kind() == UnitAction.Kind.WALK && this.getNavigation().isDone()) {
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
                    stairAction = next;
                    setAction(next);
                }
            }
            // Not tickCount % N: use a deadline, so the timing never depends on the entity id.
            if (action == null && heart != null && this.tickCount >= nextJobScan) {
                nextJobScan = this.tickCount + JOB_SCAN_INTERVAL;
                findOwnWork(heart);
            }
        }
    }


    /** With no orders and not selected, look for work the hive's worker settings allow. */
    private void findOwnWork(HiveHeart heart) {
        if (heart.isUnitSelected(this.getId())) {
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
        staircase = tag.contains("Staircase") ? com.projecthivemind.build.StairDig.load(tag.getCompound("Staircase")) : null;
        net.minecraft.resources.ResourceLocation fillId = tag.contains("FillItem") ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("FillItem")) : null;
        setFillItem(fillId == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(fillId).orElse(null));
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
