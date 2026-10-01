package com.projecthivemind.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * The Hive Heart: a stationary entity that is the hive. It has health, owns the hive's shared inventory and
 * knows the hive's level. The chunks around it are force-loaded, so it stays available while the camera roams.
 */
public class HiveHeart extends Mob {
    private static final String OWNER_TAG = "HiveOwner";
    private static final String LEVEL_TAG = "HiveLevel";
    private static final String STORAGE_TAG = "HiveStorage";

    @Nullable
    private UUID ownerId;
    private int hiveLevel = 1;
    private SimpleContainer storage = new SimpleContainer(HiveLevels.get(1).storageSlots());

    public HiveHeart(EntityType<? extends HiveHeart> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createHeartAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, HiveLevels.get(1).maxHealth())
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    public int hiveLevel() {
        return hiveLevel;
    }

    public SimpleContainer getStorage() {
        return storage;
    }

    /** Apply a level's stats: full health at the new maximum, and storage resized without losing items. */
    public void setHiveLevel(int newLevel) {
        HiveLevel definition = HiveLevels.get(newLevel);
        this.hiveLevel = definition.level();
        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(definition.maxHealth());
        this.setHealth(this.getMaxHealth());
        if (storage.getContainerSize() != definition.storageSlots()) {
            SimpleContainer resized = new SimpleContainer(definition.storageSlots());
            for (int i = 0; i < Math.min(storage.getContainerSize(), resized.getContainerSize()); i++) {
                resized.setItem(i, storage.getItem(i));
            }
            this.storage = resized;
        }
    }

    // ---- stay put, never despawn ----

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WART_BLOCK_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WART_BLOCK_BREAK;
    }

    // ---- persistence ----

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) {
            tag.putUUID(OWNER_TAG, ownerId);
        }
        tag.putInt(LEVEL_TAG, hiveLevel);
        tag.put(STORAGE_TAG, ContainerHelper.saveAllItems(new CompoundTag(), storage.getItems(), registryAccess()));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(OWNER_TAG)) {
            ownerId = tag.getUUID(OWNER_TAG);
        }
        if (tag.contains(LEVEL_TAG)) {
            hiveLevel = HiveLevels.get(tag.getInt(LEVEL_TAG)).level();
        }
        storage = new SimpleContainer(HiveLevels.get(hiveLevel).storageSlots());
        if (tag.contains(STORAGE_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(STORAGE_TAG), storage.getItems(), registryAccess());
        }
    }
}
