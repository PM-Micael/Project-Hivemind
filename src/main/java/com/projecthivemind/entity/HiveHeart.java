package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveFood;
import com.projecthivemind.HiveFurnace;
import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HiveSight;
import com.projecthivemind.build.TowerBuild;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModComponents;
import com.projecthivemind.ScoutItems;
import com.projecthivemind.UnitKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static final String LOGS_TAG = "QuestLogs";
    private static final String EXPLORED_TAG = "ExploredChunks";
    private static final String KILLS_TAG = "QuestKills";
    private static final String AGE_TAG = "HiveAge";
    private static final String FURNACE_TAG = "HiveFurnace";
    private static final String SCOUT_HAND_TAG = "ScoutHand";
    private static final String FOOD_SLOT_TAG = "FoodSlot";
    private static final String STORAGE_TAG = "HiveStorage";
    private static final String ARMOR_TAG = "HiveArmor";
    private static final String TOOLS_TAG = "HiveTools";
    private static final String ARMOR_VERSION_TAG = "ArmorVersion";
    private static final String TOOL_VERSION_TAG = "ToolVersion";

    @Nullable
    private UUID ownerId;
    private int hiveLevel = 1;
    /** Quest progress: the most logs the hive has held at once, up to what the quest asks. It never goes back down. */
    private int logsProgress;
    /** Quest progress: the most coal and raw iron the hive has held at once (up to what the quest asks), and the lowest height a unit has been at. */
    private int coalProgress;
    private int ironProgress;
    private int lowestY = Integer.MAX_VALUE;
    /** Quest progress: mobs the hive's units have killed. */
    private int kills;
    /** Quest progress: ticks the hive has lasted, counted only while its owner is in the world. */
    private int ageTicks;
    /** The tower the hive's workers are building, if any. Saved, so that the workers' build jobs carry on after a restart. */
    @Nullable
    private TowerBuild activeBuild;
    /** The health last sent to the owner for the health bar. */
    private float syncedHealth = -1.0F;
    private int syncedArmor = -1;
    private int syncedFood = -1;
    /** The furnace built into the Heart from level 3. Exists at every level so the menu code stays simple. */
    private final HiveFurnace furnace = new HiveFurnace();
    /** The item in the scout's hand, put there from the hive menu. The scout holds a copy, and what it uses comes off this. */
    private final SimpleContainer scoutHand = new SimpleContainer(1);
    /** The food the hive eats from: put in the hive menu, under the armor slots. Only food goes in. */
    private final SimpleContainer foodSlot = new SimpleContainer(1);
    /** The hive's hunger: see HiveFood. */
    private final HiveFood food = new HiveFood();
    /** Quest progress: the chunks (as packed ChunkPos) the hive's units have been in, outside the hive area. */
    private final Set<Long> exploredChunks = new HashSet<>();
    private SimpleContainer storage = new SimpleContainer(HiveLevels.get(1).storageSlots());
    /** One piece per armor slot, in {@link HiveEquipment#ARMOR_SLOTS} order. New soldiers get copies of these. */
    private final SimpleContainer armorSlots = new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length);
    /** Tools and weapons. New soldiers wield a copy of the one with the highest attack damage. */
    private final SimpleContainer toolSlots = new SimpleContainer(HiveEquipment.TOOL_SLOTS);

    /** What the hive can see from, refreshed several times a second. Not saved. */
    private List<HiveSight.Eye> sightEyes = List.of();
    /** The mobs the owner's client was last told are in sight. Not saved. */
    private Set<Integer> syncedSight = Set.of();



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
    /** The hive's teams of units. Saved. */
    private final HiveTeams teams = new HiveTeams();



    public HiveTeams teams() {
        return teams;
    }

    /**
     * The scout this unit's team follows, or null: when the unit is in a team that has a scout, and is not that scout itself. Only the
     * first scout found counts.
     */
    @Nullable
    public Mob teamLeader(Mob member) {
        int team = teams.teamOf(member.getUUID());
        if (team < 0 || !(this.level() instanceof ServerLevel level)) {
            return null;
        }
        for (UUID id : teams.members(team)) {
            if (!id.equals(member.getUUID()) && level.getEntity(id) instanceof HiveScout scout && scout.isAlive()) {
                return scout;
            }
        }
        return null;
    }

    /** Units the player has selected follow orders only; they ignore the hive's default behaviour. */
    public boolean isUnitSelected(int entityId) {
        return selectedUnits.contains(entityId);
    }

    public void setSelectedUnits(Set<Integer> entityIds) {
        this.selectedUnits = entityIds;
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
     * Look for changes to the armor and tool slots since last time, bump the versions if there are any, and say which changed (bit 1 armor,
     * bit 2 tools; 0 for none). Only a real
     * change counts: a tool wearing down, or the hidden link stamp being added, does not.
     */
    public int refreshGearVersions() {
        List<ItemStack> armor = signature(armorSlots);
        List<ItemStack> tools = signature(toolSlots);
        if (lastArmorSignature == null || lastToolSignature == null) {
            // First look since the Heart loaded: take it as the starting point rather than as a change.
            lastArmorSignature = armor;
            lastToolSignature = tools;
            return 0;
        }
        int changed = 0;
        if (!sameSignature(armor, lastArmorSignature)) {
            armorVersion++;
            lastArmorSignature = armor;
            changed |= 1;
        }
        if (!sameSignature(tools, lastToolSignature)) {
            toolVersion++;
            lastToolSignature = tools;
            changed |= 2;
        }
        return changed;
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
    public static final int QUEST_INTERVAL_TICKS = 20;

    /** A quarter second: how often what the hive can see is worked out, so hidden mobs appear and vanish promptly. */
    private static final int SIGHT_INTERVAL_TICKS = 5;

    private int spawnTimer;

    public HiveHeart(EntityType<? extends HiveHeart> type, Level level) {
        super(type, level);
        // The armor it wears is the hive's: it is dropped with the rest of the hive's things (see HivemindManager), not here too.
        for (net.minecraft.world.entity.EquipmentSlot slot : HiveEquipment.ARMOR_SLOTS) {
            this.setDropChance(slot, 0.0F);
        }
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
            HivemindManager.tickHealthSync(this);
        }
        if (this.tickCount % 5 == 0) {
            HivemindManager.tickGearSync(this);
        }
        HivemindManager.tickNaturalSpawning(this);
        if (this.level() instanceof ServerLevel foodLevel) {
            food.tick(this, foodLevel);
        }
        if (this.tickCount % 10 == 0) {
            wearHiveArmor();
            if (ownerId != null && this.getServer() != null) {
                ServerPlayer owner = this.getServer().getPlayerList().getPlayer(ownerId);
                if (owner != null) {
                    ScoutItems.tickBook(this, owner);
                }
            }
        }
        if (hiveLevel >= HiveLevels.FURNACE_LEVEL && this.level() instanceof ServerLevel serverLevel) {
            furnace.tick(serverLevel);
        }
        if (this.tickCount % QUEST_INTERVAL_TICKS == 0) {
            HivemindManager.tickQuests(this);
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

    public int logsProgress() {
        return logsProgress;
    }

    public void setLogsProgress(int logs) {
        this.logsProgress = logs;
    }

    public int coalProgress() {
        return coalProgress;
    }

    public void setCoalProgress(int coal) {
        this.coalProgress = coal;
    }

    public int ironProgress() {
        return ironProgress;
    }

    public void setIronProgress(int iron) {
        this.ironProgress = iron;
    }

    /** The lowest block height one of the hive's units has stood at, or Integer.MAX_VALUE if none has been counted. */
    public int lowestY() {
        return lowestY;
    }

    public void setLowestY(int y) {
        this.lowestY = y;
    }

    public int kills() {
        return kills;
    }

    public void addKill() {
        kills++;
    }

    public int syncedFood() {
        return syncedFood;
    }

    public void setSyncedFood(int food) {
        this.syncedFood = food;
    }

    public int syncedArmor() {
        return syncedArmor;
    }

    public void setSyncedArmor(int armor) {
        this.syncedArmor = armor;
    }

    public float syncedHealth() {
        return syncedHealth;
    }

    public void setSyncedHealth(float health) {
        this.syncedHealth = health;
    }

    @Nullable
    public TowerBuild activeBuild() {
        return activeBuild;
    }

    public void setActiveBuild(@Nullable TowerBuild build) {
        this.activeBuild = build;
    }

    public int ageTicks() {
        return ageTicks;
    }

    public void addAge(int ticks) {
        ageTicks += ticks;
    }

    /**
     * The Heart wears the armor in the hive's armor slots: the very same stacks, so it gets what armor gives (armor and
     * toughness points, knockback resistance, protection enchantments) and the armor wears down as the Heart takes
     * hits. The game works the armor's attributes out from what is equipped, so this is all it takes.
     */
    private void wearHiveArmor() {
        for (int i = 0; i < HiveEquipment.ARMOR_SLOTS.length; i++) {
            ItemStack stored = armorSlots.getItem(i);
            if (this.getItemBySlot(HiveEquipment.ARMOR_SLOTS[i]) != stored) {
                this.setItemSlot(HiveEquipment.ARMOR_SLOTS[i], stored);
            }
        }
    }

    public HiveFood food() {
        return food;
    }

    public SimpleContainer foodSlot() {
        return foodSlot;
    }

    /** Like a player, the Heart wears down the armor it wears (the hive's own pieces) when it takes a hit. */
    @Override
    protected void hurtArmor(DamageSource source, float damage) {
        this.doHurtEquipment(source, damage, HiveEquipment.ARMOR_SLOTS);
    }

    public SimpleContainer scoutHand() {
        return scoutHand;
    }

    public HiveFurnace furnace() {
        return furnace;
    }

    public Set<Long> exploredChunks() {
        return exploredChunks;
    }

    public int exploredChunkCount() {
        return exploredChunks.size();
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
        tag.putInt(LOGS_TAG, logsProgress);
        tag.putInt("QuestCoal", coalProgress);
        tag.put("Teams", teams.save());
        tag.putInt("QuestIron", ironProgress);
        tag.putInt("QuestLowestY", lowestY);
        tag.putInt(KILLS_TAG, kills);
        tag.putInt(AGE_TAG, ageTicks);
        tag.put(FURNACE_TAG, furnace.save(registryAccess()));
        food.save(tag);
        if (activeBuild != null) {
            tag.put("TowerBuild", activeBuild.save());
        }
        tag.put(SCOUT_HAND_TAG, ContainerHelper.saveAllItems(new CompoundTag(), scoutHand.getItems(), registryAccess()));
        tag.put(FOOD_SLOT_TAG, ContainerHelper.saveAllItems(new CompoundTag(), foodSlot.getItems(), registryAccess()));
        tag.putLongArray(EXPLORED_TAG, exploredChunks.stream().mapToLong(Long::longValue).toArray());
        tag.put(STORAGE_TAG, ContainerHelper.saveAllItems(new CompoundTag(), storage.getItems(), registryAccess()));
        tag.put(ARMOR_TAG, ContainerHelper.saveAllItems(new CompoundTag(), armorSlots.getItems(), registryAccess()));
        tag.put(TOOLS_TAG, ContainerHelper.saveAllItems(new CompoundTag(), toolSlots.getItems(), registryAccess()));
        tag.putInt(ARMOR_VERSION_TAG, armorVersion);
        tag.putInt(TOOL_VERSION_TAG, toolVersion);

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
        logsProgress = tag.getInt(LOGS_TAG);
        coalProgress = tag.getInt("QuestCoal");
        teams.load(tag.getList("Teams", net.minecraft.nbt.Tag.TAG_COMPOUND));
        ironProgress = tag.getInt("QuestIron");
        lowestY = tag.contains("QuestLowestY") ? tag.getInt("QuestLowestY") : Integer.MAX_VALUE;
        kills = tag.getInt(KILLS_TAG);
        ageTicks = tag.getInt(AGE_TAG);
        food.load(tag);
        activeBuild = tag.contains("TowerBuild") ? TowerBuild.load(tag.getCompound("TowerBuild")) : null;
        foodSlot.clearContent();
        if (tag.contains(FOOD_SLOT_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(FOOD_SLOT_TAG), foodSlot.getItems(), registryAccess());
        }
        scoutHand.clearContent();
        if (tag.contains(SCOUT_HAND_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(SCOUT_HAND_TAG), scoutHand.getItems(), registryAccess());
        }
        if (tag.contains(FURNACE_TAG)) {
            furnace.load(tag.getCompound(FURNACE_TAG), registryAccess());
        }
        exploredChunks.clear();
        for (long chunk : tag.getLongArray(EXPLORED_TAG)) {
            exploredChunks.add(chunk);
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

    }
}
