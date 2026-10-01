package com.projecthivemind.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HivemindManager;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Hive Heart: a stationary entity that is the hive. It has health, owns the hive's shared inventory and
 * knows the hive's level. The chunks around it are force-loaded, so it stays available while the camera roams.
 */
public class HiveHeart extends Mob {
    private static final String OWNER_TAG = "HiveOwner";
    private static final String LEVEL_TAG = "HiveLevel";
    private static final String STORAGE_TAG = "HiveStorage";
    private static final String ARMOR_TAG = "HiveArmor";
    private static final String TOOLS_TAG = "HiveTools";

    @Nullable
    private UUID ownerId;
    private int hiveLevel = 1;
    private SimpleContainer storage = new SimpleContainer(HiveLevels.get(1).storageSlots());
    /** One piece per armor slot, in {@link HiveEquipment#ARMOR_SLOTS} order. New soldiers get copies of these. */
    private final SimpleContainer armorSlots = new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length);
    /** Tools and weapons. New soldiers wield a copy of the one with the highest attack damage. */
    private final SimpleContainer toolSlots = new SimpleContainer(HiveEquipment.TOOL_SLOTS);

    /** The loaded Hive Heart with this id, or null. */
    @Nullable
    public static HiveHeart find(Level level, @Nullable UUID id) {
        if (id != null && level instanceof ServerLevel serverLevel
                && serverLevel.getEntity(id) instanceof HiveHeart heart && heart.isAlive()) {
            return heart;
        }
        return null;
    }

    /** 10 seconds. */
    private static final int COLLECTOR_INTERVAL_TICKS = 200;

    private int collectorTimer;

    public HiveHeart(EntityType<? extends HiveHeart> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        // The Heart grows its own collectors: one every interval while the hive has fewer than its cap.
        if (!this.level().isClientSide && ++collectorTimer >= COLLECTOR_INTERVAL_TICKS) {
            collectorTimer = 0;
            HivemindManager.tickCollectorSpawn(this);
        }
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

    public SimpleContainer getArmorGear() {
        return armorSlots;
    }

    public SimpleContainer getToolGear() {
        return toolSlots;
    }

    /**
     * A soldier's copy of a piece of gear lost durability: charge the same amount to the original in the hive's slots.
     * If that wears the original out, it breaks. Does nothing if the original is no longer in a gear slot.
     */
    public void damageLinked(UUID link, int amount) {
        if (amount > 0 && !damageLinkedIn(armorSlots, link, amount)) {
            damageLinkedIn(toolSlots, link, amount);
        }
    }

    private boolean damageLinkedIn(SimpleContainer container, UUID link, int amount) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!link.equals(HiveEquipment.link(stack))) {
                continue;
            }
            int damage = stack.getDamageValue() + amount;
            if (damage >= stack.getMaxDamage()) {
                container.setItem(i, ItemStack.EMPTY);
                this.level().playSound(null, this.blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 0.8F, 0.8F + this.random.nextFloat() * 0.4F);
            } else {
                stack.setDamageValue(damage);
                container.setChanged();
            }
            return true;
        }
        return false;
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
        tag.put(ARMOR_TAG, ContainerHelper.saveAllItems(new CompoundTag(), armorSlots.getItems(), registryAccess()));
        tag.put(TOOLS_TAG, ContainerHelper.saveAllItems(new CompoundTag(), toolSlots.getItems(), registryAccess()));
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
        if (tag.contains(ARMOR_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(ARMOR_TAG), armorSlots.getItems(), registryAccess());
        }
        if (tag.contains(TOOLS_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(TOOLS_TAG), toolSlots.getItems(), registryAccess());
        }
    }
}
