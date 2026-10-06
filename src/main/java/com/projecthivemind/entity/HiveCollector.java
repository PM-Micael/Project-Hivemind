package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;
import com.projecthivemind.client.ClientSelection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;

/**
 * Collector unit. Looks like a silverfish. It fetches items lying in the hive area and delivers them into the hive's
 * inventory, and it can be given planting tasks: crops and saplings, each planted on soil blocks the player has picked. It
 * only ever works inside the hive area. A collector can be selected, on its own, only to be given those tasks.
 */
public class HiveCollector extends Silverfish implements HiveUnit {
    /** The most planting spots one collector can have, for each kind of planting. */
    public static final int MAX_PLANT_SPOTS = 16;

    /** The kinds of planting a collector can be given: crops (seeds, carrots...) and saplings. Each has its own spots. */
    public enum PlantKind {
        CROP, SAPLING
    }

    /** One planting task: what is planted, and the blocks (the soil) it is planted on. Set from the hive menu and the world. */
    public static final class PlantTask {
        @Nullable
        private Item item;
        private final List<BlockPos> spots = new ArrayList<>();

        @Nullable
        public Item item() {
            return item;
        }

        public List<BlockPos> spots() {
            return spots;
        }
    }

    private final PlantTask crops = new PlantTask();
    private final PlantTask saplings = new PlantTask();
    /** The seed or sapling it is carrying from the Heart to a spot (one), or empty. Saved. */
    private ItemStack plantCarried = ItemStack.EMPTY;
    /** The tick box for picking up items inside the border. On to begin with (and for collectors saved before there was a box). Saved. */
    private boolean pickUpItems = true;

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

    // ---- outline: white, yellow when selected, visible through walls, for the owner only ----

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
        this.goalSelector.addGoal(1, new LeavePortalGoal(this));

        this.goalSelector.addGoal(2, new CollectItemsGoal(this));
        // Planting comes before collecting: a collector with something to plant plants it first, but one that is already carrying an item
        // to the Heart finishes that trip first (see CollectorPlantGoal#canUse).
        this.goalSelector.addGoal(1, new CollectorPlantGoal(this));
        // Idle inside the border: stand in the Heart.
        this.goalSelector.addGoal(6, new GatherAtHeartGoal(this, () -> true));
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

    public boolean pickUpItems() {
        return pickUpItems;
    }

    @Override
    public int behaviorFlags() {
        return pickUpItems ? 1 : 0;
    }

    @Override
    public void setBehavior(int flags, int[] radii) {
        this.pickUpItems = (flags & 1) != 0;
    }

    /** Collectors take no orders: they never have an action. Their tasks are the planting ones. */
    @Nullable
    @Override
    public UnitAction action() {
        return null;
    }

    @Override
    public void setAction(@Nullable UnitAction action) {
    }

    /** The pathfinder does not know the Heart's body is solid, so a unit would press against it and never get past: units walk through it. */
    @Override
    public boolean canCollideWith(net.minecraft.world.entity.Entity other) {
        return !(other instanceof HiveHeart) && super.canCollideWith(other);
    }

    /** Short, so a unit can use a portal again soon after coming through one. */
    @Override
    public int getDimensionChangingDelay() {
        return 40;
    }

