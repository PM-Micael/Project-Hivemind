package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.CollectorBehavior;
import com.projecthivemind.HiveActions;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HiveSight;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModComponents;
import com.projecthivemind.SoldierBehavior;
import com.projecthivemind.UnitKind;
import com.projecthivemind.WorkerBehavior;

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
    private static final String BEHAVIOR_TAG = "SoldierBehavior";
    private static final String WORKER_BEHAVIOR_TAG = "WorkerBehavior";
    private static final String COLLECTOR_BEHAVIOR_TAG = "CollectorBehavior";

    @Nullable
    private UUID ownerId;
    private int hiveLevel = 1;
    private SimpleContainer storage = new SimpleContainer(HiveLevels.get(1).storageSlots());
    /** One piece per armor slot, in {@link HiveEquipment#ARMOR_SLOTS} order. New soldiers get copies of these. */
    private final SimpleContainer armorSlots = new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length);
    /** Tools and weapons. New soldiers wield a copy of the one with the highest attack damage. */
    private final SimpleContainer toolSlots = new SimpleContainer(HiveEquipment.TOOL_SLOTS);

    /** How the hive's idle, unselected soldiers behave. Edited on the menu's Behavior tab; saved. */
    private SoldierBehavior soldierBehavior = SoldierBehavior.DEFAULT;
    /** How the hive's idle, unselected workers behave. Edited on the menu's Behavior tab; saved. */
    private WorkerBehavior workerBehavior = WorkerBehavior.DEFAULT;
    /** How far past the hive area the hive's collectors may reach. Edited on the menu's Behavior tab; saved. */
    private CollectorBehavior collectorBehavior = CollectorBehavior.DEFAULT;

    public CollectorBehavior collectorBehavior() {
        return collectorBehavior;
    }

    public void setCollectorBehavior(CollectorBehavior behavior) {
        this.collectorBehavior = behavior;
    }

    /** What the hive can see from, refreshed several times a second. Not saved. */
    private List<HiveSight.Eye> sightEyes = List.of();
    /** The mobs the owner's client was last told are in sight. Not saved. */
    private Set<Integer> syncedSight = Set.of();

    public WorkerBehavior workerBehavior() {
        return workerBehavior;
    }

    public void setWorkerBehavior(WorkerBehavior behavior) {
        this.workerBehavior = behavior;
    }

    public List<HiveSight.Eye> sightEyes() {
        return sightEyes;
    }

    public void setSightEyes(List<HiveSight.Eye> eyes) {
        this.sightEyes = eyes;
    }

    public Set<Integer> syncedSight() {
        return syncedSight;
    }

    public void setSyncedSight(Set<Integer> mobIds) {
        this.syncedSight = mobIds;
    }

    /** Entity ids of the units the owner has selected right now, as their client reports. Not saved. */
    private Set<Integer> selectedUnits = Set.of();
    /** Mobs that have hurt the hive or its units, or are trying to, as of the last look. Not saved. */
    private Set<UUID> threats = Set.of();

    public SoldierBehavior soldierBehavior() {
        return soldierBehavior;
    }

    public void setSoldierBehavior(SoldierBehavior behavior) {
        this.soldierBehavior = behavior;
    }

    /** Units the player has selected follow orders only; they ignore the hive's default behaviour. */
    public boolean isUnitSelected(int entityId) {
        return selectedUnits.contains(entityId);
    }

    public void setSelectedUnits(Set<Integer> entityIds) {
        this.selectedUnits = entityIds;
    }

    public boolean isThreat(UUID mobId) {
        return threats.contains(mobId);
    }

    public Set<UUID> threats() {
        return threats;
    }

    public void setThreats(Set<UUID> mobIds) {
        this.threats = mobIds;
    }

    /** Counts how many times the armor slots have really been changed. Saved, so units made earlier stay comparable. */
    private int armorVersion;
    /** Counts how many times the tool slots have really been changed. */
    private int toolVersion;
    /** What the gear slots held when changes were last looked for, to tell a real swap from wear. Not saved. */
    @Nullable
    private List<ItemStack> lastArmorSignature;
    @Nullable
    private List<ItemStack> lastToolSignature;

    /** Ticks until the next spawning interval, which tops up units and refreshes out-of-date ones. */
    public int ticksUntilSpawn() {
        return Math.max(0, SPAWN_INTERVAL_TICKS - spawnTimer);
    }

    /**
     * True if the gear slots have been changed in a way that matters to this kind of unit since the last interval, so
     * the next interval will count it as a change. (Soldiers care about armor and tools, workers only about tools.)
     */
    public boolean gearChangePending(UnitKind kind) {
        if (lastArmorSignature == null || lastToolSignature == null) {
            return false;
        }
        boolean tools = !sameSignature(signature(toolSlots), lastToolSignature);
        boolean armor = !sameSignature(signature(armorSlots), lastArmorSignature);
        return switch (kind) {
            case SOLDIER -> armor || tools;
            case WORKER -> tools;
            case SCOUT, COLLECTOR -> false;
        };
    }

    /**
     * The gear version a unit of this kind is made with. Soldiers care about armor and tools, workers only about
     * tools, and collectors use no gear. A unit made at an older version than this is out of date.
     */
    public int gearVersionFor(UnitKind kind) {
        return switch (kind) {
            case SOLDIER -> armorVersion + toolVersion;
            case WORKER -> toolVersion;
            case SCOUT, COLLECTOR -> 0;
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
    private static final int SPAWN_INTERVAL_TICKS = 200;

    /** 1 second: how often the owner is told which blocks have units working on them. */
    private static final int ACTION_SYNC_INTERVAL_TICKS = 20;

    /** A quarter second: how often what the hive can see is worked out, so hidden mobs appear and vanish promptly. */
    private static final int SIGHT_INTERVAL_TICKS = 5;

    private int spawnTimer;

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
        if (++spawnTimer >= SPAWN_INTERVAL_TICKS) {
            spawnTimer = 0;
            HivemindManager.tickUnitSpawning(this);
        }
        // Sight first, so the workers' scans and the action sync below always use fresh eyes.
        if (this.tickCount % SIGHT_INTERVAL_TICKS == 0) {
            HivemindManager.tickSight(this);
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
        tag.put(BEHAVIOR_TAG, soldierBehavior.save());
        tag.put(WORKER_BEHAVIOR_TAG, workerBehavior.save());
        tag.put(COLLECTOR_BEHAVIOR_TAG, collectorBehavior.save());

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
        soldierBehavior = tag.contains(BEHAVIOR_TAG) ? SoldierBehavior.load(tag.getCompound(BEHAVIOR_TAG)) : SoldierBehavior.DEFAULT;
        workerBehavior = tag.contains(WORKER_BEHAVIOR_TAG) ? WorkerBehavior.load(tag.getCompound(WORKER_BEHAVIOR_TAG)) : WorkerBehavior.DEFAULT;
        collectorBehavior = tag.contains(COLLECTOR_BEHAVIOR_TAG)
                ? CollectorBehavior.load(tag.getCompound(COLLECTOR_BEHAVIOR_TAG)) : CollectorBehavior.DEFAULT;
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
