package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;
import com.projecthivemind.client.ClientSelection;

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
        this.goalSelector.addGoal(1, new WorkerDigGoal(this));
        this.goalSelector.addGoal(1, new InteractBlockGoal(this));
        this.goalSelector.addGoal(1, new WorkerBuildGoal(this));
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
            // Not tickCount % N: use a deadline, so the timing never depends on the entity id.
            if (action == null && heart != null && this.tickCount >= nextJobScan) {
                nextJobScan = this.tickCount + JOB_SCAN_INTERVAL;
                findOwnWork(heart);
            }
        }
    }

    /** With no orders and not selected, look for work the hive's worker settings allow. */
    private void findOwnWork(HiveHeart heart) {
        if (heart.isUnitSelected(this.getId()) || !heart.workerBehavior().any()) {
            return;
        }
        UnitAction job = WorkerAutoJobs.findJob(this, heart);
        if (job != null) {
            action = job;
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

    @Nullable
    @Override
    public UnitAction action() {
        return action;
    }

    @Override
    public void setAction(@Nullable UnitAction action) {
        this.action = action;
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
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
        tag.putInt(GEAR_VERSION_TAG, gearVersion);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        gearVersion = tag.getInt(GEAR_VERSION_TAG);
    }
}
