package com.projecthivemind.menu;

import java.util.Optional;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Pair;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveFurnace;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModMenus;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveHeart;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * The bodyless hivemind's inventory screen: the hive's shared storage, a crafting grid, and the gear slots whose
 * contents new soldiers are equipped with. The hivemind has no body, so nothing here touches the player's own
 * inventory; everything goes to and from the hive.
 */
public class HiveMenu extends AbstractContainerMenu implements SpectatorClickable, ScrollableStorage {
    public static final int GRID_SIZE = 3;

    // Slot indices. The storage grid grows with the hive's level, so everything after it moves with it.
    private final int storageSlots;
    private final int resultIndex;
    private final int gridStart;
    private final int gridEnd;
    private final int armorStart;
    private final int toolsStart;
    private final int toolsEnd;
    private final int furnaceStart;
    private final int furnaceEnd;
    private final boolean hasFurnace;
    private static final int STORAGE_START = 0;

    /**
     * Groups of slots that a tab can show. The storage grid and the gear slots are the base of every inventory tab;
     * the right-hand workstation (the crafting grid, the furnace, later others) is swapped by the tab.
     */
    public static final int GROUP_STORAGE = 1;
    public static final int GROUP_CRAFT = 2;
    public static final int GROUP_FURNACE = 4;
    public static final int GROUP_GEAR = 8;

    // Slot positions inside the panel, shared with the screen.
    public static final int ARMOR_X = 8;
    public static final int ARMOR_Y = 82;
    public static final int STORAGE_X = 44;
    public static final int STORAGE_Y = 82;
    public static final int TOOLS_X = 44;
    /** Slots in a row of the storage grid. */
    public static final int STORAGE_COLUMNS = 9;

    /** Rows in the storage grid. */
    public static int storageRows(int storageSlots) {
        return (storageSlots + STORAGE_COLUMNS - 1) / STORAGE_COLUMNS;
    }

    /** Where the tool row sits: under the storage grid, whatever its size. */
    public static int toolsY(int storageRows) {
        return STORAGE_Y + storageRows * 18 + 8;
    }

    /** The panel's height: the tool row, then the unit counts underneath. */
    public static int panelHeight(int storageRows) {
        return toolsY(storageRows) + 70;
    }

    public int storageSlots() {
        return storageSlots;
    }

    public int storageRows() {
        return storageRows(storageSlots);
    }
    /** The built-in furnace (level 3): input, fuel and output. It takes the crafting grid's place when its tab is open. */
    public static final int FURNACE_INPUT_X = 246;
    public static final int FURNACE_INPUT_Y = 86;
    public static final int FURNACE_FUEL_X = 246;
    public static final int FURNACE_FUEL_Y = 122;
    public static final int FURNACE_OUTPUT_X = 300;
    public static final int FURNACE_OUTPUT_Y = 104;
    public static final int GRID_X = 246;
    public static final int GRID_Y = 82;
    public static final int RESULT_X = 265;
    public static final int RESULT_Y = 146;

    // Synced values: level, health, max health, ticks until the next spawning interval, then for each unit kind its
    // count, its cap, and what the next interval will do for it.
    private static final int DATA_LEVEL = 0;
    private static final int DATA_HEALTH = 1;
    private static final int DATA_MAX_HEALTH = 2;
    private static final int DATA_TIMER = 3;
    private static final int DATA_BEHAVIOR_FLAGS = 4;
    private static final int DATA_UNIT_AREA = 5;
    private static final int DATA_WORKER_FLAGS = 6;
    private static final int DATA_WORKER_AREA = 7;
    private static final int DATA_COLLECTOR_RANGE = 8;
    private static final int DATA_SCOUT_FLAGS = 9;
    private static final int DATA_SCOUT_AREA = 10;
    private static final int DATA_QUEST_LOGS = 11;
    private static final int DATA_QUEST_CHUNKS = 12;
    private static final int DATA_QUEST_KILLS = 13;
    private static final int DATA_QUEST_SECONDS = 14;
    private static final int DATA_FURNACE_LIT = 15;
    private static final int DATA_FURNACE_LIT_DURATION = 16;
    private static final int DATA_FURNACE_COOK = 17;
    private static final int DATA_FURNACE_COOK_TOTAL = 18;
    private static final int DATA_UNITS = 19;
    private static final int VALUES_PER_UNIT = 3;
    public static final int DATA_COUNT = DATA_UNITS + UnitKind.values().length * VALUES_PER_UNIT;

