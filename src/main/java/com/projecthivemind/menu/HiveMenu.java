package com.projecthivemind.menu;

import java.util.Optional;

import javax.annotation.Nullable;

import com.mojang.datafixers.util.Pair;
import com.projecthivemind.HiveEnchanting;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveBrewing;
import com.projecthivemind.HiveFurnace;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModMenus;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.network.chat.Component;
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
import net.minecraft.world.inventory.DataSlot;
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
    private final int enchantStart;
    private final int enchantEnd;
    private final int jukeboxStart;
    private final int jukeboxEnd;
    private final int cartographyStart;
    private final int cartographyEnd;
    private final int anvilStart;
    private final int anvilEnd;
    private final Slot trashSlot;
    /** Where the scouts' armor slots and the window onto the storage beside them are among the slots (set as they are added). */
    private int scoutArmorStart;
    private int scoutStorageStart;
    private int scoutStorageEnd;
    private int scoutHotbarStart;
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
    /** The enchanting station (once an enchanting table has been consumed). */
    public static final int GROUP_ENCHANT = 32;
    /** The jukebox slot (once a jukebox has been consumed). */
    public static final int GROUP_JUKEBOX = 64;
    /** The cartography table (once one has been consumed): the map, the material and the result. */
    public static final int GROUP_CARTOGRAPHY = 128;
    /** The anvil (once one has been consumed): the item, what it is combined with, and the result. */
    public static final int GROUP_ANVIL = 256;
    /** The trash (always there): a station of its own. */
    public static final int GROUP_TRASH = 512;
    /** The armor every scout wears (leather only): shown on the scouts' page of the Units tab. */
    public static final int GROUP_SCOUT_ARMOR = 1024;
    /** Where the scout armor slots are, in a column on the scouts' page. */
    public static final int SCOUT_ARMOR_X = 64;
    public static final int SCOUT_ARMOR_Y = 152;
    /** A three-row window onto the hive storage beside the scout armor (group {@link #GROUP_SCOUT_STORAGE}): where it is, and how many slots. */
    public static final int GROUP_SCOUT_STORAGE = 2048;
    public static final int SCOUT_INV_COLUMNS = 9;
    public static final int SCOUT_INV_SLOTS = 27;
    public static final int SCOUT_INV_X = 140;
    public static final int SCOUT_INV_Y = 152;
    /** The scouts' hotbar, a row of nine under the storage window: its first slot is the item the scouts hold (the hive page's hand slot). */
    public static final int SCOUT_HOTBAR_X = SCOUT_INV_X;
    public static final int SCOUT_HOTBAR_Y = SCOUT_INV_Y + 3 * 18 + 16;
    /** The groups of slots that are workstations, in the screen's scrolling list: their slots are moved about, and hidden when out of view. */
    public static final int STATION_GROUPS = GROUP_CRAFT | GROUP_FURNACE | GROUP_BREWING | GROUP_ENCHANT | GROUP_JUKEBOX | GROUP_CARTOGRAPHY | GROUP_ANVIL | GROUP_TRASH;
    /** The trash may hold a stack of anything stackable this many times as large as usual: a whole storage stack, and more. */
    private static final int TRASH_STACK_MULTIPLIER = 1000;

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
    /** The built-in furnace (from the Evolve tab): input, fuel and output. It takes the crafting grid's place when its tab is open. */
    public static final int FURNACE_INPUT_X = 246;
    public static final int FURNACE_INPUT_Y = 86;
    public static final int FURNACE_FUEL_X = 246;
    public static final int FURNACE_FUEL_Y = 122;
    public static final int FURNACE_OUTPUT_X = 300;
    public static final int FURNACE_OUTPUT_Y = 104;
    /** The built-in brewing stand (from the Evolve tab): fuel and ingredient on top, the three bottles below. It takes the crafting grid's place too. */
    public static final int BREW_FUEL_X = 246;
    public static final int BREW_FUEL_Y = 86;
    public static final int BREW_INGREDIENT_X = 282;
    public static final int BREW_INGREDIENT_Y = 86;
    public static final int BREW_BOTTLE_Y = 136;
    public static final int BREW_BOTTLE_X = 246;
    /** The enchanting station: the item slot, with the lapis slot under it; the three options sit to the right (see the screen). */
    public static final int ENCHANT_ITEM_X = 248;
    public static final int ENCHANT_ITEM_Y = 96;
    /** The jukebox slot sits in the middle of where the crafting grid is. */
    public static final int JUKEBOX_X = 264;
    public static final int JUKEBOX_Y = 100;

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
    /** After the units: quest progress for coal, iron ingots, and the lowest height reached (sent plus 1000, as it can be negative). */
    private static final int DATA_QUEST_COAL = DATA_UNITS + UnitKind.values().length * VALUES_PER_UNIT;
    private static final int DATA_QUEST_IRON = DATA_QUEST_COAL + 1;
    private static final int DATA_QUEST_DEPTH = DATA_QUEST_COAL + 2;
    private static final int DEPTH_OFFSET = 1000;
    private static final int DATA_QUEST_BLAZE = DATA_QUEST_COAL + 3;
    private static final int DATA_QUEST_NETHER = DATA_QUEST_COAL + 4;
    private static final int DATA_BREW_TIME = DATA_QUEST_COAL + 5;
    private static final int DATA_BREW_FUEL = DATA_QUEST_COAL + 6;
    /** The evolution tasks done, as a mask (see EvolveTask). */
    private static final int DATA_EVOLVE = DATA_BREW_FUEL + 1;
    /** The tasks the hive can do now (their item is in its storage), as a mask. */
    private static final int DATA_EVOLVE_READY = DATA_EVOLVE + 1;
    /** Whether the Ender Dragon has been defeated, for the quest. */
    private static final int DATA_QUEST_DRAGON = DATA_EVOLVE_READY + 1;
    /** The top half of the two masks above: a menu value is sent as 16 bits, and there are more tasks than that. */
    private static final int DATA_EVOLVE_HIGH = DATA_QUEST_DRAGON + 1;
    private static final int DATA_EVOLVE_READY_HIGH = DATA_EVOLVE_HIGH + 1;
    /** Ticks until the Heart's totem effect is ready again (0 when it is ready). */
    private static final int DATA_TOTEM = DATA_EVOLVE_READY_HIGH + 1;
    /** The bits 32 to 47 of the two masks (there are more than 32 tasks now). */
    private static final int DATA_EVOLVE_TOP = DATA_TOTEM + 1;
    private static final int DATA_EVOLVE_READY_TOP = DATA_EVOLVE_TOP + 1;
    public static final int DATA_COUNT = DATA_EVOLVE_READY_TOP + 1;

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
    /** The window onto the storage on the scouts' page: three rows, with a search of its own, for putting armor on the scouts. */
    private final StorageScroll scoutScroll;
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
    /** The enchanting station's two slots (item, lapis). What is left in them goes back to the hive when the menu closes. */
    private final SimpleContainer enchantSlots = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            HiveMenu.this.slotsChanged(this);
        }
    };
    /** The cartography table's inputs (map, material) and its result. What is left in the inputs goes back to the hive when the menu closes. */
    private final SimpleContainer cartographySlots = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            HiveMenu.this.slotsChanged(this);
        }
    };
    private final SimpleContainer cartographyResult = new SimpleContainer(1);
    /** The anvil's inputs and result, the levels the result costs (sent to the screen), and the game's own anvil working it out (server side only). */
    private final SimpleContainer anvilSlots = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            HiveMenu.this.slotsChanged(this);
        }
    };
    private final SimpleContainer anvilResult = new SimpleContainer(1);
    private final DataSlot anvilCost = DataSlot.standalone();
    @Nullable
    private AnvilEngine anvilEngine;

    /** Client-side only: the screen shows the Quests tab, so the slots are hidden. */
    public int visibleGroups = GROUP_STORAGE | GROUP_GEAR | GROUP_CRAFT;

    /** Client constructor: the real contents arrive from the server. */
    public HiveMenu(int containerId, Inventory inventory, int totalStorageSlots) {
        this(containerId, inventory, null, new StorageScroll(null, totalStorageSlots), new StorageScroll(null, totalStorageSlots, SCOUT_INV_SLOTS),
                new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length), new SimpleContainer(HiveEquipment.TOOL_SLOTS),
                new SimpleContainer(3), new SimpleContainer(5), new SimpleContainer(HiveHeart.SCOUT_HOTBAR_SLOTS), new SimpleContainer(1), new SimpleContainer(1), new com.projecthivemind.entity.HiveStorage(1, () -> TRASH_STACK_MULTIPLIER), new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length), true, true, new SimpleContainerData(DATA_COUNT),
                new int[] {-1, -1}, null);
    }

    private HiveMenu(int containerId, Inventory inventory, @Nullable SimpleContainer storage, StorageScroll scroll, StorageScroll scoutScroll, SimpleContainer armor,
                     SimpleContainer tools, SimpleContainer furnace, SimpleContainer brewing, SimpleContainer scoutHand, SimpleContainer foodSlot, SimpleContainer jukeboxSlot, SimpleContainer trash, SimpleContainer scoutArmor, boolean hasFurnace, boolean hasBrewing, ContainerData data, int[] view,
                     @Nullable HiveHeart heart) {
        super(ModMenus.HIVE.get(), containerId);
        this.scroll = scroll;
        this.scoutScroll = scoutScroll;
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
        this.enchantStart = foodIndex + 1;
        this.enchantEnd = enchantStart + 2;
        this.jukeboxStart = enchantEnd;
        this.jukeboxEnd = jukeboxStart + 1;
        this.cartographyStart = jukeboxEnd;
        this.cartographyEnd = cartographyStart + 3;
        this.anvilStart = cartographyEnd;
        this.anvilEnd = anvilStart + 3;
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
                this.addSlot(new GridSlot(craftSlots, col + row * GRID_SIZE, GRID_X + col * 18, GRID_Y + row * 18));
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
        // The enchanting station: the item to enchant, and the lapis lazuli it costs.
        this.addSlot(new EnchantItemSlot(enchantSlots, 0, ENCHANT_ITEM_X, ENCHANT_ITEM_Y));
        this.addSlot(new EnchantLapisSlot(enchantSlots, 1, ENCHANT_ITEM_X, ENCHANT_ITEM_Y + 24));
        // The jukebox: the music disc that is playing.
        this.addSlot(new JukeboxSlot(jukeboxSlot, 0, JUKEBOX_X, JUKEBOX_Y));
        // The cartography table: the map, what is done to it (paper, glass pane or an empty map), and the result.
        this.addSlot(new CartographyInputSlot(cartographySlots, 0, FURNACE_INPUT_X, FURNACE_INPUT_Y, true));
        this.addSlot(new CartographyInputSlot(cartographySlots, 1, FURNACE_FUEL_X, FURNACE_FUEL_Y, false));
        this.addSlot(new CartographyResultSlot(cartographyResult, 0, FURNACE_OUTPUT_X, FURNACE_OUTPUT_Y));
        // The anvil: the item, what it is combined with (another item, or material to repair it), and the result.
        this.addSlot(new AnvilInputSlot(anvilSlots, 0, FURNACE_INPUT_X, FURNACE_INPUT_Y));
        this.addSlot(new AnvilInputSlot(anvilSlots, 1, FURNACE_FUEL_X, FURNACE_FUEL_Y));
        this.addSlot(new AnvilResultSlot(anvilResult, 0, FURNACE_OUTPUT_X, FURNACE_OUTPUT_Y));
        // The trash: its own station. What is put in it stays until another item is put over it (the two swap), as in Terraria.
        this.trashSlot = new TrashSlot(trash, 0, GRID_X, GRID_Y);
        this.addSlot(trashSlot);
        // The scouts' armor, shared by every scout: leather only, on the scouts' page.
        this.scoutArmorStart = this.slots.size();
        for (int i = 0; i < HiveEquipment.ARMOR_SLOTS.length; i++) {
            this.addSlot(new ArmorSlot(scoutArmor, i, SCOUT_ARMOR_X, SCOUT_ARMOR_Y + i * 18, HiveEquipment.ARMOR_SLOTS[i], GROUP_SCOUT_ARMOR));
        }
        // The scouts' page also shows the hive's storage in three rows, so armor can be put on the scouts from it.
        this.scoutStorageStart = this.slots.size();
        for (int row = 0; row < scoutScroll.visibleRows(); row++) {
            for (int col = 0; col < SCOUT_INV_COLUMNS; col++) {
                int index = col + row * SCOUT_INV_COLUMNS;
                if (index < Math.min(scoutScroll.total(), SCOUT_INV_SLOTS)) {
                    this.addSlot(new HiveSlot(scoutScroll.view(), index, SCOUT_INV_X + col * 18, SCOUT_INV_Y + row * 18, GROUP_SCOUT_STORAGE));
                }
            }
        }
        this.scoutStorageEnd = this.slots.size();
        this.scoutHotbarStart = this.slots.size();
        for (int i = 0; i < HiveHeart.SCOUT_HOTBAR_SLOTS; i++) {
            this.addSlot(new HiveSlot(scoutHand, i, SCOUT_HOTBAR_X + i * 18, SCOUT_HOTBAR_Y, GROUP_SCOUT_ARMOR));
        }
        this.addDataSlots(data);
        this.addDataSlot(scroll.position());
        this.addDataSlot(scroll.matchCount());
        this.addDataSlot(scoutScroll.position());
        this.addDataSlot(scoutScroll.matchCount());
        this.addDataSlot(anvilCost);
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
                if (index == DATA_QUEST_DRAGON) {
                    return heart.dragonDefeated() ? 1 : 0;
                }
                if (index == DATA_EVOLVE_READY) {
                    return (int) (heart.evolveReadyMask() & 0xFFFFL);
                }
                if (index == DATA_EVOLVE_READY_HIGH) {
                    return (int) ((heart.evolveReadyMask() >>> 16) & 0xFFFFL);
                }
                if (index == DATA_EVOLVE) {
                    return (int) (heart.evolveMask() & 0xFFFFL);
                }
                if (index == DATA_EVOLVE_HIGH) {
                    return (int) ((heart.evolveMask() >>> 16) & 0xFFFFL);
                }
                if (index == DATA_EVOLVE_TOP) {
                    return (int) ((heart.evolveMask() >>> 32) & 0xFFFFL);
                }
                if (index == DATA_EVOLVE_READY_TOP) {
                    return (int) ((heart.evolveReadyMask() >>> 32) & 0xFFFFL);
                }
                if (index == DATA_TOTEM) {
                    return heart.totemCooldown();
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
        StorageScroll scoutScroll = new StorageScroll(heart.getStorage(), heart.getStorage().getContainerSize(), SCOUT_INV_SLOTS);
        return new HiveMenu(containerId, inventory, heart.getStorage(), scroll, scoutScroll, heart.getArmorGear(), heart.getToolGear(),
                heart.furnace().items(), heart.brewing().items(), heart.scoutHand(), heart.foodSlot(), heart.jukeboxSlot(), heart.trash(), heart.getScoutArmor(), true,
                true, data, view, heart);
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

    /** The evolution tasks done, as a mask of EvolveTask bits. */
    public long evolveMask() {
        return (data.get(DATA_EVOLVE) & 0xFFFFL) | ((data.get(DATA_EVOLVE_HIGH) & 0xFFFFL) << 16) | ((data.get(DATA_EVOLVE_TOP) & 0xFFFFL) << 32);
    }

    /** The tasks the hive can do right now, as a mask of EvolveTask bits: those still to do whose item is in its storage. */
    public long evolveReady() {
        return (data.get(DATA_EVOLVE_READY) & 0xFFFFL) | ((data.get(DATA_EVOLVE_READY_HIGH) & 0xFFFFL) << 16) | ((data.get(DATA_EVOLVE_READY_TOP) & 0xFFFFL) << 32);
    }

    /**
     * Server side: the player clicked a task on the Evolve tab. If it is still to do and its item is in the hive's storage, one of the item
     * is taken from there and the task is done.
     */
    public void consumeEvolveTask(int taskOrdinal) {
        com.projecthivemind.EvolveTask[] tasks = com.projecthivemind.EvolveTask.values();
        if (heart == null || taskOrdinal < 0 || taskOrdinal >= tasks.length) {
            return;
        }
        com.projecthivemind.EvolveTask task = tasks[taskOrdinal];
        int slot = task.doneIn(heart.evolveMask()) ? -1 : heart.storageHasFor(task);
        if (slot < 0) {
            return;
        }
        heart.getStorage().removeItem(slot, 1);
        heart.completeEvolve(task);
        broadcastChanges();
    }

    /** True once the hive has consumed a brewing stand: its brewing stand can be used. (The slots are always there; they only show when this is so.) */
    public boolean hasBrewing() {
        return com.projecthivemind.EvolveTask.BREWING_STAND.doneIn(evolveMask());
    }

    /** True once the hive has consumed a furnace. */
    public boolean hasFurnace() {
        return com.projecthivemind.EvolveTask.FURNACE.doneIn(evolveMask());
    }

    /** True once the hive has consumed a crafting table: its 3x3 crafting grid can be used. */
    public boolean hasCrafting() {
        return com.projecthivemind.EvolveTask.CRAFTING_TABLE.doneIn(evolveMask());
    }

    /** Whether the workstation a group of slots belongs to has been unlocked (the other groups are always there). */
    private boolean unlocked(int group) {
        return switch (group) {
            case GROUP_CRAFT -> true;
            case GROUP_FURNACE -> hasFurnace();
            case GROUP_BREWING -> hasBrewing();
            case GROUP_ENCHANT -> hasEnchanting();
            case GROUP_JUKEBOX -> hasJukebox();
            case GROUP_CARTOGRAPHY -> hasCartography();
            case GROUP_ANVIL -> hasAnvil();
            default -> true;
        };
    }

    /** Before the menu sends what changed: work out again what the storage search shows, as the storage may have changed. */
    @Override
    public void broadcastChanges() {
        if (heart != null) {
            scroll.refresh();
            scoutScroll.refresh();
        }
        super.broadcastChanges();
    }

    /** Put the hive's storage in order right away (the Heart also does it now and then, for what comes in while no one clicks). */
    public void sortStorageNow() {
        if (storage != null) {
            com.projecthivemind.entity.StorageSorter.sort(storage);
        }
    }

    public StorageScroll scoutScroll() {
        return scoutScroll;
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

    public boolean questDragon() {
        return data.get(DATA_QUEST_DRAGON) != 0;
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







    /** Ticks until the Heart's totem effect is ready again; 0 when it is ready (or the task is not done). */
    public int totemCooldownTicks() {
        return data.get(DATA_TOTEM);
    }

    /** Whole seconds until the next spawning interval, rounded up so it never shows 0 before it fires. */
    public int secondsUntilSpawn() {
        return (data.get(DATA_TIMER) + 19) / 20;
    }

    // ---- enchanting ----

    /** A slot of a workstation: the screen moves it up and down the scrolling list (see {@link #layoutStation}). */
    private interface StationSlot {
        int stationGroup();

        void place(int dy, boolean clipped);
    }

    /**
     * Client side: put a workstation's slots {@code dy} pixels from where they were made, and hide those that are not wholly between the top and the
     * bottom (panel coordinates) of the list's window.
     */
    public void layoutStation(int group, int dy, int viewTop, int viewBottom) {
        for (Slot slot : this.slots) {
            if (slot instanceof StationSlot station && station.stationGroup() == group) {
                station.place(dy, false);
                station.place(dy, slot.y < viewTop || slot.y + 16 > viewBottom);
            }
        }
    }

    /** The food slot, so the screen can show what goes in it when it is empty. */
    public Slot foodSlot() {
        return this.slots.get(foodIndex);
    }

    /** Delete what is in the trash. */
    public void clearTrash() {
        trashSlot.container.clearContent();
        trashSlot.setChanged();
    }

    /** The trash slot, so the screen can draw it. */
    public Slot trashSlot() {
        return trashSlot;
    }

    /** True once the hive has consumed an anvil: it can repair, combine and rename items, paying with the hivemind's levels. */
    public boolean hasAnvil() {
        return com.projecthivemind.EvolveTask.ANVIL.doneIn(evolveMask());
    }

    /** The levels the anvil's result costs (0 for none). */
    public int anvilCost() {
        return anvilCost.get();
    }

    /** True once the hive has consumed a cartography table: it can copy, zoom and lock maps. */
    public boolean hasCartography() {
        return com.projecthivemind.EvolveTask.CARTOGRAPHY_TABLE.doneIn(evolveMask());
    }

    /** True once the hive has consumed a jukebox: it can play music discs. */
    public boolean hasJukebox() {
        return com.projecthivemind.EvolveTask.JUKEBOX.doneIn(evolveMask());
    }

    /** True once the hive has consumed an enchanting table: it can enchant. */
    public boolean hasEnchanting() {
        return com.projecthivemind.EvolveTask.ENCHANTING_TABLE.doneIn(evolveMask());
    }

    /** How many lapis lazuli are in the lapis slot of the enchanting station. */
    public int enchantLapis() {
        ItemStack stack = enchantSlots.getItem(1);
        return stack.isEmpty() ? 0 : stack.getCount();
    }

    /** The item in the enchanting station's item slot (empty if none), for the screen to say what each enchantment would do to it. */
    public ItemStack enchantItem() {
        return enchantSlots.getItem(0);
    }

    private static net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment> enchantmentById(net.minecraft.core.RegistryAccess registries, String text) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(text);
        if (id == null) {
            return null;
        }
        return registries.registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, id)).orElse(null);
    }

    /**
     * Server side: the player clicked an enchantment on the Evolve tab. If it is not available yet and an enchanted book with it is in the hive's
     * storage, one of the book is taken from there and the enchantment becomes available in the enchanting station.
     */
    public void consumeEnchantBook(String idText) {
        if (heart == null || !hasEnchanting() || !(player instanceof net.minecraft.server.level.ServerPlayer owner)) {
            return;
        }
        net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment> holder = enchantmentById(player.level().registryAccess(), idText);
        if (holder == null || heart.unlockedEnchants().contains(holder.key().location())) {
            return;
        }
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK) && stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
                    net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).getLevel(holder) > 0) {
                heart.getStorage().removeItem(i, 1);
                heart.unlockEnchant(holder.key().location());
                HivemindManager.sendEnchants(owner, heart);
                broadcastChanges();
                return;
            }
        }
        owner.displayClientMessage(Component.translatable("message.projecthivemind.no_enchant_book"), true);
    }

    /**
     * Server side: the player picked an available enchantment for the item in the station. It goes on at its highest level, and costs what
     * {@link HiveEnchanting} says in lapis lazuli (from the lapis slot) and levels (from the hivemind's own experience bar).
     */
    public void applyEnchant(String idText) {
        if (heart == null || !hasEnchanting() || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment> holder = enchantmentById(level.registryAccess(), idText);
        if (holder == null || !heart.unlockedEnchants().contains(holder.key().location())) {
            return;
        }
        ItemStack stack = enchantSlots.getItem(0);
        if (HiveEnchanting.problem(stack, holder) != null) {
            return;
        }
        int lapisCost = HiveEnchanting.lapisCost(holder);
        int levelCost = HiveEnchanting.levelCost(holder);
        boolean free = player.hasInfiniteMaterials();
        ItemStack lapis = enchantSlots.getItem(1);
        if (!free && (lapis.isEmpty() || lapis.getCount() < lapisCost || player.experienceLevel < levelCost)) {
            return;
        }
        java.util.List<net.minecraft.world.item.enchantment.EnchantmentInstance> list =
                java.util.List.of(new net.minecraft.world.item.enchantment.EnchantmentInstance(holder, HiveEnchanting.levelOf(holder)));
        player.onEnchantmentPerformed(stack, levelCost);
        ItemStack enchanted = stack.getItem().applyEnchantments(stack, list);
        enchantSlots.setItem(0, enchanted);
        net.neoforged.neoforge.common.CommonHooks.onPlayerEnchantItem(player, enchanted, list);
        if (!free) {
            lapis.consume(lapisCost, player);
            if (lapis.isEmpty()) {
                enchantSlots.setItem(1, ItemStack.EMPTY);
            }
        }
        enchantSlots.setChanged();
        slotsChanged(enchantSlots);
        level.playSound(null, heart.blockPosition(), net.minecraft.sounds.SoundEvents.ENCHANTMENT_TABLE_USE, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F,
                level.random.nextFloat() * 0.1F + 0.9F);
    }

    // ---- crafting ----

    @Override
    public void slotsChanged(Container container) {
        if (container == anvilSlots) {
            updateAnvil();
        } else if (container == cartographySlots) {
            updateCartography();
        } else if (container == enchantSlots) {
            // (Nothing to work out: what the station does is chosen from its list.)
        } else {
            updateResult(player.level());
        }
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

    /**
     * The cartography table, as in the game: a map with paper makes a bigger-scale copy of it (up to the largest scale), with a glass pane it is
     * locked, and with an empty map it is copied (two come out). The inputs are used up when the result is taken.
     */
    private void updateCartography() {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack map = cartographySlots.getItem(0);
        ItemStack material = cartographySlots.getItem(1);
        ItemStack result = ItemStack.EMPTY;
        net.minecraft.world.level.saveddata.maps.MapItemSavedData data = map.is(net.minecraft.world.item.Items.FILLED_MAP)
                ? net.minecraft.world.item.MapItem.getSavedData(map, level) : null;
        if (data != null) {
            if (material.is(net.minecraft.world.item.Items.PAPER) && !data.locked && data.scale < 4) {
                result = map.copyWithCount(1);
                result.set(net.minecraft.core.component.DataComponents.MAP_POST_PROCESSING, net.minecraft.world.item.component.MapPostProcessing.SCALE);
            } else if (material.is(net.minecraft.world.item.Items.GLASS_PANE) && !data.locked) {
                result = map.copyWithCount(1);
                result.set(net.minecraft.core.component.DataComponents.MAP_POST_PROCESSING, net.minecraft.world.item.component.MapPostProcessing.LOCK);
            } else if (material.is(net.minecraft.world.item.Items.MAP)) {
                result = map.copyWithCount(2);
            }
        }
        cartographyResult.setItem(0, result);
        int index = cartographyStart + 2;
        setRemoteSlot(index, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), index, result));
    }

    /**
     * The anvil, worked out by the game's own anvil (kept out of sight, with the two items put in it): repairing with material or with a second
     * item, combining enchantments, and what it costs in levels, which are the hivemind's own. The result shows when the player has the levels.
     */
    private void updateAnvil() {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (anvilEngine == null) {
            anvilEngine = new AnvilEngine(serverPlayer.getInventory());
        }
        anvilEngine.load(anvilSlots.getItem(0), anvilSlots.getItem(1));
        ItemStack result = anvilEngine.result().copy();
        anvilCost.set(result.isEmpty() ? 0 : anvilEngine.cost());
        anvilResult.setItem(0, result);
        int index = anvilStart + 2;
        setRemoteSlot(index, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), index, result));
    }

    /** The game's anvil menu used as the working: this class only opens up what it needs of it. */
    private static final class AnvilEngine extends net.minecraft.world.inventory.AnvilMenu {
        AnvilEngine(Inventory inventory) {
            super(-1, inventory);
        }

        void load(ItemStack first, ItemStack second) {
            inputSlots.setItem(0, first.copy());
            inputSlots.setItem(1, second.copy());
            createResult();
        }

        ItemStack result() {
            return resultSlots.getItem(0);
        }

        int cost() {
            return getCost();
        }

        ItemStack input(int index) {
            return inputSlots.getItem(index);
        }

        boolean canTake(Player taker) {
            return mayPickup(taker, true);
        }

        void take(Player taker, ItemStack stack) {
            onTake(taker, stack);
        }
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

        if (index == anvilStart + 2 && (stack.isEmpty() || !slot.mayPickup(player))) {
            return ItemStack.EMPTY;
        }
        if (index == cartographyStart + 2) {
            // A map made at the cartography table is finished as it comes out, whichever way it is taken.
            stack.getItem().onCraftedBy(stack, player.level(), player);
        }
        if (index == resultIndex) {
            stack.getItem().onCraftedBy(stack, player.level(), player);
            if (!this.moveItemStackTo(stack, STORAGE_START, resultIndex, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else if (index >= scoutStorageStart && index < scoutStorageEnd) {
            // From the storage window on the scouts' page: onto the scouts (the armor slots take only leather armor). Not back into the storage, which is
            // what the window is a view of.
            if (!this.moveItemStackTo(stack, scoutArmorStart, scoutArmorStart + HiveEquipment.ARMOR_SLOTS.length, false)
                    && !this.moveItemStackTo(stack, scoutHotbarStart, scoutHotbarStart + HiveHeart.SCOUT_HOTBAR_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
        } else if ((index >= scoutArmorStart && index < scoutArmorStart + HiveEquipment.ARMOR_SLOTS.length)
                || (index >= scoutHotbarStart && index < scoutHotbarStart + HiveHeart.SCOUT_HOTBAR_SLOTS)) {
            // From the scouts' armor or hotbar: into the hive's storage itself (wherever there is room in it, not only in the window onto it).
            if (storage == null) {
                return ItemStack.EMPTY;
            }
            ItemStack rest = storage.addItem(stack.copy());
            stack.setCount(rest.getCount());
        } else if (index >= gridStart) {
            // Crafting grid, armor and tool slots: back to storage.
            if (!this.moveItemStackTo(stack, STORAGE_START, resultIndex, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!(shown(armorStart, toolsEnd) && this.moveItemStackTo(stack, armorStart, toolsEnd, false))
                && !(shown(gridStart, gridEnd) && this.moveItemStackTo(stack, gridStart, gridEnd, false))
                && !(shown(furnaceStart, furnaceEnd) && this.moveItemStackTo(stack, furnaceStart, furnaceEnd, false))
                && !(shown(brewingStart, brewingEnd) && this.moveItemStackTo(stack, brewingStart, brewingEnd, false))
                && !(shown(enchantStart, enchantEnd) && this.moveItemStackTo(stack, enchantStart, enchantEnd, false))
                && !(shown(jukeboxStart, jukeboxEnd) && this.moveItemStackTo(stack, jukeboxStart, jukeboxEnd, false))
                && !(shown(cartographyStart, cartographyStart + 2) && this.moveItemStackTo(stack, cartographyStart, cartographyStart + 2, false))
                && !(shown(anvilStart, anvilStart + 2) && this.moveItemStackTo(stack, anvilStart, anvilStart + 2, false))
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
        Slot first = start < end ? this.slots.get(start) : null;
        return first != null && (first instanceof HiveSlot hive ? hive.routed() : first.isActive());
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
            for (int i = 0; i < enchantSlots.getContainerSize(); i++) {
                giveToHive(enchantSlots.removeItemNoUpdate(i));
            }
            for (int i = 0; i < cartographySlots.getContainerSize(); i++) {
                giveToHive(cartographySlots.removeItemNoUpdate(i));
            }
            for (int i = 0; i < anvilSlots.getContainerSize(); i++) {
                giveToHive(anvilSlots.removeItemNoUpdate(i));
            }
            anvilResult.clearContent();
            cartographyResult.clearContent();
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
    private class HiveSlot extends Slot implements StationSlot {
        private final int group;
        /** Where the slot sits when its station is at the top of the scrolling list, and whether the screen has scrolled it out of view. */
        private final int baseY;
        private boolean clipped;

        HiveSlot(Container container, int index, int x, int y) {
            this(container, index, x, y, GROUP_CRAFT);
        }

        HiveSlot(Container container, int index, int x, int y, int group) {
            super(container, index, x, y);
            this.group = group;
            this.baseY = y;
        }

        @Override
        public int stationGroup() {
            return group;
        }

        @Override
        public void place(int dy, boolean clipped) {
            this.y = baseY + dy;
            this.clipped = clipped;
        }

        /** Whether shift-click may send items here: the slot's group is the one chosen for that (the server's idea of it, see {@link #visibleGroups}). */
        boolean routed() {
            return (visibleGroups & group) != 0 && unlocked(group);
        }

        /**
         * Shown (and clickable) on the screen: the base and gear slots with their tab; a workstation's slots while it is unlocked, the Hive tab is up, and
         * the scrolling list has it in view.
         */
        @Override
        public boolean isActive() {
            if ((STATION_GROUPS & group) != 0) {
                return (visibleGroups & GROUP_STORAGE) != 0 && unlocked(group) && !clipped;
            }
            return routed();
        }

        /** In the hive's storage a stack of anything stackable holds as many full stacks as the evolution tasks give. */
        @Override
        public int getMaxStackSize(ItemStack stack) {
            int base = super.getMaxStackSize(stack);
            return (group == GROUP_STORAGE || group == GROUP_SCOUT_STORAGE) && stack.getMaxStackSize() > 1 ? stack.getMaxStackSize() * com.projecthivemind.EvolveTask.stackMultiplier(evolveMask()) : base;
        }

        /** With a search on, the storage slots after the last match are not real slots: nothing can be put in them. */
        @Override
        public boolean mayPlace(ItemStack stack) {
            return super.mayPlace(stack) && (group != GROUP_STORAGE || scroll.isBacked(getContainerSlot()))
                    && (group != GROUP_SCOUT_STORAGE || scoutScroll.isBacked(getContainerSlot()));
        }
    }

    /**
     * A slot of the crafting grid. Before the hive has consumed a crafting table only the top left 2x2 of it is there, like the player's own
     * crafting grid (a recipe that fits in two by two fits in those four); the rest comes with the crafting table.
     */
    private class GridSlot extends HiveSlot {
        private final boolean small;

        GridSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_CRAFT);
            this.small = index % GRID_SIZE < 2 && index / GRID_SIZE < 2;
        }

        @Override
        boolean routed() {
            return (visibleGroups & GROUP_CRAFT) != 0 && (small || hasCrafting());
        }

        @Override
        public boolean isActive() {
            return (visibleGroups & GROUP_STORAGE) != 0 && (small || hasCrafting()) && !isClipped();
        }

        private boolean isClipped() {
            return ((HiveSlot) this).clipped;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return (small || hasCrafting()) && super.mayPlace(stack);
        }
    }

    private class HiveResultSlot extends ResultSlot implements StationSlot {
        private final int baseY;
        private boolean clipped;

        HiveResultSlot(Player player, CraftingContainer craftSlots, Container container, int index, int x, int y) {
            super(player, craftSlots, container, index, x, y);
            this.baseY = y;
        }

        @Override
        public int stationGroup() {
            return GROUP_CRAFT;
        }

        @Override
        public void place(int dy, boolean clipped) {
            this.y = baseY + dy;
            this.clipped = clipped;
        }

        @Override
        public boolean isActive() {
            return (visibleGroups & GROUP_STORAGE) != 0 && !clipped;
        }
    }

    /**
     * The trash: a workstation of its own, with one slot that takes anything, whole stacks included. What is put in it stays there, as in Terraria,
     * until another item is put over it, when the two change places (the old one is on the cursor, to be put back or thrown away by being put
     * over the next one). It is the hive's, so it is kept when the menu is closed and the game saved.
     */
    private class TrashSlot extends HiveSlot {
        TrashSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_TRASH);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return true;
        }

        /** Room for a whole stack, even one that has grown past the usual size (the container holds up to a thousand times the usual). */
        @Override
        public int getMaxStackSize(ItemStack stack) {
            return Integer.MAX_VALUE;
        }
    }

    /** A cartography table input: the map (a filled map), or the material (paper, a glass pane or an empty map). */
    private class CartographyInputSlot extends HiveSlot {
        private final boolean mapSlot;

        CartographyInputSlot(Container container, int index, int x, int y, boolean mapSlot) {
            super(container, index, x, y, GROUP_CARTOGRAPHY);
            this.mapSlot = mapSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return mapSlot ? stack.is(net.minecraft.world.item.Items.FILLED_MAP)
                    : stack.is(net.minecraft.world.item.Items.PAPER) || stack.is(net.minecraft.world.item.Items.GLASS_PANE) || stack.is(net.minecraft.world.item.Items.MAP);
        }
    }

    /** The cartography table's result: nothing can be put in it; taking it uses up one of each input and finishes the map. */
    private class CartographyResultSlot extends HiveSlot {
        CartographyResultSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_CARTOGRAPHY);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public void onTake(Player taker, ItemStack stack) {
            stack.getItem().onCraftedBy(stack, taker.level(), taker);
            cartographySlots.removeItem(0, 1);
            cartographySlots.removeItem(1, 1);
            taker.level().playSound(null, taker.blockPosition(), net.minecraft.sounds.SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
            super.onTake(taker, stack);
        }
    }

    /** An anvil input: any item (what it can be combined with is the anvil's business, and the result slot stays empty when nothing works). */
    private class AnvilInputSlot extends HiveSlot {
        AnvilInputSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_ANVIL);
        }
    }

    /** The anvil's result: nothing can be put in it; taking it needs the levels, uses up the inputs and spends them. */
    private class AnvilResultSlot extends HiveSlot {
        AnvilResultSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_ANVIL);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player taker) {
            return anvilEngine != null && anvilEngine.canTake(taker);
        }

        @Override
        public void onTake(Player taker, ItemStack stack) {
            if (anvilEngine != null) {
                anvilEngine.take(taker, stack);
                // What the game's anvil left in its inputs is what is left in these.
                anvilSlots.setItem(0, anvilEngine.input(0).copy());
                anvilSlots.setItem(1, anvilEngine.input(1).copy());
            }
            super.onTake(taker, stack);
        }
    }

    /** The jukebox slot: one music disc, which plays while it is there. */
    private class JukeboxSlot extends HiveSlot {
        JukeboxSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_JUKEBOX);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.has(net.minecraft.core.component.DataComponents.JUKEBOX_PLAYABLE);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** The enchanting station's item slot: one enchantable item at a time. */
    private class EnchantItemSlot extends HiveSlot {
        EnchantItemSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_ENCHANT);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.isEnchantable();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** The enchanting station's lapis slot: only lapis lazuli. */
    private class EnchantLapisSlot extends HiveSlot {
        EnchantLapisSlot(Container container, int index, int x, int y) {
            super(container, index, x, y, GROUP_ENCHANT);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(net.minecraft.world.item.Items.LAPIS_LAZULI);
        }
    }

    /** Holds one armor piece, and only of the right kind: new soldiers wear a copy of it. */
    private class ArmorSlot extends HiveSlot {
        private final EquipmentSlot equipmentSlot;
        private final int position;

        /** @param position index in {@link HiveEquipment#ARMOR_SLOTS}, which picks the empty-slot icon */
        ArmorSlot(Container container, int position, int x, int y, EquipmentSlot equipmentSlot) {
            this(container, position, x, y, equipmentSlot, GROUP_GEAR);
        }

        ArmorSlot(Container container, int position, int x, int y, EquipmentSlot equipmentSlot, int group) {
            super(container, position, x, y, group);
            this.equipmentSlot = equipmentSlot;
            this.position = position;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return HiveEquipment.isArmorFor(stack, equipmentSlot) && (stationGroup() != GROUP_SCOUT_ARMOR || stack.is(net.minecraft.tags.ItemTags.DYEABLE));
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
