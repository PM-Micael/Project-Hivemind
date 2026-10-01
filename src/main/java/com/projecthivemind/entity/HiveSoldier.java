package com.projecthivemind.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * Soldier unit. Looks like a zombie but is a passive unit that only does what its owner commands:
 * no sunburn, no drowned conversion, no reinforcements, no targeting on its own, never despawns.
 */
public class HiveSoldier extends Zombie implements HiveUnit {
    @Nullable
    private UUID ownerId;

    public HiveSoldier(EntityType<? extends HiveSoldier> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createHiveAttributes() {
        return Zombie.createAttributes().add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D);
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: zombie goals hunt players, villagers and turtle eggs.
        this.goalSelector.addGoal(0, new FloatGoal(this));
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

    @Override
    public UnitKind kind() {
        return UnitKind.SOLDIER;
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
