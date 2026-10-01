package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModComponents;
import com.projecthivemind.UnitKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.level.block.state.BlockState;

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
    private static final String CONSUMED_TAG = "ConsumedGround";
    private static final String ARMOR_VERSION_TAG = "ArmorVersion";
    private static final String TOOL_VERSION_TAG = "ToolVersion";

    @Nullable
    private UUID ownerId;
    private int hiveLevel = 1;
    private SimpleContainer storage = new SimpleContainer(HiveLevels.get(1).storageSlots());
    /** One piece per armor slot, in {@link HiveEquipment#ARMOR_SLOTS} order. New soldiers get copies of these. */
    private final SimpleContainer armorSlots = new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length);
    /** Tools and weapons. New soldiers wield a copy of the one with the highest attack damage. */
    private final SimpleContainer toolSlots = new SimpleContainer(HiveEquipment.TOOL_SLOTS);

    /** Counts how many times the armor slots have really been changed. Saved, so units made earlier stay comparable. */
    private int armorVersion;
    /** Counts how many times the tool slots have really been changed. */
    private int toolVersion;
    /** What the gear slots held when changes were last looked for, to tell a real swap from wear. Not saved. */
    @Nullable
    private List<ItemStack> lastArmorSignature;
    @Nullable
    private List<ItemStack> lastToolSignature;

    /**
     * The gear version a unit of this kind is made with. Soldiers care about armor and tools, workers only about
     * tools, and collectors use no gear. A unit made at an older version than this is out of date.
     */
    public int gearVersionFor(UnitKind kind) {
        return switch (kind) {
            case SOLDIER -> armorVersion + toolVersion;
            case WORKER -> toolVersion;
            case COLLECTOR -> 0;
        };
    }

    /**
     * Look for changes to the armor and tool slots since last time and bump the versions if there are any. Only a real
     * change counts: a tool wearing down, or the hidden link stamp being added, does not.
     */
    public void refreshGearVersions() {
        List<ItemStack> armor = signature(armorSlots);
        List<ItemStack> tools = signature(toolSlots);
        if (lastArmorSignature == null || lastToolSignature == null) {
            // First look since the Heart loaded: take it as the starting point rather than as a change.
            lastArmorSignature = armor;
            lastToolSignature = tools;
            return;
        }
        if (!sameSignature(armor, lastArmorSignature)) {
            armorVersion++;
            lastArmorSignature = armor;
        }
        if (!sameSignature(tools, lastToolSignature)) {
            toolVersion++;
            lastToolSignature = tools;
        }
    }

    /** The contents of a gear container, with wear and the link stamp stripped off so only a real swap shows up. */
    private static List<ItemStack> signature(SimpleContainer container) {
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack copy = container.getItem(i).copy();
            if (!copy.isEmpty()) {
                copy.remove(DataComponents.DAMAGE);
                copy.remove(ModComponents.HIVE_LINK.get());
            }
            contents.add(copy);
        }
        return contents;
    }

    private static boolean sameSignature(List<ItemStack> a, List<ItemStack> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** How far along each block being dug is, from 0 to 1. Shared by every worker digging it. Not saved. */
    private final Map<BlockPos, Float> digProgress = new HashMap<>();
    /** What the owner's client was last told units are working on. Not saved. */
    private HiveActions.Snapshot syncedActions = HiveActions.Snapshot.EMPTY;

    /** Add to a block's dig progress and return the new total. */
    public float addDigProgress(BlockPos pos, float amount) {
        return digProgress.merge(pos.immutable(), amount, Float::sum);
    }

    public void clearDigProgress(BlockPos pos) {
        digProgress.remove(pos);
    }

    public HiveActions.Snapshot syncedActions() {
        return syncedActions;
    }

    public void setSyncedActions(HiveActions.Snapshot snapshot) {
        this.syncedActions = snapshot;
    }

    /** The ground the hive's creep has consumed, by position, so destroying the Heart can put it back. */
    private final Map<BlockPos, BlockState> consumedBlocks = new LinkedHashMap<>();

    public Map<BlockPos, BlockState> consumedBlocks() {
        return consumedBlocks;
    }

    public void recordConsumed(BlockPos pos, BlockState original) {
        consumedBlocks.put(pos.immutable(), original);
    }

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

    /** 1 second: how often the owner is told which blocks have units working on them. */
    private static final int ACTION_SYNC_INTERVAL_TICKS = 20;

    private int collectorTimer;

    public HiveHeart(EntityType<? extends HiveHeart> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        // The Heart makes its own units: every interval it tops up what is below the cap and refreshes out-of-date gear.
        if (++collectorTimer >= COLLECTOR_INTERVAL_TICKS) {
            collectorTimer = 0;
            HivemindManager.tickUnitSpawning(this);
        }
        if (this.tickCount % ACTION_SYNC_INTERVAL_TICKS == 0) {
            HivemindManager.tickActionSync(this);
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
        tag.putInt(ARMOR_VERSION_TAG, armorVersion);
        tag.putInt(TOOL_VERSION_TAG, toolVersion);

        ListTag consumed = new ListTag();
        consumedBlocks.forEach((pos, state) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            entry.put("State", NbtUtils.writeBlockState(state));
            consumed.add(entry);
        });
        tag.put(CONSUMED_TAG, consumed);
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
        armorVersion = tag.getInt(ARMOR_VERSION_TAG);
        toolVersion = tag.getInt(TOOL_VERSION_TAG);
        // The signatures are not saved: the first look after loading becomes the baseline.
        lastArmorSignature = null;
        lastToolSignature = null;

        consumedBlocks.clear();
        for (Tag entry : tag.getList(CONSUMED_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag consumed = (CompoundTag) entry;
            consumedBlocks.put(BlockPos.of(consumed.getLong("Pos")),
                    NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), consumed.getCompound("State")));
        }
    }
}
