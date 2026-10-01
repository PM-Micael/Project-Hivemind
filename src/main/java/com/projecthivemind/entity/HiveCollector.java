package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

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
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Collector unit. Looks like a silverfish and cannot be commanded: it fetches items lying near the Hive Heart
 * and delivers them into the hive's inventory.
 */
public class HiveCollector extends Silverfish implements HiveUnit {
    private static final String HEART_TAG = "HiveHeartId";
    private static final String CARRIED_TAG = "Carried";

    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveCollector.class, EntityDataSerializers.OPTIONAL_UUID);

    @Nullable
    private UUID heartId;
    private ItemStack carried = ItemStack.EMPTY;

    public HiveCollector(EntityType<? extends HiveCollector> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    // ---- outline: always white (collectors cannot be selected), visible through walls, for the owner only ----

    @Override
    public boolean isCurrentlyGlowing() {
        return this.level().isClientSide ? ClientSelection.shouldGlow(ownerId()) : super.isCurrentlyGlowing();
    }

    @Override
    public int getTeamColor() {
        return this.level().isClientSide ? ClientSelection.outlineColor(this.getId()) : super.getTeamColor();
    }

    public static AttributeSupplier.Builder createCollectorAttributes() {
        return Silverfish.createAttributes().add(Attributes.MOVEMENT_SPEED, 0.3D);
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: silverfish goals hide in stone, wake friends and attack players.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CollectItemsGoal(this));
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    /** Units give no experience when they die. */
    @Override
    protected void dropExperience(@Nullable Entity killer) {
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    /** The Hive Heart this collector works for, or null if it is gone or not loaded. */
    @Nullable
    public HiveHeart findHeart() {
        if (heartId != null && this.level() instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(heartId);
            if (entity instanceof HiveHeart heart && heart.isAlive()) {
                return heart;
            }
        }
        return null;
    }

    public ItemStack carried() {
        return carried;
    }

    public void setCarried(ItemStack stack) {
        this.carried = stack;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide && !carried.isEmpty()) {
            this.spawnAtLocation(carried);
            carried = ItemStack.EMPTY;
        }
    }

    @Override
    public UnitKind kind() {
        return UnitKind.COLLECTOR;
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

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
        if (!carried.isEmpty()) {
            tag.put(CARRIED_TAG, carried.save(registryAccess()));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        if (tag.contains(CARRIED_TAG)) {
            carried = ItemStack.parse(registryAccess(), tag.get(CARRIED_TAG)).orElse(ItemStack.EMPTY);
        }
    }
}
