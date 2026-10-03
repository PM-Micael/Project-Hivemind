package com.projecthivemind.menu;

import java.util.Optional;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Pair;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveBrewing;
import com.projecthivemind.HiveFurnace;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModMenus;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
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
    private final int brewingStart;
    private final int brewingEnd;
    private final boolean hasBrewing;
    private final int handIndex;
    private final int foodIndex;
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
    public static final int GROUP_BREWING = 16;

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
        return toolsY(storageRows) + 72;
    }

    /** Where the scout's hand slot is: under the row of unit counters, the scout's being the first. */
    public static int scoutHandY(int storageRows) {
        return toolsY(storageRows) + 52;
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
    /** The built-in brewing stand (level 5): fuel and ingredient on top, the three bottles below. It takes the crafting grid's place too. */
    public static final int BREW_FUEL_X = 246;
    public static final int BREW_FUEL_Y = 86;
    public static final int BREW_INGREDIENT_X = 282;
    public static final int BREW_INGREDIENT_Y = 86;
    public static final int BREW_BOTTLE_Y = 136;
    public static final int BREW_BOTTLE_X = 246;
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
    /**
     * The unit whose page is open (see {@link #viewUnit}): the number the player's screen asked for, echoed back so the
     * screen knows the values after it are that unit's, then that unit's kind, behaviour flags and radius or range.
     * All sent plus one, so that 0 means "not arrived yet".
     */
    private static final int DATA_VIEW_SEQ = 4;
    private static final int DATA_VIEW_KIND = 5;
    private static final int DATA_VIEW_FLAGS = 6;
    private static final int DATA_VIEW_RADIUS = 7;
    private static final int VIEW_RADII = 4;
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
    /** After the units: quest progress for coal, raw iron, and the lowest height reached (sent plus 1000, as it can be negative). */
    private static final int DATA_QUEST_COAL = DATA_UNITS + UnitKind.values().length * VALUES_PER_UNIT;
    private static final int DATA_QUEST_IRON = DATA_QUEST_COAL + 1;
    private static final int DATA_QUEST_DEPTH = DATA_QUEST_COAL + 2;
    private static final int DEPTH_OFFSET = 1000;
    private static final int DATA_QUEST_BLAZE = DATA_QUEST_COAL + 3;
    private static final int DATA_QUEST_NETHER = DATA_QUEST_COAL + 4;
    private static final int DATA_BREW_TIME = DATA_QUEST_COAL + 5;
    private static final int DATA_BREW_FUEL = DATA_QUEST_COAL + 6;
    public static final int DATA_COUNT = DATA_BREW_FUEL + 1;

    /** What the next spawning interval will do for a kind of unit. */
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_SPAWNING = 1;

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
    /** Server side: the entity id of the unit whose page is open, and the number the screen asked about it with. */
    private final int[] view;
    private final Player player;
    @Nullable
    private final HiveHeart heart;
    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, GRID_SIZE, GRID_SIZE);
    private final ResultContainer resultSlots = new ResultContainer();

    /** Client-side only: the screen shows the Quests tab, so the slots are hidden. */
    public int visibleGroups = GROUP_STORAGE | GROUP_GEAR | GROUP_CRAFT;

    /** Client constructor: the real contents arrive from the server. */
    public HiveMenu(int containerId, Inventory inventory, int totalStorageSlots, boolean hasFurnace, boolean hasBrewing) {
        this(containerId, inventory, null, new StorageScroll(null, totalStorageSlots),
                new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length), new SimpleContainer(HiveEquipment.TOOL_SLOTS),
                new SimpleContainer(3), new SimpleContainer(5), new SimpleContainer(1), new SimpleContainer(1), hasFurnace, hasBrewing, new SimpleContainerData(DATA_COUNT),
                new int[] {-1, -1}, null);
    }

    private HiveMenu(int containerId, Inventory inventory, @Nullable SimpleContainer storage, StorageScroll scroll, SimpleContainer armor,
                     SimpleContainer tools, SimpleContainer furnace, SimpleContainer brewing, SimpleContainer scoutHand, SimpleContainer foodSlot, boolean hasFurnace, boolean hasBrewing, ContainerData data, int[] view,
                     @Nullable HiveHeart heart) {
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
        this.hasBrewing = hasBrewing;
        this.brewingStart = furnaceEnd;
        this.brewingEnd = brewingStart + (hasBrewing ? 5 : 0);
        this.handIndex = brewingEnd;
        this.foodIndex = handIndex + 1;
        this.storage = storage;
        this.data = data;
        this.view = view;
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
        if (hasBrewing) {
            for (int i = 0; i < HiveBrewing.BOTTLES; i++) {
                this.addSlot(new BottleSlot(brewing, i, BREW_BOTTLE_X + i * 18 + (i == 1 ? 0 : 0), BREW_BOTTLE_Y + (i == 1 ? 8 : 0)));
            }
            this.addSlot(new BrewIngredientSlot(brewing, HiveBrewing.INGREDIENT, BREW_INGREDIENT_X, BREW_INGREDIENT_Y));
            this.addSlot(new BrewFuelSlot(brewing, HiveBrewing.FUEL, BREW_FUEL_X, BREW_FUEL_Y));
        }
        // The scout's hand: under the scout's unit counter. What is put here is what the scout holds.
        this.addSlot(new HiveSlot(scoutHand, 0, STORAGE_X, scoutHandY(scroll.visibleRows()), GROUP_GEAR));
        // The food the hive eats from, under the armor slots: only food goes in.
        this.addSlot(new FoodSlot(foodSlot, 0, ARMOR_X, ARMOR_Y + HiveEquipment.ARMOR_SLOTS.length * 18 + 2));
        this.addDataSlots(data);
        this.addDataSlot(scroll.position());
        this.addDataSlot(scroll.matchCount());
    }

    /** Server constructor: backed by the Heart's real storage, with live stats for the screen. */
    public static HiveMenu create(int containerId, Inventory inventory, HiveHeart heart, ServerPlayer player) {
        int[] view = {-1, -1};
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
                if (index == DATA_VIEW_SEQ) {
                    return view[1] + 1;
                }
                if (index == DATA_VIEW_KIND || index == DATA_VIEW_FLAGS || (index >= DATA_VIEW_RADIUS && index < DATA_VIEW_RADIUS + VIEW_RADII)) {
                    // The page's unit: only the player's own, and only while it is alive.
                    if (HivemindManager.findById(player, view[0]) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                            && player.getUUID().equals(unit.ownerId())) {
                        return (index == DATA_VIEW_KIND ? unit.kind().ordinal() : index == DATA_VIEW_FLAGS ? unit.behaviorFlags()
                                : unit.behaviorRadii()[index - DATA_VIEW_RADIUS]) + 1;
                    }
                    return 0;
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
                if (index == DATA_QUEST_COAL) {
                    return heart.coalProgress();
                }
                if (index == DATA_QUEST_IRON) {
                    return heart.ironProgress();
                }
                if (index == DATA_BREW_TIME) {
                    return heart.brewing().brewTime();
                }
                if (index == DATA_BREW_FUEL) {
                    return heart.brewing().fuel();
                }
                if (index == DATA_QUEST_BLAZE) {
                    return heart.blazeProgress();
                }
                if (index == DATA_QUEST_NETHER) {
                    return heart.netherEntered() ? 1 : 0;
                }
                if (index == DATA_QUEST_DEPTH) {
                    return Math.min(heart.lowestY(), 30000) + DEPTH_OFFSET;
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
                heart.furnace().items(), heart.brewing().items(), heart.scoutHand(), heart.foodSlot(), heart.hiveLevel() >= HiveLevels.FURNACE_LEVEL,
                heart.hiveLevel() >= HiveLevels.BREWING_LEVEL, data, view, heart);
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
     * True once the values of the unit the screen asked about (with this number) have reached the client. A new menu's
     * data starts at zero and the real values arrive a moment after; reading them before then would show wrong settings.
     */
    public boolean viewReady(int seq) {
        return data.get(DATA_VIEW_SEQ) == seq + 1 && data.get(DATA_VIEW_KIND) > 0 && data.get(DATA_VIEW_FLAGS) > 0 && data.get(DATA_VIEW_RADIUS) > 0;
    }

    /** The kind of the unit being viewed, once ready. */
    public UnitKind viewKind() {
        return UnitKind.values()[data.get(DATA_VIEW_KIND) - 1];
    }

    /** The viewed unit's behaviour checkboxes as flags, once ready. */
    public int viewFlags() {
        return data.get(DATA_VIEW_FLAGS) - 1;
    }

    /** One of the viewed unit's radii (or its range), once ready: see HiveUnit#behaviorRadii. */
    public int viewRadius(int index) {
        return data.get(DATA_VIEW_RADIUS + index) - 1;
    }

    /** Server side: which unit's page is open, and the number the screen asked about it with. */
    public void viewUnit(int entityId, int seq) {
        view[0] = entityId;
        view[1] = seq;
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

    /** Ticks left of the brew in progress (0 when none), out of {@link HiveBrewing#BREW_TICKS}. */
    public int brewTime() {
        return data.get(DATA_BREW_TIME);
    }

    /** Brews left in the blaze powder that is burning. */
    public int brewFuel() {
        return data.get(DATA_BREW_FUEL);
    }

    public boolean hasBrewing() {
        return hasBrewing;
    }

    public boolean hasFurnace() {
        return hasFurnace;
    }

    /** Before the menu sends what changed: work out again what the storage search shows, as the storage may have changed. */
    @Override
    public void broadcastChanges() {
        if (heart != null) {
            scroll.refresh();
        }
        super.broadcastChanges();
    }

    /** Put the hive's storage in order right away (the Heart also does it now and then, for what comes in while no one clicks). */
    public void sortStorageNow() {
        if (storage != null) {
            com.projecthivemind.entity.StorageSorter.sort(storage);
        }
    }

    @Override
    public StorageScroll storageScroll() {
        return scroll;
    }

    public int questCoal() {
        return data.get(DATA_QUEST_COAL);
    }

    public int questIron() {
        return data.get(DATA_QUEST_IRON);
    }

    public int questBlaze() {
        return data.get(DATA_QUEST_BLAZE);
    }

    public boolean questNether() {
        return data.get(DATA_QUEST_NETHER) != 0;
    }

    /** The lowest block height a unit has reached, or a very large number if none counted yet. */
    public int questLowestY() {
        return data.get(DATA_QUEST_DEPTH) - DEPTH_OFFSET;
    }

    /** Quest progress: chunks the hive's units have explored so far. */
    public int questChunks() {
        return data.get(DATA_QUEST_CHUNKS);
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
                && !(shown(furnaceStart, furnaceEnd) && this.moveItemStackTo(stack, furnaceStart, furnaceEnd, false))
                && !(shown(brewingStart, brewingEnd) && this.moveItemStackTo(stack, brewingStart, brewingEnd, false))
                && !(shown(foodIndex, foodIndex + 1) && this.moveItemStackTo(stack, foodIndex, foodIndex + 1, false))) {
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

        /** With a search on, the storage slots after the last match are not real slots: nothing can be put in them. */
        @Override
        public boolean mayPlace(ItemStack stack) {
            return super.mayPlace(stack) && (group != GROUP_STORAGE || scroll.isBacked(getContainerSlot()));
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

    /** The hive's food slot: only food goes in. */
    private class FoodSlot extends HiveSlot {
        FoodSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_GEAR);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getFoodProperties(player) != null;
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

    /** A brewing stand's bottle slot: an empty bottle or a potion, one at a time. */
    private class BottleSlot extends HiveSlot {
        BottleSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_BREWING);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !hasItem() && (player.level().potionBrewing().isInput(stack) || stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE));
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** The brewing stand's ingredient slot: only what some recipe uses. */
    private class BrewIngredientSlot extends HiveSlot {
        BrewIngredientSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_BREWING);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return player.level().potionBrewing().isIngredient(stack);
        }
    }

    /** The brewing stand's fuel slot: blaze powder. */
    private class BrewFuelSlot extends HiveSlot {
        BrewFuelSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_BREWING);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(net.minecraft.world.item.Items.BLAZE_POWDER);
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
