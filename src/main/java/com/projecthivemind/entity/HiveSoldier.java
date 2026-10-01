package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveEquipment;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Soldier unit. Looks like a zombie but is a passive unit that only does what its owner commands:
 * no sunburn, no drowned conversion, no reinforcements, no targeting on its own, never despawns.
 */
public class HiveSoldier extends Zombie implements HiveUnit {
    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveSoldier.class, EntityDataSerializers.OPTIONAL_UUID);

    private static final String HEART_TAG = "HiveHeartId";

    @Nullable
    private UUID heartId;

    // What each equipment slot held last tick, to notice durability the soldier loses. Not saved: after a reload the
    // current state is simply taken as the new baseline.
    private final UUID[] linkOf = new UUID[EquipmentSlot.values().length];
    private final int[] damageOf = new int[EquipmentSlot.values().length];
    private final int[] maxDamageOf = new int[EquipmentSlot.values().length];

    public HiveSoldier(EntityType<? extends HiveSoldier> type, Level level) {
        super(type, level);
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            mirrorDurability();
        }
    }

    /**
     * The gear on this soldier is a copy of pieces in the Hive Heart. Whenever a copy loses durability, whether from
     * damage taken or anything else that wears it, the same amount is charged to the original in the hive.
     */
    private void mirrorDurability() {
        HiveHeart heart = HiveHeart.find(this.level(), heartId);
        if (heart == null) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            int i = slot.ordinal();
            ItemStack stack = this.getItemBySlot(slot);
            UUID link = HiveEquipment.link(stack);

            if (link != null && link.equals(linkOf[i])) {
                int lost = stack.getDamageValue() - damageOf[i];
                if (lost > 0) {
                    heart.damageLinked(link, lost);
                }
                damageOf[i] = stack.getDamageValue();
            } else {
                if (linkOf[i] != null && stack.isEmpty()) {
                    // The piece broke: the original gets whatever durability the copy still had.
                    heart.damageLinked(linkOf[i], maxDamageOf[i] - damageOf[i]);
                }
                linkOf[i] = link;
                if (link != null) {
                    damageOf[i] = stack.getDamageValue();
                    maxDamageOf[i] = stack.getMaxDamage();
                }
            }
        }
    }

    /** Soldiers never drop their gear: it is a copy, and the original stays in the hive. */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
    }

    public static AttributeSupplier.Builder createHiveAttributes() {
        return Zombie.createAttributes().add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
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
        return UnitKind.SOLDIER;
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
