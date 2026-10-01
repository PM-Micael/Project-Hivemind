package com.projecthivemind.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.level.Level;

/**
 * Worker unit. Looks like a skeleton but is a passive unit that only does what its owner commands:
 * no sunburn, no combat AI, never despawns.
 */
public class HiveWorker extends Skeleton implements HiveUnit {
    @Nullable
    private UUID ownerId;

    public HiveWorker(EntityType<? extends HiveWorker> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: skeleton goals flee the sun and shoot players.
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public UnitKind kind() {
        return UnitKind.WORKER;
    }

    @Nullable
    @Override
    public UUID ownerId() {
        return ownerId;
    }

    @Override
    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
    }
}
