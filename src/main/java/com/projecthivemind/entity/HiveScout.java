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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * Scout unit. Looks like a husk and, like every unit, is passive: no combat AI, never despawns, no experience, no
 * drops. It can be selected and sent places; what a scout is actually for is still to come.
 */
public class HiveScout extends Husk implements HiveUnit {
    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveScout.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final String HEART_TAG = "HiveHeartId";

    @Nullable
    private UUID heartId;
    @Nullable
    private UnitAction action;

    public HiveScout(EntityType<? extends HiveScout> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createScoutAttributes() {
        return Zombie.createAttributes().add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: husk goals hunt players, villagers and turtle eggs.
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    @Nullable
    @Override
    public HiveHeart findHeart() {
        return HiveHeart.find(this.level(), heartId);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && action != null && action.kind() == UnitAction.Kind.WALK && this.getNavigation().isDone()) {
            action = null;
        }
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    @Override
    protected boolean convertsInWater() {
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

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
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
        return UnitKind.SCOUT;
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
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
    }
}