    /** What the next spawning interval will do for a kind of unit. */
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_SPAWNING = 1;
    public static final int STATUS_REFRESHING = 2;

    /** Empty-slot icons, in {@link HiveEquipment#ARMOR_SLOTS} order. */
    private static final ResourceLocation[] ARMOR_ICONS = {
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};

    /** What the storage grid is built on, and scrolled through. Null storage on the client, which has no real one. */
    private final StorageScroll scroll;
    @Nullable
    private final SimpleContainer storage;
    private final ContainerData data;
    private final Player player;
    @Nullable
    private final HiveHeart heart;
    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, GRID_SIZE, GRID_SIZE);
    private final ResultContainer resultSlots = new ResultContainer();

    /** Client-side only: the screen shows the Quests tab, so the slots are hidden. */
    public int visibleGroups = GROUP_STORAGE | GROUP_GEAR | GROUP_CRAFT;

    /** Client constructor: the real contents arrive from the server. */
    public HiveMenu(int containerId, Inventory inventory, int totalStorageSlots, boolean hasFurnace) {
        this(containerId, inventory, null, new StorageScroll(null, totalStorageSlots),
                new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length), new SimpleContainer(HiveEquipment.TOOL_SLOTS),
                new SimpleContainer(3), hasFurnace, new SimpleContainerData(DATA_COUNT), null);
    }

    private HiveMenu(int containerId, Inventory inventory, @Nullable SimpleContainer storage, StorageScroll scroll, SimpleContainer armor,
                     SimpleContainer tools, SimpleContainer furnace, boolean hasFurnace, ContainerData data, @Nullable HiveHeart heart) {
        super(ModMenus.HIVE.get(), containerId);
        this.scroll = scroll;
        this.storageSlots = scroll.visibleSlots(scroll.total());
        this.resultIndex = STORAGE_START + storageSlots;
        this.gridStart = resultIndex + 1;
        this.gridEnd = gridStart + GRID_SIZE * GRID_SIZE;
        this.armorStart = gridEnd;
        this.toolsStart = armorStart + HiveEquipment.ARMOR_SLOTS.length;
        this.toolsEnd = toolsStart + HiveEquipment.TOOL_SLOTS;
        this.hasFurnace = hasFurnace;
        this.furnaceStart = toolsEnd;
        this.furnaceEnd = furnaceStart + (hasFurnace ? 3 : 0);
        this.storage = storage;
        this.data = data;
        this.player = inventory.player;
        this.heart = heart;

        for (int row = 0; row < scroll.visibleRows(); row++) {
            for (int col = 0; col < STORAGE_COLUMNS; col++) {
                int index = col + row * STORAGE_COLUMNS;
                if (index < storageSlots) {
                    this.addSlot(new HiveSlot(scroll.view(), index, STORAGE_X + col * 18, STORAGE_Y + row * 18, GROUP_STORAGE));
                }
            }
        }
        this.addSlot(new HiveResultSlot(player, craftSlots, resultSlots, 0, RESULT_X, RESULT_Y));
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                this.addSlot(new HiveSlot(craftSlots, col + row * GRID_SIZE, GRID_X + col * 18, GRID_Y + row * 18));
            }
        }
        for (int i = 0; i < HiveEquipment.ARMOR_SLOTS.length; i++) {
            this.addSlot(new ArmorSlot(armor, i, ARMOR_X, ARMOR_Y + i * 18, HiveEquipment.ARMOR_SLOTS[i]));
        }
        for (int i = 0; i < HiveEquipment.TOOL_SLOTS; i++) {
            this.addSlot(new ToolSlot(tools, i, TOOLS_X + i * 18, toolsY(scroll.visibleRows())));
        }
        if (hasFurnace) {
            this.addSlot(new HiveSlot(furnace, HiveFurnace.INPUT, FURNACE_INPUT_X, FURNACE_INPUT_Y, GROUP_FURNACE));
            this.addSlot(new FuelSlot(furnace, HiveFurnace.FUEL, FURNACE_FUEL_X, FURNACE_FUEL_Y));
            this.addSlot(new OutputSlot(furnace, HiveFurnace.OUTPUT, FURNACE_OUTPUT_X, FURNACE_OUTPUT_Y));
        }
        this.addDataSlots(data);
        this.addDataSlot(scroll.position());
    }

    /** Server constructor: backed by the Heart's real storage, with live stats for the screen. */
    public static HiveMenu create(int containerId, Inventory inventory, HiveHeart heart, ServerPlayer player) {
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                if (index == DATA_LEVEL) {
                    return heart.hiveLevel();
                }
                if (index == DATA_HEALTH) {
                    return (int) Math.ceil(heart.getHealth());
                }
                if (index == DATA_MAX_HEALTH) {
                    return (int) heart.getMaxHealth();
                }
                if (index == DATA_TIMER) {
                    return heart.ticksUntilSpawn();
                }
                // Both behaviour values are sent plus one, so a value of 0 means "not received yet" on the client.
                if (index == DATA_BEHAVIOR_FLAGS) {
                    return heart.soldierBehavior().flags() + 1;
                }
                if (index == DATA_UNIT_AREA) {
                    return heart.soldierBehavior().unitAreaRadius() + 1;
                }
                if (index == DATA_WORKER_FLAGS) {
                    return heart.workerBehavior().flags() + 1;
                }
                if (index == DATA_WORKER_AREA) {
                    return heart.workerBehavior().unitAreaRadius() + 1;
                }
                if (index == DATA_COLLECTOR_RANGE) {
                    return heart.collectorBehavior().extraRange() + 1;
                }
                if (index == DATA_SCOUT_FLAGS) {
                    return heart.scoutBehavior().flags() + 1;
                }
                if (index == DATA_QUEST_LOGS) {
                    return heart.logsProgress();
                }
                if (index == DATA_QUEST_KILLS) {
                    return heart.kills();
                }
                if (index == DATA_QUEST_SECONDS) {
                    return Math.min(heart.ageTicks() / 20, 32000);
                }
                if (index == DATA_FURNACE_LIT) {
                    return heart.furnace().litTime();
                }
                if (index == DATA_FURNACE_LIT_DURATION) {
                    return heart.furnace().litDuration();
                }
                if (index == DATA_FURNACE_COOK) {
                    return heart.furnace().cookingProgress();
                }
                if (index == DATA_FURNACE_COOK_TOTAL) {
                    return heart.furnace().cookingTotal();
                }
                if (index == DATA_QUEST_CHUNKS) {
                    return heart.exploredChunkCount();
                }
                if (index == DATA_SCOUT_AREA) {
                    return heart.scoutBehavior().unitAreaRadius() + 1;
                }
                UnitKind kind = UnitKind.values()[(index - DATA_UNITS) / VALUES_PER_UNIT];
                return switch ((index - DATA_UNITS) % VALUES_PER_UNIT) {
                    case 0 -> HivemindManager.get(player).count(kind);
                    case 1 -> HiveLevels.get(heart.hiveLevel()).cap(kind);
                    default -> HivemindManager.spawnStatus(player, heart, kind);
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        StorageScroll scroll = new StorageScroll(heart.getStorage(), heart.getStorage().getContainerSize());
        return new HiveMenu(containerId, inventory, heart.getStorage(), scroll, heart.getArmorGear(), heart.getToolGear(),
                heart.furnace().items(), heart.hiveLevel() >= HiveLevels.FURNACE_LEVEL, data, heart);
    }

    // ---- values for the screen ----

    public int level() {
        return data.get(DATA_LEVEL);
    }

    public int health() {
        return data.get(DATA_HEALTH);
    }

    public int maxHealth() {
        return data.get(DATA_MAX_HEALTH);
    }

    public int unitCount(UnitKind kind) {
        return data.get(DATA_UNITS + kind.ordinal() * VALUES_PER_UNIT);
    }

    public int unitCap(UnitKind kind) {
        return data.get(DATA_UNITS + kind.ordinal() * VALUES_PER_UNIT + 1);
    }

    /** What the next spawning interval will do for this kind: one of the STATUS constants. */
    public int unitStatus(UnitKind kind) {
        return data.get(DATA_UNITS + kind.ordinal() * VALUES_PER_UNIT + 2);
    }

    /**
     * True once the server's behaviour settings have reached the client. A new menu's data starts at zero and the
     * real values arrive a moment after the screen opens; reading them before then would show wrong settings.
     */
    public boolean behaviorReady() {
        return data.get(DATA_BEHAVIOR_FLAGS) > 0 && data.get(DATA_UNIT_AREA) > 0
                && data.get(DATA_WORKER_FLAGS) > 0 && data.get(DATA_WORKER_AREA) > 0
                && data.get(DATA_COLLECTOR_RANGE) > 0 && data.get(DATA_SCOUT_FLAGS) > 0
                && data.get(DATA_SCOUT_AREA) > 0;
    }

    /** The scout behaviour checkboxes, packed into one number (see ScoutBehavior). Only valid once ready. */
    public int scoutFlags() {
        return data.get(DATA_SCOUT_FLAGS) - 1;
    }

    /** Quest progress: logs the hive has collected so far (counting up to what the quest asks). */
    public int questLogs() {
        return data.get(DATA_QUEST_LOGS);
    }

    /** Quest progress: mobs the hive's units have killed so far. */
    public int questKills() {
        return data.get(DATA_QUEST_KILLS);
    }

    /** Quest progress: seconds the hive has lasted. */
    public int questSeconds() {
        return data.get(DATA_QUEST_SECONDS);
    }

    /** The furnace's burning fuel left, as a fraction (0 to 1) of what the burning fuel started with. */
    public float furnaceBurn() {
        int duration = data.get(DATA_FURNACE_LIT_DURATION);
        return duration == 0 ? 0.0F : Math.min(1.0F, data.get(DATA_FURNACE_LIT) / (float) duration);
    }

    /** How far through the current item the furnace is, as a fraction (0 to 1). */
    public float furnaceProgress() {
        int total = data.get(DATA_FURNACE_COOK_TOTAL);
        return total == 0 ? 0.0F : Math.min(1.0F, data.get(DATA_FURNACE_COOK) / (float) total);
    }

    public boolean hasFurnace() {
        return hasFurnace;
    }

    @Override
    public StorageScroll storageScroll() {
        return scroll;
    }

    /** Quest progress: chunks the hive's units have explored so far. */
    public int questChunks() {
        return data.get(DATA_QUEST_CHUNKS);
    }

    /** The scouts' own-area radius setting. Only valid once ready. */
    public int scoutAreaRadius() {
        return data.get(DATA_SCOUT_AREA) - 1;
    }

    /** How far past the hive area collectors may reach. Only valid once ready. */
    public int collectorRange() {
        return data.get(DATA_COLLECTOR_RANGE) - 1;
    }

    /** The worker behaviour checkboxes, packed into one number (see WorkerBehavior). Only valid once ready. */
    public int workerFlags() {
        return data.get(DATA_WORKER_FLAGS) - 1;
    }

    /** The workers' own-area radius setting. Only valid once ready. */
    public int workerAreaRadius() {
        return data.get(DATA_WORKER_AREA) - 1;
    }

    /** The soldier behaviour checkboxes, packed into one number (see SoldierBehavior). Only valid once ready. */
    public int behaviorFlags() {
        return data.get(DATA_BEHAVIOR_FLAGS) - 1;
    }

    /** The soldiers' own-area radius setting. Only valid once ready. */
    public int unitAreaRadius() {
        return data.get(DATA_UNIT_AREA) - 1;
    }

    /** Whole seconds until the next spawning interval, rounded up so it never shows 0 before it fires. */
    public int secondsUntilSpawn() {
        return (data.get(DATA_TIMER) + 19) / 20;
    }

    // ---- crafting ----

    @Override
    public void slotsChanged(Container container) {
        updateResult(player.level());
    }

    /** Same as the vanilla crafting table: find a matching recipe and show its result. */
    private void updateResult(Level level) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        CraftingInput input = craftSlots.asCraftInput();
        ItemStack result = ItemStack.EMPTY;
        Optional<RecipeHolder<CraftingRecipe>> match = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        if (match.isPresent()) {
            RecipeHolder<CraftingRecipe> holder = match.get();
            if (resultSlots.setRecipeUsed(level, serverPlayer, holder)) {
                ItemStack assembled = holder.value().assemble(input, level.registryAccess());
                if (assembled.isItemEnabled(level.enabledFeatures())) {
                    result = assembled;
                }
            }
        }
        resultSlots.setItem(0, result);
        setRemoteSlot(resultIndex, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), resultIndex, result));
    }

    // ---- moving items ----

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == resultIndex) {
            stack.getItem().onCraftedBy(stack, player.level(), player);
            if (!this.moveItemStackTo(stack, STORAGE_START, resultIndex, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else if (index >= gridStart) {
            // Crafting grid, armor and tool slots: back to storage.
            if (!this.moveItemStackTo(stack, STORAGE_START, resultIndex, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!(shown(armorStart, toolsEnd) && this.moveItemStackTo(stack, armorStart, toolsEnd, false))
                && !(shown(gridStart, gridEnd) && this.moveItemStackTo(stack, gridStart, gridEnd, false))
                && !(shown(furnaceStart, furnaceEnd) && this.moveItemStackTo(stack, furnaceStart, furnaceEnd, false))) {
            // From storage: gear slots first (each only takes what belongs there), then the crafting grid, then the furnace,
            // but only those the open tab is showing.
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }


    /** True if the slots from start to end are on show, so a shift-click may send items there. */
    private boolean shown(int start, int end) {
        return start < end && this.slots.get(start).isActive();
    }
    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return heart == null || heart.isAlive();
    }

    /** Closing the menu: everything on the cursor or in the crafting grid goes back to the hive, never to the player. */
    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide) {
            ItemStack carried = getCarried();
            if (!carried.isEmpty()) {
                setCarried(ItemStack.EMPTY);
                giveToHive(carried);
            }
            for (int i = 0; i < craftSlots.getContainerSize(); i++) {
                giveToHive(craftSlots.removeItemNoUpdate(i));
            }
            resultSlots.clearContent();
        }
        super.removed(player);
    }

    private void giveToHive(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (storage == null) {
            return;
        }
        ItemStack leftover = storage.addItem(stack);
        if (!leftover.isEmpty() && heart != null) {
            heart.spawnAtLocation(leftover);
        }
    }

    // ---- slots ----

    /** Which slots show depends on the tab: see {@link #visibleGroups}. Slots that are not shown cannot be clicked. */
    private class HiveSlot extends Slot {
        private final int group;

        HiveSlot(Container container, int index, int x, int y) {
            this(container, index, x, y, GROUP_CRAFT);
        }

        HiveSlot(Container container, int index, int x, int y, int group) {
            super(container, index, x, y);
            this.group = group;
        }

        @Override
        public boolean isActive() {
            return (visibleGroups & group) != 0;
        }
    }

    private class HiveResultSlot extends ResultSlot {
        HiveResultSlot(Player player, CraftingContainer craftSlots, Container container, int index, int x, int y) {
            super(player, craftSlots, container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return (visibleGroups & GROUP_CRAFT) != 0;
        }
    }

    /** Holds one armor piece, and only of the right kind: new soldiers wear a copy of it. */
    private class ArmorSlot extends HiveSlot {
        private final EquipmentSlot equipmentSlot;
        private final int position;

        /** @param position index in {@link HiveEquipment#ARMOR_SLOTS}, which picks the empty-slot icon */
        ArmorSlot(Container container, int position, int x, int y, EquipmentSlot equipmentSlot) {
            super(container, position, x, y, GROUP_GEAR);
            this.equipmentSlot = equipmentSlot;
            this.position = position;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return HiveEquipment.isArmorFor(stack, equipmentSlot);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Nullable
        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, ARMOR_ICONS[position]);
        }
    }

    /** The furnace's fuel slot: only things that burn. */
    private class FuelSlot extends HiveSlot {
        FuelSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_FURNACE);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return HiveFurnace.isFuel(stack);
        }
    }

    /** The furnace's output: things come out of it, never go in. */
    private class OutputSlot extends HiveSlot {
        OutputSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_FURNACE);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    /** Holds tools and weapons: new soldiers wield a copy of the one with the highest attack damage. */
    private class ToolSlot extends HiveSlot {
        ToolSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_GEAR);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return HiveEquipment.isToolOrWeapon(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