    @Nullable
    @Override
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
        if (!this.level().isClientSide) {
            if (!carried.isEmpty()) {
                this.spawnAtLocation(carried);
                carried = ItemStack.EMPTY;
            }
            if (!plantCarried.isEmpty()) {
                this.spawnAtLocation(plantCarried);
                plantCarried = ItemStack.EMPTY;
            }
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

    // ---- planting tasks ----

    public PlantTask task(PlantKind kind) {
        return kind == PlantKind.CROP ? crops : saplings;
    }

    /** The seed or sapling it is carrying from the Heart to a spot, or empty. */
    public ItemStack plantCarried() {
        return plantCarried;
    }

    public void setPlantCarried(ItemStack stack) {
        this.plantCarried = stack;
    }

    /** Choose what a kind of planting plants: an item that cannot be planted that way clears it. */
    public void setPlantItem(PlantKind kind, @Nullable Item item) {
        task(kind).item = item != null && plantBlock(kind, item) != null ? item : null;
    }

    /** Add a soil block to plant on, up to {@link #MAX_PLANT_SPOTS}; false if it is there already or there is no room. */
    public boolean addPlantSpot(PlantKind kind, BlockPos pos) {
        List<BlockPos> spots = task(kind).spots;
        if (spots.size() >= MAX_PLANT_SPOTS || spots.contains(pos)) {
            return false;
        }
        spots.add(pos.immutable());
        return true;
    }

    public void removePlantSpot(PlantKind kind, BlockPos pos) {
        task(kind).spots.remove(pos);
    }

    public void clearPlantSpots(PlantKind kind) {
        task(kind).spots.clear();
    }

    /**
     * The block this item plants for this kind of planting, or null if it is not something that can be planted that way.
     * Crops: seeds, carrots, potatoes, nether warts, melon and pumpkin seeds, and the like. Saplings: the trees' saplings
     * and mangrove propagules.
     */
    /** The Hive Heart this collector works for, or null if it is gone or not loaded. */
    @Nullable
    public static Block plantBlock(PlantKind kind, Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return null;
        }
        Block block = blockItem.getBlock();
        if (kind == PlantKind.SAPLING) {
            return block instanceof SaplingBlock || block instanceof MangrovePropaguleBlock ? block : null;
        }
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof NetherWartBlock
                || block instanceof PitcherCropBlock || block instanceof TorchflowerCropBlock ? block : null;
    }

    // ---- saving ----

    private static void saveTask(CompoundTag tag, String key, PlantTask task) {
        CompoundTag saved = new CompoundTag();
        if (task.item != null) {
            saved.putString("Item", BuiltInRegistries.ITEM.getKey(task.item).toString());
        }
        ListTag list = new ListTag();
        for (BlockPos spot : task.spots) {
            list.add(NbtUtils.writeBlockPos(spot));
        }
        saved.put("Spots", list);
        tag.put(key, saved);
    }

    private void loadTask(CompoundTag tag, String key, PlantKind kind) {
        PlantTask task = task(kind);
        task.item = null;
        task.spots.clear();
        if (!tag.contains(key)) {
            return;
        }
        CompoundTag saved = tag.getCompound(key);
        if (saved.contains("Item")) {
            ResourceLocation id = ResourceLocation.tryParse(saved.getString("Item"));
            setPlantItem(kind, id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null));
        }
        ListTag list = saved.getList("Spots", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size() && i < MAX_PLANT_SPOTS; i++) {
            if (list.get(i) instanceof IntArrayTag array && array.size() == 3) {
                task.spots.add(new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()));
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        saveTask(tag, "Crops", crops);
        saveTask(tag, "Saplings", saplings);
        tag.putBoolean("PickUpItems", pickUpItems);
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
        if (!plantCarried.isEmpty()) {
            tag.put("PlantCarried", plantCarried.save(registryAccess()));
        }
        if (!carried.isEmpty()) {
            tag.put(CARRIED_TAG, carried.save(registryAccess()));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        loadTask(tag, "Crops", PlantKind.CROP);
        loadTask(tag, "Saplings", PlantKind.SAPLING);
        pickUpItems = !tag.contains("PickUpItems") || tag.getBoolean("PickUpItems");
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        plantCarried = tag.contains("PlantCarried") ? ItemStack.parse(registryAccess(), tag.get("PlantCarried")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        if (tag.contains(CARRIED_TAG)) {
            carried = ItemStack.parse(registryAccess(), tag.get(CARRIED_TAG)).orElse(ItemStack.EMPTY);
        }
    }
}
