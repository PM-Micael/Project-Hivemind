package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.FeederBehavior;
import com.projecthivemind.ScoutBehavior;
import com.projecthivemind.SoldierBehavior;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.WorkerBehavior;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.menu.StorageScroll;
import com.projecthivemind.network.CancelJobPayload;
import com.projecthivemind.network.FocusUnitPayload;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.ReturnToHeartPayload;
import com.projecthivemind.network.ScrollStoragePayload;
import com.projecthivemind.network.SetJobResumePayload;
import com.projecthivemind.network.SetCollectorTaskPayload;
import com.projecthivemind.network.SetUnitTeamPayload;
import com.projecthivemind.network.SetStorageSearchPayload;
import com.projecthivemind.network.SetTeamRadiusPayload;
import com.projecthivemind.network.SyncPortalsPayload;
import com.projecthivemind.network.SetMenuViewPayload;
import com.projecthivemind.network.SyncUnitsPayload;
import com.projecthivemind.network.SetUnitBehaviorPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;
import com.projecthivemind.network.ViewUnitPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive menu. The Hive tab is the hive's inventory: shared storage, the crafting grid, gear, food, the scout's
 * hand, and the unit counts. The Quests tab is the level-up quest. Under the tabs is a row of buttons, one for each
 * kind of unit, each showing the head of that unit's mob model (its name is the tooltip). A unit's button opens its page:
 * a head for each unit of that kind that is out, and next to them that one unit's behaviour settings. Drawn with plain
 * rectangles until there is real art.
 */
public class HiveScreen extends AbstractContainerScreen<HiveMenu> {
    private static final int PANEL = 0xF0201414;
    private static final int PANEL_EDGE = 0xFF6B2A2A;
    private static final int SLOT_EDGE = 0xFF120A0A;
    private static final int SLOT_FILL = 0xFF3A2424;
    private static final int HIGHLIGHT = 0xFFFFDD55;

    /** How far apart the row of unit counts is spread. */
    private static final int COUNTS_SPACING = 62;

    // Layout, relative to the panel.
    /** The row of unit buttons, under the text tabs. */
    private static final int UNIT_TAB_Y = 50;
    /** Where the labels over the slot grids sit, just above the slots. */
    private static final int LABEL_Y = HiveMenu.STORAGE_Y - 10;
    /** A unit page: the column of heads on the left, then the behaviour settings. */
    private static final int UNIT_LIST_X = 12;
    private static final int UNIT_LIST_TOP = 94;
    private static final int UNIT_HEAD = 28;
    private static final int BEHAVIOR_X = 60;
    /** A unit's job, above its settings: a line saying what it is, and the tick box for taking it up again. */
    private static final int HEALTH_Y = 74;
    private static final int JOB_TOP = 88;
    private static final int BEHAVIOR_TOP = 126;
    /** A collector's two planting rows (crops, then saplings), and the hint under them. */
    private static final int CROP_ROW_Y = BEHAVIOR_TOP + 22;
    private static final int SAPLING_ROW_Y = BEHAVIOR_TOP + 52;
    private static final int BEHAVIOR_ROW = 18;
    /** The row of the worker page where the "Woodwork" group begins: its heading is drawn there, and its options are the rows after it. */
    /** The rows of the worker page where its groups of options begin: the heading is drawn there and the options are the rows after it. */
    private static final int BORDER_ROW = 4;
    private static final int WOODWORK_ROW = 10;

    private enum Tab {
        HIVE, QUESTS, UNITS, TEAM, PORTALS, EVOLVE
    }

    /** The order of the row of unit buttons: the same as the command bar's keys, then the collectors. */
    private static final UnitKind[] KINDS = {UnitKind.SCOUT, UnitKind.SOLDIER, UnitKind.WORKER, UnitKind.COLLECTOR, UnitKind.FEEDER};

    private Tab tab = Tab.HIVE;
    /** Which kind's page the Units tab is showing. */
    private UnitKind unitPage = UnitKind.SOLDIER;
    private Button hiveTab;
    private Button questsTab;
    private Button teamTab;
    private Button portalsTab;
    private Button evolveTab;
    /** Which workstation shows on the right of the Hive tab: the crafting grid, or the furnace (from level 3). */
    private boolean furnaceShown;
    private boolean brewingShown;
    private SeedButton brewingButton;
    private SeedButton enchantStationButton;
    private SeedButton jukeboxButton;
    private boolean jukeboxShown;
    private final Button[] enchantOptions = new Button[3];
    private boolean enchantShown;
    private SeedButton craftingButton;
    private SeedButton furnaceButton;
    /** What was on offer the last time the workstation buttons were laid out, so a change (the hive levelling up) lays them out again. */
    private int stationKey = -1;
    /** The search box over the hive storage: only what has this text in its name is shown. */
    private EditBox storageSearch;
    private final Map<UnitKind, Button> kindTabs = new EnumMap<>(UnitKind.class);

    /** The units of the open page whose heads are shown, and their buttons. */
    private final List<Integer> shownUnits = new ArrayList<>();
    private final List<AbstractWidget> unitButtons = new ArrayList<>();
    /** The unit whose settings are shown: its entity id, or -1. */
    private int viewedUnit = -1;
    /** Numbers each request for a unit's settings, so a late answer for an earlier unit is not taken for this one's. */
    private int viewSeq;

    // Soldier settings.
    private Checkbox allInHiveArea;
    private Checkbox hostileInHiveArea;
    private Checkbox soldierStay;
    private Checkbox soldierWander;

    // Worker settings.
    private final EditBox[] workerRadii = new EditBox[3];
    private Checkbox mineOre;
    private Checkbox chopLogs;
    private Checkbox digThrough;
    private Checkbox workerStay;
    private Checkbox harvestCrops;
    private Checkbox clearPlants;

    // Collector setting: just the one.
    /** The collector's planting task: the seed it plants, and clearing the soil block it plants on. */
    private Button seedButton;
    private Button saplingButton;
    private Button clearSaplingSpotsButton;
    private Button clearSpotButton;

    // Scout settings: the radius and two checkboxes.
    private final EditBox[] scoutRadii = new EditBox[2];
    private Checkbox pickUpItems;
    private Checkbox collectorPickUp;
    private Checkbox fleeHostiles;
    private Checkbox scoutStay;
    private Checkbox scoutWander;
    private Checkbox scoutSneak;
    private Checkbox workerWander;
    private Checkbox flattenGround;
    private Checkbox flattenTeam;
    private Checkbox channelCrops;
    private Checkbox useBoneMeal;
    private Checkbox channelSaplings;
    private Checkbox fellTrees;
    private Checkbox workerFlee;
    private Checkbox useComposter;
    private SeedButton compostButton;
    /** How far the unit page's settings are scrolled up, in pixels; and how far the widgets have been moved for it so far. */
    private int behaviorScroll;
    private int appliedScroll;
    private SeedButton fillButton;

    private final List<AbstractWidget> soldierWidgets = new ArrayList<>();
    private final List<AbstractWidget> workerWidgets = new ArrayList<>();
    private final List<AbstractWidget> collectorWidgets = new ArrayList<>();
    private final List<AbstractWidget> scoutWidgets = new ArrayList<>();
    private final List<AbstractWidget> feederWidgets = new ArrayList<>();

    /** True while the widgets are being filled from the server's values, so that does not count as the player editing. */
    private boolean filling;
    /** The widgets show the viewed unit's real settings. Until then they are disabled, so nothing wrong can be sent back. */
    private boolean behaviorLoaded;
    /** The tick box for going back to the viewed unit's set-aside job; true while it is being set from the server's word. */
    private Checkbox jobResumeBox;
    private boolean jobFilling;
    /** Ends the viewed unit's job, after the player has confirmed. */
    private Button cancelJobButton;
    private Button killUnitButton;

    /** A unit page the player asked for before the menu was open (from the popup over a unit): its kind and unit. */
    @Nullable
    private static UnitKind requestedKind;
    private static int requestedUnit = -1;

    /** Ask for the hive menu to open on this unit's page. Taken up, once, by the next hive menu to open. */
    static void requestUnitPage(UnitKind kind, int entityId) {
        requestedKind = kind;
        requestedUnit = entityId;
    }

    public HiveScreen(HiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // Wide enough for the tabs, with room to spare for more.
        this.imageWidth = 360;
        // Tall enough for a unit's page, whatever the storage size.
        this.imageHeight = Math.max(HiveMenu.panelHeight(menu.storageRows()), 262);
        this.titleLabelX = 8;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();
        // Top row: the text tabs. Second row: one button for each kind of unit, showing its head.
        hiveTab = tabButton(0, Items.BEE_NEST, "screen.projecthivemind.hive.tab_hive", Tab.HIVE);
        questsTab = tabButton(1, Items.BOOK, "screen.projecthivemind.hive.tab_quests", Tab.QUESTS);
        teamTab = tabButton(2, Items.DIAMOND_SWORD, "screen.projecthivemind.hive.tab_team", Tab.TEAM);
        portalsTab = tabButton(3, Items.ENDER_PEARL, "screen.projecthivemind.hive.tab_portals", Tab.PORTALS);
        evolveTab = tabButton(4, Items.DRAGON_EGG, "screen.projecthivemind.hive.tab_evolve", Tab.EVOLVE);
        updateTabButtons();
        kindTabs.clear();
        for (int i = 0; i < KINDS.length; i++) {
            UnitKind kind = KINDS[i];
            Component name = Component.translatable("command.projecthivemind." + kind.name().toLowerCase(Locale.ROOT) + "s");
            UnitIconButton button = new UnitIconButton(leftPos + 8 + i * 26, topPos + UNIT_TAB_Y, 24, 20, name,
                    () -> HeadIcons.standIn(kind), kind, () -> tab == Tab.UNITS && unitPage == kind, pressed -> showTab(Tab.UNITS, kind));
            button.setTooltip(Tooltip.create(name));
            kindTabs.put(kind, addRenderableWidget(button));
        }

        // The workstation on the right: the crafting grid, or (from level 3) the furnace. The buttons sit beside the storage's scrollbar.
        int stationX = leftPos + HiveMenu.STORAGE_X + 9 * 18 + 10;
        craftingButton = addRenderableWidget(new SeedButton(stationX, topPos + HiveMenu.STORAGE_Y, 20, 20,
                () -> new ItemStack(net.minecraft.world.item.Items.CRAFTING_TABLE), button -> chooseWorkstation(0)));
        craftingButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.hive.crafting")));
        furnaceButton = addRenderableWidget(new SeedButton(stationX, topPos + HiveMenu.STORAGE_Y + 24, 20, 20,
                () -> new ItemStack(net.minecraft.world.item.Items.FURNACE), button -> chooseWorkstation(1)));
        furnaceButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.hive.furnace")));
        brewingButton = addRenderableWidget(new SeedButton(stationX, topPos + HiveMenu.STORAGE_Y + 48, 20, 20,
                () -> new ItemStack(net.minecraft.world.item.Items.BREWING_STAND), button -> chooseWorkstation(2)));
        brewingButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.hive.brewing")));
        craftingButton.visible = false;
        furnaceButton.visible = false;
        brewingButton.visible = false;
        enchantStationButton = addRenderableWidget(new SeedButton(stationX, topPos + HiveMenu.STORAGE_Y + 72, 20, 20,
                () -> new ItemStack(net.minecraft.world.item.Items.ENCHANTING_TABLE), button -> chooseWorkstation(3)));
        enchantStationButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.hive.enchanting")));
        enchantStationButton.visible = false;
        jukeboxButton = addRenderableWidget(new SeedButton(stationX, topPos + HiveMenu.STORAGE_Y + 96, 20, 20,
                () -> new ItemStack(net.minecraft.world.item.Items.JUKEBOX), button -> chooseWorkstation(4)));
        jukeboxButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.hive.jukebox")));
        jukeboxButton.visible = false;
        // The three enchanting options, to the right of the item and lapis slots.
        for (int i = 0; i < 3; i++) {
            int option = i;
            enchantOptions[i] = addRenderableWidget(Button.builder(Component.empty(),
                    button -> PacketDistributor.sendToServer(new com.projecthivemind.network.EnchantPayload(menu.containerId, option)))
                    .bounds(leftPos + HiveMenu.ENCHANT_ITEM_X + 26, topPos + HiveMenu.ENCHANT_ITEM_Y - 8 + i * 22, 78, 20).build());
            enchantOptions[i].visible = false;
        }

        // Search the storage: what is typed here filters the storage grid to the items whose name has it in it.
        storageSearch = addRenderableWidget(new EditBox(font, leftPos + HiveMenu.STORAGE_X + 46, topPos + LABEL_Y - 2, 112, 12,
                Component.translatable("screen.projecthivemind.hive.search")));
        storageSearch.setHint(Component.translatable("screen.projecthivemind.hive.search"));
        storageSearch.setMaxLength(32);
        storageSearch.setBordered(true);
        storageSearch.setResponder(text -> PacketDistributor.sendToServer(new SetStorageSearchPayload(menu.containerId, text)));

        // Fly the camera back to the Hive Heart.
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.hive.to_heart"), button -> {
            PacketDistributor.sendToServer(new ReturnToHeartPayload());
            onClose();
        }).bounds(leftPos + imageWidth - 8 - 70, topPos + 5, 70, 16).build());

        // Creative players can drop out of the hive to the normal inventory, e.g. to spawn items in for testing.
        if (ClientState.canSwapInventory()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.swap.to_normal"), button -> {
                PacketDistributor.sendToServer(new ToggleInventoryModePayload());
                onClose();
            }).bounds(leftPos + imageWidth - 8 - 90, topPos + 28, 90, 18).build());
        }

        shownUnits.clear();
        unitButtons.clear();
        jobResumeBox = Checkbox.builder(Component.translatable("screen.projecthivemind.job.resume"), font)
                .maxWidth(imageWidth - BEHAVIOR_X - 12)
                .onValueChange((box, value) -> {
                    if (!jobFilling && viewedUnit >= 0) {
                        PacketDistributor.sendToServer(new SetJobResumePayload(viewedUnit, value));
                    }
                })
                .build();
        jobResumeBox.setPosition(leftPos + BEHAVIOR_X, topPos + JOB_TOP + 12);
        addRenderableWidget(jobResumeBox);
        cancelJobButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.job.cancel"), button -> askToCancelJob())
                .bounds(leftPos + imageWidth - 12 - 80, topPos + JOB_TOP - 4, 80, 16).build());
        killUnitButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.unit.kill").withStyle(net.minecraft.ChatFormatting.RED), button -> askToKillUnit())
                .bounds(leftPos + BEHAVIOR_X, topPos + imageHeight - 26, 90, 18).build());
        killUnitButton.visible = false;
        initBehaviorWidgets();
        // Opened from the popup over a unit: go straight to that unit's page.
        int requested = requestedUnit;
        UnitKind requestedPage = requestedKind;
        requestedKind = null;
        requestedUnit = -1;
        if (requestedPage != null) {
            showTab(Tab.UNITS, requestedPage);
            viewUnit(requested);
        } else {
            showTab(tab, unitPage);
        }
    }

    /** A square button for a tab, showing an item as its icon; the tab's name is its tooltip. */
    private Button tabButton(int index, net.minecraft.world.item.Item icon, String key, Tab target) {
        SeedButton button = new SeedButton(leftPos + 8 + index * TAB_STEP, topPos + 28, 20, 20, () -> new ItemStack(icon), pressed -> showTab(target, unitPage));
        button.setTooltip(Tooltip.create(Component.translatable(key)));
        return addRenderableWidget(button);
    }

    private static final int TAB_STEP = 22;

    /** Which tab buttons are there for this level, packed to the left: the Portals tab comes with level 3, the Evolve tab with level 2. */
    private void updateTabButtons() {
        portalsTab.visible = menu.level() >= com.projecthivemind.HiveLevels.PORTAL_LEVEL;
        evolveTab.visible = menu.level() >= com.projecthivemind.HiveLevels.EVOLVE_LEVEL;
        evolveTab.setX(leftPos + 8 + (portalsTab.visible ? 4 : 3) * TAB_STEP);
    }

    /** A button that shows the head of a unit's mob model where a label would be. */
    private static final class UnitIconButton extends Button {
        private final Supplier<LivingEntity> entity;
        private final UnitKind kind;
        private final BooleanSupplier highlighted;

        UnitIconButton(int x, int y, int width, int height, Component name, Supplier<LivingEntity> entity, UnitKind kind,
                       BooleanSupplier highlighted, Button.OnPress onPress) {
            super(x, y, width, height, name, onPress, DEFAULT_NARRATION);
            this.entity = entity;
            this.kind = kind;
            this.highlighted = highlighted;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            if (highlighted.getAsBoolean()) {
                graphics.renderOutline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2, HIGHLIGHT);
            }
            HeadIcons.draw(graphics, getX() + 2, getY() + 2, getX() + getWidth() - 2, getY() + getHeight() - 2, entity.get(), kind);
        }

        /** The name is only a tooltip: drawing it on the button as well would put text behind the head. */
        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
        }
    }

    /**
     * Which slots of the menu show: on the Hive tab the storage, the gear and one workstation, the crafting grid or the furnace; on the
     * other tabs none. The two buttons by the storage's scrollbar (there only once the furnace is unlocked) choose between the workstations.
     */
    private void applyMenuGroups(Tab forTab) {
        // The workstations the hive can use: the crafting grid (2x2 to begin with, 3x3 once a crafting table has been consumed), the furnace and the brewing stand once each has been consumed on the Evolve
        // tab, and the evolve slot from level 2. The one shown is the one chosen, or else the first there is.
        boolean[] offered = {true, menu.hasFurnace(), menu.hasBrewing(), menu.hasEnchanting(), menu.hasJukebox()};
        int chosen = jukeboxShown ? 4 : enchantShown ? 3 : brewingShown ? 2 : furnaceShown ? 1 : 0;
        if (!offered[chosen]) {
            chosen = 0;
            for (int i = 0; i < offered.length; i++) {
                if (offered[i]) {
                    chosen = i;
                    break;
                }
            }
        }
        furnaceShown = chosen == 1;
        brewingShown = chosen == 2;
        enchantShown = chosen == 3;
        jukeboxShown = chosen == 4;
        menu.visibleGroups = forTab == Tab.HIVE
                ? HiveMenu.GROUP_STORAGE | HiveMenu.GROUP_GEAR
                        | (chosen == 4 ? HiveMenu.GROUP_JUKEBOX : chosen == 3 ? HiveMenu.GROUP_ENCHANT : chosen == 2 ? HiveMenu.GROUP_BREWING : chosen == 1 ? HiveMenu.GROUP_FURNACE : HiveMenu.GROUP_CRAFT) : 0;
        if (craftingButton != null) {
            // The buttons for the workstations on offer, one under the other. With only one there is nothing to choose.
            SeedButton[] buttons = {craftingButton, furnaceButton, brewingButton, enchantStationButton, jukeboxButton};
            int count = 0;
            for (boolean has : offered) {
                count += has ? 1 : 0;
            }
            int row = 0;
            for (int i = 0; i < buttons.length; i++) {
                buttons[i].visible = forTab == Tab.HIVE && offered[i] && count > 1;
                buttons[i].active = chosen != i;
                if (offered[i]) {
                    buttons[i].setY(topPos + HiveMenu.STORAGE_Y + row * 24);
                    row++;
                }
            }
            stationKey = stationKey();
        }
    }

    /** The evolve slot is there from level 2. */
    private boolean evolveAvailable() {
        return menu.level() >= com.projecthivemind.HiveLevels.EVOLVE_LEVEL;
    }

    private int stationKey() {
        return (menu.hasFurnace() ? 1 : 0) | (menu.hasBrewing() ? 2 : 0) | (menu.hasCrafting() ? 8 : 0) | (menu.hasEnchanting() ? 16 : 0) | (menu.hasJukebox() ? 32 : 0);
    }

    /**
     * The three enchanting options: there while the enchanting station is open. Each shows the level it asks for; its popup gives the hint of what
     * it may enchant with (as at an enchanting table), and what it costs, in red where the hive is short.
     */
    private void updateEnchantButtons() {
        boolean shown = tab == Tab.HIVE && enchantShown && menu.hasEnchanting();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        net.minecraft.core.IdMap<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> ids =
                minecraft.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).asHolderIdMap();
        boolean free = minecraft.player.hasInfiniteMaterials();
        for (int i = 0; i < 3; i++) {
            Button button = enchantOptions[i];
            button.visible = shown;
            int cost = menu.enchantCosts[i];
            if (cost <= 0) {
                button.setMessage(Component.literal("-"));
                button.active = false;
                button.setTooltip(null);
                continue;
            }
            boolean lapisOk = free || menu.enchantLapis() >= i + 1;
            boolean levelsOk = free || minecraft.player.experienceLevel >= Math.max(i + 1, cost);
            button.setMessage(Component.translatable("screen.projecthivemind.enchant.option", cost));
            button.active = lapisOk && levelsOk;
            net.minecraft.network.chat.MutableComponent text = Component.empty();
            net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> clue = menu.enchantClue[i] >= 0 ? ids.byId(menu.enchantClue[i]) : null;
            if (clue != null) {
                text.append(net.minecraft.world.item.enchantment.Enchantment.getFullname(clue, menu.enchantLevelClue[i])).append(Component.literal(" ...?")).append("\n");
            }
            text.append(Component.translatable(i == 0 ? "container.enchant.lapis.one" : "container.enchant.lapis.many", i + 1)
                    .withStyle(lapisOk ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.RED)).append("\n");
            text.append(Component.translatable(i == 0 ? "container.enchant.level.one" : "container.enchant.level.many", i + 1)
                    .withStyle(levelsOk ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.RED));
            if (cost > i + 1) {
                text.append("\n").append(Component.translatable("container.enchant.level.requirement", cost)
                        .withStyle(levelsOk ? net.minecraft.ChatFormatting.GRAY : net.minecraft.ChatFormatting.RED));
            }
            button.setTooltip(Tooltip.create(text));
        }
    }

    private void chooseWorkstation(int station) {
        furnaceShown = station == 1;
        brewingShown = station == 2;
        enchantShown = station == 3;
        jukeboxShown = station == 4;
        applyMenuGroups(tab);
        PacketDistributor.sendToServer(new SetMenuViewPayload(menu.containerId, menu.visibleGroups));
    }

    /** Switch tab. Each tab shows its own slots, and the Units tab shows the page of one kind of unit. */
    private void showTab(Tab newTab, UnitKind kind) {
        tab = newTab;
        unitPage = kind;
        unitListScroll = 0;
        behaviorScroll = 0;
        // Only the Hive tab has slots; on the others they are hidden and cannot be clicked. The server is told, so that
        // shift-click agrees.
        applyMenuGroups(newTab);
        PacketDistributor.sendToServer(new SetMenuViewPayload(menu.containerId, menu.visibleGroups));
        hiveTab.active = newTab != Tab.HIVE;
        questsTab.active = newTab != Tab.QUESTS;
        teamTab.active = newTab != Tab.TEAM;
        dragUnit = -1;
        portalsTab.active = newTab != Tab.PORTALS;
        evolveTab.active = newTab != Tab.EVOLVE;
        storageSearch.visible = newTab == Tab.HIVE;

        if (newTab == Tab.PORTALS) {
            portalScroll = 0;
            refreshPortals(true);
        } else if (newTab == Tab.TEAM) {
            shownTeam.clear();
            refreshTeam(true);
        } else if (newTab == Tab.UNITS) {
            // Start on the first unit of this kind (not selecting it: that is for the player to do).
            shownUnits.clear();
            refreshUnitList(true);
        } else {
            clearUnitButtons();
        }
        updateBehaviorVisibility();
    }

    private void updateBehaviorVisibility() {
        boolean units = tab == Tab.UNITS && viewedUnit >= 0;
        killUnitButton.visible = units;
        soldierWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.SOLDIER);
        workerWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.WORKER);
        collectorWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.COLLECTOR);
        scoutWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.SCOUT);
        feederWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.FEEDER);
        applyBehaviorScroll();
    }

    // ---- scrolling the settings ----

    /** The bottom of the settings area, in the panel's own coordinates: a little above the panel's edge. */
    private int behaviorViewBottom() {
        return imageHeight - 32;
    }

    /** Where the unit page's settings end if nothing is scrolled: how tall the settings of this kind of unit are. */
    private int behaviorContentBottom() {
        return switch (unitPage) {
            case SOLDIER -> BEHAVIOR_TOP + 4 * BEHAVIOR_ROW;
            case WORKER -> BEHAVIOR_TOP + (WOODWORK_ROW + 4) * BEHAVIOR_ROW;
            case FEEDER -> BEHAVIOR_TOP + 5 * BEHAVIOR_ROW;
            case SCOUT -> BEHAVIOR_TOP + 6 * BEHAVIOR_ROW;
            default -> 0;
        };
    }

    private int maxBehaviorScroll() {
        return Math.max(0, behaviorContentBottom() - behaviorViewBottom());
    }

    /**
     * Move the settings widgets to match the scroll, and hide the ones that are not wholly inside the settings area, so that they
     * neither show nor take clicks outside it. Called whenever the page or the scroll changes.
     */
    private void applyBehaviorScroll() {
        int delta = appliedScroll - behaviorScroll;
        if (delta != 0) {
            for (List<AbstractWidget> group : List.of(soldierWidgets, workerWidgets, collectorWidgets, scoutWidgets, feederWidgets)) {
                for (AbstractWidget widget : group) {
                    widget.setY(widget.getY() + delta);
                }
            }
            appliedScroll = behaviorScroll;
        }
        int top = topPos + BEHAVIOR_TOP - 4;
        int bottom = topPos + behaviorViewBottom();
        for (List<AbstractWidget> group : List.of(soldierWidgets, workerWidgets, collectorWidgets, scoutWidgets, feederWidgets)) {
            for (AbstractWidget widget : group) {
                if (widget.visible && (widget.getY() < top || widget.getY() + widget.getHeight() > bottom)) {
                    widget.visible = false;
                }
            }
        }
    }

    private void scrollBehavior(int pixels) {
        int next = Math.max(0, Math.min(maxBehaviorScroll(), behaviorScroll + pixels));
        if (next != behaviorScroll) {
            behaviorScroll = next;
            updateBehaviorVisibility();
        }
    }

    /** A thin bar at the right of the settings area when they do not all fit. */
    private void renderBehaviorScrollbar(GuiGraphics graphics) {
        int max = maxBehaviorScroll();
        if (max <= 0) {
            return;
        }
        int top = BEHAVIOR_TOP - 4;
        int height = behaviorViewBottom() - top;
        int thumb = Math.max(12, height * height / (height + max));
        int thumbY = top + (height - thumb) * behaviorScroll / max;
        graphics.fill(imageWidth - 7, top, imageWidth - 5, top + height, 0x44000000);
        graphics.fill(imageWidth - 7, thumbY, imageWidth - 5, thumbY + thumb, 0xFFC0C0C0);
    }

    private void renderWorkerNote(GuiGraphics graphics) {
        // The headings of the groups of options.
        for (int[] heading : new int[][] {{BORDER_ROW}, {WOODWORK_ROW}}) {
            int headingY = BEHAVIOR_TOP + heading[0] * BEHAVIOR_ROW + 5 - behaviorScroll;
            if (headingY >= BEHAVIOR_TOP - 4 && headingY + 9 <= behaviorViewBottom()) {
                graphics.drawString(font, Component.translatable(heading[0] == BORDER_ROW ? "screen.projecthivemind.behavior.group.border"
                        : "screen.projecthivemind.behavior.group.woodwork"), BEHAVIOR_X + 4, headingY, 0xFFDD55, false);
            }
        }
        int y = BEHAVIOR_TOP + (WOODWORK_ROW + 3) * BEHAVIOR_ROW + 4 - behaviorScroll;
        if (y >= BEHAVIOR_TOP - 4 && y + 9 <= behaviorViewBottom()) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.worker_note"), BEHAVIOR_X + 4, y, 0x909090, false);
        }
        renderBehaviorScrollbar(graphics);
    }

    // ---- the unit pages ----

    private void clearUnitButtons() {
        for (AbstractWidget button : unitButtons) {
            removeWidget(button);
        }
        unitButtons.clear();
    }

    /**
     * Keep the column of heads in step with the units that are out: when one is made or lost the column is rebuilt, and if
     * the unit whose settings are shown is gone, the first one is shown instead.
     */
    private void refreshUnitList(boolean force) {
        List<Integer> ids = ClientUnits.ofKind(unitPage);
        if (!force && ids.equals(shownUnits)) {
            return;
        }
        clearUnitButtons();
        shownUnits.clear();
        shownUnits.addAll(ids);
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i);
            Component name = Component.translatable("screen.projecthivemind.unit.numbered",
                    Component.translatable("unit.projecthivemind." + unitPage.name().toLowerCase(Locale.ROOT)), i + 1);
            UnitIconButton button = new UnitIconButton(leftPos + UNIT_LIST_X, topPos + UNIT_LIST_TOP, UNIT_HEAD, UNIT_HEAD,
                    name, () -> unitEntity(id, unitPage), unitPage, () -> id == viewedUnit, pressed -> {
                viewUnit(id);
                // Clicking a head also highlights that one unit in the world, and only it.
                ClientSelection.retain(Set.of());
                ClientSelection.select(id);
                // ...and the camera goes to it, 3 blocks away, looking straight at it.
                PacketDistributor.sendToServer(new FocusUnitPayload(id));
            });
            SyncUnitsPayload.Entry shown = ClientUnits.entry(id);
            button.setTooltip(Tooltip.create(shown != null && shown.away()
                    ? name.copy().append(Component.translatable("screen.projecthivemind.unit.away")) : name));
            unitButtons.add(addRenderableWidget(button));
        }
        unitListScroll = Math.max(0, Math.min(unitListScroll, Math.max(0, ids.size() - unitListVisible())));
        layoutUnitButtons();
        if (!ids.contains(viewedUnit)) {
            viewUnit(ids.isEmpty() ? -1 : ids.get(0));
        }
    }

    // ---- scrolling the list of units ----

    /** How far the column of unit heads is scrolled, in heads. */
    private int unitListScroll;

    /** How many unit heads fit in the column above the bottom of the panel. */
    private int unitListVisible() {
        return Math.max(1, (imageHeight - UNIT_LIST_TOP - 10) / (UNIT_HEAD + 4));
    }

    /** Put each head where the scroll says, and hide the ones that are scrolled out of the column. */
    private void layoutUnitButtons() {
        int visible = unitListVisible();
        for (int i = 0; i < unitButtons.size(); i++) {
            AbstractWidget button = unitButtons.get(i);
            button.setY(topPos + UNIT_LIST_TOP + (i - unitListScroll) * (UNIT_HEAD + 4));
            button.visible = i >= unitListScroll && i < unitListScroll + visible;
        }
    }

    /** A thin bar beside the column of heads when there are more than fit. */
    private void renderUnitListScrollbar(GuiGraphics graphics) {
        int visible = unitListVisible();
        int count = shownUnits.size();
        if (count <= visible) {
            return;
        }
        int height = visible * (UNIT_HEAD + 4) - 4;
        int thumb = Math.max(10, height * visible / count);
        int thumbY = UNIT_LIST_TOP + (height - thumb) * unitListScroll / (count - visible);
        int x = UNIT_LIST_X + UNIT_HEAD + 3;
        graphics.fill(x, UNIT_LIST_TOP, x + 2, UNIT_LIST_TOP + height, 0x44000000);
        graphics.fill(x, thumbY, x + 2, thumbY + thumb, 0xFFC0C0C0);
    }

    /** The live unit to draw, or a stand-in of its kind when it is out of the client's sight. */
    @Nullable
    private static LivingEntity unitEntity(int id, UnitKind kind) {
        Minecraft minecraft = Minecraft.getInstance();
        // A crouching (sneaking) scout is drawn with its head pushed down out of the button, so it gets the stand-in instead.
        if (minecraft.level != null && minecraft.level.getEntity(id) instanceof LivingEntity living && !living.isCrouching()) {
            return living;
        }
        return HeadIcons.standIn(kind);
    }

    /** Show this unit's settings: ask the server for them, and wait for them before letting them be edited. */
    private void viewUnit(int id) {
        viewedUnit = id;
        behaviorScroll = 0;
        behaviorLoaded = false;
        setBehaviorEnabled(false);
        if (id >= 0) {
            viewSeq = (viewSeq + 1) % 30000;
            PacketDistributor.sendToServer(new ViewUnitPayload(menu.containerId, id, viewSeq));
        }
        updateBehaviorVisibility();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateJobBox();
        // The portal network comes with level 3; the tab is there once the hive has it.
        updateTabButtons();
        // The workstation buttons depend on the level, which only reaches the client a moment after the menu opens (and changes when the hive levels up).
        if (stationKey != stationKey()) {
            applyMenuGroups(tab);
        }
        updateEnchantButtons();
        if (tab == Tab.EVOLVE && !evolveTab.visible) {
            showTab(Tab.HIVE, unitPage);
        }
        if (tab == Tab.PORTALS) {
            if (portalsTab.visible) {
                refreshPortals(false);
            } else {
                showTab(Tab.HIVE, unitPage);
            }
        }
        if (tab == Tab.TEAM) {
            refreshTeam(false);
        }
        if (tab == Tab.UNITS) {
            refreshUnitList(false);
            if (!behaviorLoaded && viewedUnit >= 0 && menu.viewReady(viewSeq) && menu.viewKind() == unitPage) {
                loadBehavior();
            }
        }
    }

    /**
     * Cancelling a job cannot be undone, so the player is asked first. The question is a screen over this one; whatever the
     * answer, this one is shown again.
     */
    private void askToCancelJob() {
        int unit = viewedUnit;
        if (unit < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                PacketDistributor.sendToServer(new CancelJobPayload(unit));
            }
            minecraft.setScreen(this);
        }, Component.translatable("screen.projecthivemind.job.cancel_title"),
                Component.translatable("screen.projecthivemind.job.cancel_message"),
                CommonComponents.GUI_YES, CommonComponents.GUI_NO));
    }

    /** Killing a unit cannot be undone, so the player is asked first. Whatever the answer, this screen is shown again. */
    private void askToKillUnit() {
        int unit = viewedUnit;
        if (unit < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                PacketDistributor.sendToServer(new com.projecthivemind.network.KillUnitPayload(unit));
                viewedUnit = -1;
            }
            minecraft.setScreen(this);
        }, Component.translatable("screen.projecthivemind.unit.kill_title"),
                Component.translatable("screen.projecthivemind.unit.kill_message"),
                CommonComponents.GUI_YES, CommonComponents.GUI_NO));
    }

    /** Whether the page's unit has a job to show: only soldiers and workers do jobs. */
    @Nullable
    private SyncUnitsPayload.Entry viewedJob() {
        if (tab != Tab.UNITS || viewedUnit < 0 || (unitPage != UnitKind.SOLDIER && unitPage != UnitKind.WORKER)) {
            return null;
        }
        SyncUnitsPayload.Entry entry = ClientUnits.entry(viewedUnit);
        return entry != null && entry.hasJob() ? entry : null;
    }

    /** The job's tick box shows only when there is a job, and mirrors what the server says about the unit. */
    private void updateJobBox() {
        SyncUnitsPayload.Entry entry = viewedJob();
        jobResumeBox.visible = entry != null;
        cancelJobButton.visible = entry != null;
        if (entry != null && jobResumeBox.selected() != entry.resume()) {
            jobFilling = true;
            jobResumeBox.onPress();
            jobFilling = false;
        }
    }

    /** Clearing every planting spot of a kind cannot be undone, so the player is asked first; this screen comes back either way. */
    private void askToClearSpots(HiveCollector.PlantKind kind) {
        int unit = viewedUnit;
        if (unit < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                PacketDistributor.sendToServer(new SetCollectorTaskPayload(unit, taskOp(kind, 2), "", BlockPos.ZERO));
            }
            minecraft.setScreen(this);
        }, Component.translatable("screen.projecthivemind.collector.clear_title." + kind.name().toLowerCase(Locale.ROOT)),
                Component.translatable("screen.projecthivemind.collector.clear_message"),
                CommonComponents.GUI_YES, CommonComponents.GUI_NO));
    }


    /** The operation number the server wants: saplings are the same as crops, plus 10. */
    private static int taskOp(HiveCollector.PlantKind kind, int op) {
        return kind == HiveCollector.PlantKind.SAPLING ? op + 10 : op;
    }

    /** What the viewed collector plants for this kind, as an item stack to draw (empty for none). */
    private ItemStack currentPlant(HiveCollector.PlantKind kind) {
        SyncUnitsPayload.Entry entry = ClientUnits.entry(viewedUnit);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        String name = kind == HiveCollector.PlantKind.SAPLING ? entry.task().sapling() : entry.task().seed();
        ResourceLocation id = name.isEmpty() ? null : ResourceLocation.tryParse(name);
        return id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** Pick what is planted: a list of everything that can be planted that way, shown over this screen. */
    private void openPlantPicker(HiveCollector.PlantKind kind) {
        int unit = viewedUnit;
        if (unit < 0) {
            return;
        }
        Minecraft.getInstance().setScreen(new SeedPickerScreen(this, kind, item ->
                PacketDistributor.sendToServer(new SetCollectorTaskPayload(unit, taskOp(kind, 0),
                        item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString(), BlockPos.ZERO))));
    }

    /** The block the viewed worker fills gaps with, as an item to show on the button; empty if none is chosen. */
    private ItemStack currentFill() {
        SyncUnitsPayload.Entry entry = ClientUnits.entry(viewedUnit);
        String name = entry == null || unitPage != UnitKind.WORKER ? "" : entry.task().seed();
        ResourceLocation id = name.isEmpty() ? null : ResourceLocation.tryParse(name);
        return id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** Pick the fill block: every plain full block, as a scrolling grid shown over this screen. */
    private void openFillPicker() {
        int unit = viewedUnit;
        if (unit < 0) {
            return;
        }
        Minecraft.getInstance().setScreen(new SeedPickerScreen(this, Component.translatable("screen.projecthivemind.fill.title"),
                item -> com.projecthivemind.entity.HiveWorker.fillBlock(item) != null,
                item -> PacketDistributor.sendToServer(new com.projecthivemind.network.SetWorkerFillPayload(unit,
                        item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString()))));
    }

    /** The item the viewed worker puts in composters, as an item to show on the button; empty if none is chosen. */
    private ItemStack currentCompost() {
        SyncUnitsPayload.Entry entry = ClientUnits.entry(viewedUnit);
        String name = entry == null || unitPage != UnitKind.FEEDER ? "" : entry.task().sapling();
        ResourceLocation id = name.isEmpty() ? null : ResourceLocation.tryParse(name);
        return id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** Pick the item for the composters: everything that can be composted, as a scrolling grid shown over this screen. */
    private void openCompostPicker() {
        int unit = viewedUnit;
        if (unit < 0) {
            return;
        }
        Minecraft.getInstance().setScreen(new SeedPickerScreen(this, Component.translatable("screen.projecthivemind.compost.title"),
                com.projecthivemind.entity.HiveWorker::compostable,
                item -> PacketDistributor.sendToServer(new com.projecthivemind.network.SetWorkerCompostPayload(unit,
                        item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString()))));
    }

    /** A button that shows the seed chosen, as its item, where a label would be. */
    private static final class SeedButton extends Button {
        private final Supplier<ItemStack> seed;

        SeedButton(int x, int y, int width, int height, Supplier<ItemStack> seed, Button.OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
            this.seed = seed;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            ItemStack stack = seed.get();
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, getX() + 2, getY() + 2);
            }
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
        }
    }

    // ---- the behaviour settings of the viewed unit ----

    /**
     * Build the checkboxes and the radius fields, disabled and empty. They are filled in, and enabled, by
     * {@link #loadBehavior()} once the server's real settings for the viewed unit have reached the client: showing
     * placeholders would hide the real settings and let them be overwritten. Each option that has a radius has its own
     * number field at the right of its row.
     */
    private void initBehaviorWidgets() {
        behaviorLoaded = false;
        filling = true;
        soldierWidgets.clear();
        workerWidgets.clear();
        collectorWidgets.clear();
        scoutWidgets.clear();
        feederWidgets.clear();

        // Soldiers: four options with a radius each, then the one that reaches anywhere.
        allInHiveArea = behaviorBox(soldierWidgets, 0, "screen.projecthivemind.behavior.all_in_hive", this::sendSoldierBehavior);

        hostileInHiveArea = behaviorBox(soldierWidgets, 1, "screen.projecthivemind.behavior.hostile_in_hive", this::sendSoldierBehavior);
        soldierStay = behaviorBox(soldierWidgets, 2, "screen.projecthivemind.behavior.stay_inside", this::sendSoldierBehavior);
        soldierWander = behaviorBox(soldierWidgets, 3, "screen.projecthivemind.behavior.wander", this::sendSoldierBehavior);

        // Workers: what to work on, with how far to look for it where that applies. Related options are grouped under headings: first the
        // ones with no group, then "Border Management" (BORDER_ROW) and "Woodwork" (WOODWORK_ROW).
        // The widgets are made at their unscrolled places.
        behaviorScroll = 0;
        appliedScroll = 0;
        // The first row: running from hostile mobs, the highest priority a worker has, with how close one has to come.
        workerFlee = behaviorBox(workerWidgets, 0, "screen.projecthivemind.behavior.worker_flee", this::sendWorkerBehavior);
        workerRadii[2] = radiusBox(workerWidgets, 0, 2, this::sendWorkerBehavior);
        mineOre = behaviorBox(workerWidgets, 1, "screen.projecthivemind.behavior.mine_ore", this::sendWorkerBehavior);
        workerRadii[0] = radiusBox(workerWidgets, 1, 0, this::sendWorkerBehavior);
        digThrough = behaviorBox(workerWidgets, 2, "screen.projecthivemind.behavior.dig_through", this::sendWorkerBehavior);
        harvestCrops = behaviorBox(workerWidgets, 3, "screen.projecthivemind.behavior.harvest_crops", this::sendWorkerBehavior);
        // Border Management: everything that is about the hive's own area. Its heading is row BORDER_ROW.
        workerStay = behaviorBox(workerWidgets, BORDER_ROW + 1, "screen.projecthivemind.behavior.stay_inside", this::sendWorkerBehavior);
        clearPlants = behaviorBox(workerWidgets, BORDER_ROW + 2, "screen.projecthivemind.behavior.clear_plants", this::sendWorkerBehavior);
        workerWander = behaviorBox(workerWidgets, BORDER_ROW + 3, "screen.projecthivemind.behavior.wander", this::sendWorkerBehavior);
        // Flatten the ground: the box, and at the end of its row the block that fills the gaps (chosen from a list).
        flattenGround = behaviorBox(workerWidgets, BORDER_ROW + 4, "screen.projecthivemind.behavior.flatten_ground", this::sendWorkerBehavior);
        fillButton = addRenderableWidget(new SeedButton(leftPos + imageWidth - 12 - 22, topPos + BEHAVIOR_TOP + (BORDER_ROW + 4) * BEHAVIOR_ROW - 2, 20, 20,
                this::currentFill, button -> openFillPicker()));
        fillButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.behavior.flatten_ground.tooltip")));
        workerWidgets.add(fillButton);
        // The same, inside the team area around the team scout; it fills with the same block.
        flattenTeam = behaviorBox(workerWidgets, BORDER_ROW + 5, "screen.projecthivemind.behavior.flatten_team", this::sendWorkerBehavior);
        flattenTeam.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.behavior.flatten_team.tooltip")));
        // Woodwork (its heading is row WOODWORK_ROW).
        chopLogs = behaviorBox(workerWidgets, WOODWORK_ROW + 1, "screen.projecthivemind.behavior.chop_logs", this::sendWorkerBehavior);
        workerRadii[1] = radiusBox(workerWidgets, WOODWORK_ROW + 1, 1, this::sendWorkerBehavior);
        fellTrees = behaviorBox(workerWidgets, WOODWORK_ROW + 2, "screen.projecthivemind.behavior.fell_trees", this::sendWorkerBehavior);

        // Feeders: channelling on crops (with bone meal, which only counts while channelling), channelling on saplings, and composters:
        // the box, and at the end of its row the item put in them (chosen from what can be composted).
        channelCrops = behaviorBox(feederWidgets, 0, "screen.projecthivemind.behavior.channel_crops", this::sendFeederBehavior);
        channelSaplings = behaviorBox(feederWidgets, 1, "screen.projecthivemind.behavior.channel_saplings", this::sendFeederBehavior);
        useBoneMeal = behaviorBox(feederWidgets, 2, "screen.projecthivemind.behavior.use_bone_meal", this::sendFeederBehavior);
        useComposter = behaviorBox(feederWidgets, 3, "screen.projecthivemind.behavior.use_composter", this::sendFeederBehavior);
        compostButton = addRenderableWidget(new SeedButton(leftPos + imageWidth - 12 - 22, topPos + BEHAVIOR_TOP + 3 * BEHAVIOR_ROW - 2, 20, 20,
                this::currentCompost, button -> openCompostPicker()));
        compostButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.behavior.use_composter.tooltip")));
        feederWidgets.add(compostButton);

        // Collectors: two planting tasks, crops and saplings. For each, the item (picked from a list) and the soil blocks it is
        // planted on (set in the world). Their rows: the item, how many spots, and a button to clear them.
        int labelEnd = font.width(Component.translatable("screen.projecthivemind.collector.seed")) + 8;
        seedButton = addRenderableWidget(new SeedButton(leftPos + BEHAVIOR_X + 4 + labelEnd, topPos + CROP_ROW_Y, 20, 20,
                () -> currentPlant(HiveCollector.PlantKind.CROP), button -> openPlantPicker(HiveCollector.PlantKind.CROP)));
        seedButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.collector.seed.tooltip")));
        collectorWidgets.add(seedButton);
        clearSpotButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.collector.clear_spots"),
                button -> askToClearSpots(HiveCollector.PlantKind.CROP)).bounds(leftPos + imageWidth - 12 - 44, topPos + CROP_ROW_Y + 2, 44, 16).build());
        collectorWidgets.add(clearSpotButton);
        int saplingLabelEnd = font.width(Component.translatable("screen.projecthivemind.collector.sapling")) + 8;
        saplingButton = addRenderableWidget(new SeedButton(leftPos + BEHAVIOR_X + 4 + saplingLabelEnd, topPos + SAPLING_ROW_Y, 20, 20,
                () -> currentPlant(HiveCollector.PlantKind.SAPLING), button -> openPlantPicker(HiveCollector.PlantKind.SAPLING)));
        saplingButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.collector.sapling.tooltip")));
        collectorWidgets.add(saplingButton);
        clearSaplingSpotsButton = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.collector.clear_spots"),
                button -> askToClearSpots(HiveCollector.PlantKind.SAPLING)).bounds(leftPos + imageWidth - 12 - 44, topPos + SAPLING_ROW_Y + 2, 44, 16).build());
        collectorWidgets.add(clearSaplingSpotsButton);
        collectorPickUp = behaviorBox(collectorWidgets, 0, "screen.projecthivemind.behavior.collector_pickup", this::sendCollectorBehavior);

        // Scouts: the two things they may do on their own, each with its radius.
        pickUpItems = behaviorBox(scoutWidgets, 0, "screen.projecthivemind.behavior.scout_pickup", this::sendScoutBehavior);
        scoutRadii[0] = radiusBox(scoutWidgets, 0, 0, this::sendScoutBehavior);
        fleeHostiles = behaviorBox(scoutWidgets, 1, "screen.projecthivemind.behavior.scout_flee", this::sendScoutBehavior);
        scoutRadii[1] = radiusBox(scoutWidgets, 1, 1, this::sendScoutBehavior);
        scoutStay = behaviorBox(scoutWidgets, 2, "screen.projecthivemind.behavior.stay_inside", this::sendScoutBehavior);
        scoutWander = behaviorBox(scoutWidgets, 3, "screen.projecthivemind.behavior.wander", this::sendScoutBehavior);
        scoutSneak = behaviorBox(scoutWidgets, 4, "screen.projecthivemind.behavior.scout_sneak", this::sendScoutBehavior);

        setBehaviorEnabled(false);
        filling = false;
    }

    /** The number field at the right of an option's row: how far that option reaches. Reports each valid number typed. */
    private EditBox radiusBox(List<AbstractWidget> group, int row, int index, Runnable onChange) {
        EditBox box = addRenderableWidget(new EditBox(font, leftPos + imageWidth - 12 - 34, topPos + BEHAVIOR_TOP + row * BEHAVIOR_ROW - 1, 32, 16,
                Component.translatable("screen.projecthivemind.behavior.radius")));
        box.setMaxLength(2);
        box.setFilter(text -> text.matches("\\d*"));
        box.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.behavior.radius.tooltip")));
        box.setResponder(text -> {
            if (!text.isEmpty() && !filling && behaviorLoaded) {
                onChange.run();
            }
        });
        group.add(box);
        return box;
    }

    /** What a radius box says, or 0 when it is empty. */
    private static int number(EditBox box) {
        return box.getValue().isEmpty() ? 0 : Integer.parseInt(box.getValue());
    }


    private Checkbox behaviorBox(List<AbstractWidget> group, int row, String key, Runnable onChange) {
        Checkbox box = Checkbox.builder(Component.translatable(key), font)
                .maxWidth(imageWidth - BEHAVIOR_X - 12)
                .onValueChange((checkbox, value) -> onChange.run())
                .build();
        box.setPosition(leftPos + BEHAVIOR_X, topPos + BEHAVIOR_TOP + row * BEHAVIOR_ROW);
        group.add(box);
        return addRenderableWidget(box);
    }

    private void setBehaviorEnabled(boolean enabled) {
        for (List<AbstractWidget> group : List.of(soldierWidgets, workerWidgets, collectorWidgets, scoutWidgets, feederWidgets)) {
            for (AbstractWidget widget : group) {
                if (widget instanceof EditBox editBox) {
                    editBox.setEditable(enabled);
                } else {
                    widget.active = enabled;
                }
            }
        }
        if (enabled && useBoneMeal != null) {
            useBoneMeal.active = channelCrops.selected() || channelSaplings.selected();
        }
    }

    /** Fill the widgets of the open page from the viewed unit's real settings, once, and let the player edit them. */
    private void loadBehavior() {
        int flags = menu.viewFlags();
        int[] radii = {menu.viewRadius(0), menu.viewRadius(1), menu.viewRadius(2), menu.viewRadius(3)};
        filling = true;
        switch (unitPage) {
            case SOLDIER -> {
                SoldierBehavior soldier = SoldierBehavior.from(flags, radii);
                setChecked(allInHiveArea, soldier.allInHiveArea());
                setChecked(hostileInHiveArea, soldier.hostileInHiveArea());
                setChecked(soldierStay, soldier.stayInside());
                setChecked(soldierWander, soldier.wander());
            }
            case WORKER -> {
                WorkerBehavior worker = WorkerBehavior.from(flags, radii);
                setChecked(mineOre, worker.mineOre());
                setChecked(chopLogs, worker.chopLogs());
                setChecked(digThrough, worker.digThrough());
                setChecked(workerStay, worker.stayInside());
                setChecked(harvestCrops, worker.harvestCrops());
                setChecked(clearPlants, worker.clearPlants());
                setChecked(workerWander, worker.wander());
                setChecked(flattenGround, worker.flattenGround());
                setChecked(flattenTeam, worker.flattenTeam());
                setChecked(fellTrees, worker.fellTrees());
                setChecked(workerFlee, worker.fleeHostiles());
                int[] values = worker.radii();
                for (int i = 0; i < workerRadii.length; i++) {
                    workerRadii[i].setValue(String.valueOf(values[i]));
                }
            }
            case FEEDER -> {
                FeederBehavior feeder = FeederBehavior.from(flags, radii);
                setChecked(channelCrops, feeder.channelCrops());
                setChecked(channelSaplings, feeder.channelSaplings());
                setChecked(useBoneMeal, feeder.useBoneMeal());
                setChecked(useComposter, feeder.useComposter());
            }
            case COLLECTOR -> {
                // The tasks come with the unit list; the tick box is a setting.
                setChecked(collectorPickUp, (flags & 1) != 0);
            }
            case SCOUT -> {
                ScoutBehavior scout = ScoutBehavior.from(flags, radii);
                setChecked(pickUpItems, scout.collectItems());
                setChecked(fleeHostiles, scout.fleeHostiles());
                setChecked(scoutStay, scout.stayInside());
                setChecked(scoutWander, scout.wander());
                setChecked(scoutSneak, scout.sneak());
                int[] values = scout.radii();
                for (int i = 0; i < scoutRadii.length; i++) {
                    scoutRadii[i].setValue(String.valueOf(values[i]));
                }
            }
        }
        filling = false;
        behaviorLoaded = true;
        setBehaviorEnabled(true);
    }

    /** Tick a checkbox to match a value, without that counting as the player clicking it. */
    private static void setChecked(Checkbox box, boolean value) {
        if (box.selected() != value) {
            box.onPress();
        }
    }

    private boolean canSend() {
        return !filling && behaviorLoaded && viewedUnit >= 0;
    }

    private void sendSoldierBehavior() {
        if (canSend()) {
            SoldierBehavior behavior = new SoldierBehavior(allInHiveArea.selected(), hostileInHiveArea.selected(), soldierStay.selected(), soldierWander.selected());
            sendBehavior(behavior.flags(), behavior.radii());
        }
    }

    private void sendWorkerBehavior() {
        if (canSend()) {
            WorkerBehavior behavior = new WorkerBehavior(mineOre.selected(), number(workerRadii[0]), chopLogs.selected(),
                    number(workerRadii[1]), digThrough.selected(), workerStay.selected(), harvestCrops.selected(), clearPlants.selected(), workerWander.selected(), flattenGround.selected(), flattenTeam.selected(), fellTrees.selected(), workerFlee.selected(), number(workerRadii[2]));
            sendBehavior(behavior.flags(), behavior.radii());
        }
    }

    private void sendFeederBehavior() {
        // The bone meal tick belongs to channelling: it can only be changed while that is ticked.
        useBoneMeal.active = channelCrops.selected() || channelSaplings.selected();
        if (canSend()) {
            FeederBehavior behavior = new FeederBehavior(channelCrops.selected(), useBoneMeal.selected(), channelSaplings.selected(), useComposter.selected());
            sendBehavior(behavior.flags(), behavior.radii());
        }
    }

    private void sendCollectorBehavior() {
        if (canSend()) {
            sendBehavior(collectorPickUp.selected() ? 1 : 0, new int[4]);
        }
    }

    private void sendScoutBehavior() {
        if (canSend()) {
            ScoutBehavior behavior = new ScoutBehavior(pickUpItems.selected(), number(scoutRadii[0]), fleeHostiles.selected(), number(scoutRadii[1]), scoutStay.selected(), scoutWander.selected(), scoutSneak.selected());
            sendBehavior(behavior.flags(), behavior.radii());
        }
    }


    private void sendBehavior(int flags, int[] radii) {
        List<Integer> list = new ArrayList<>();
        for (int radius : radii) {
            list.add(radius);
        }
        PacketDistributor.sendToServer(new SetUnitBehaviorPayload(viewedUnit, flags, list));
    }

    // ---- clicks and drawing ----

    /**
     * The server ignores vanilla container clicks from spectators, which the bodyless hivemind is. Send the click
     * over our own packet instead; the server applies it and sends the result back.
     */
    @Override
    protected void slotClicked(@Nullable Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot != null) {
            slotId = slot.index;
        }
        PacketDistributor.sendToServer(new HiveMenuClickPayload(menu.containerId, slotId, mouseButton, type));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        mouseXNow = mouseX;
        mouseYNow = mouseY;
        super.render(graphics, mouseX, mouseY, partialTick);
        // The head being dragged to another team follows the cursor.
        SyncUnitsPayload.Entry dragged = dragUnit >= 0 && tab == Tab.TEAM ? ClientUnits.entry(dragUnit) : null;
        if (dragged != null) {
            UnitKind dragKind = UnitKind.values()[dragged.kind()];
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            HeadIcons.draw(graphics, mouseX - TEAM_HEAD / 2, mouseY - TEAM_HEAD / 2, mouseX + TEAM_HEAD / 2, mouseY + TEAM_HEAD / 2, unitEntity(dragUnit, dragKind), dragKind);
            graphics.pose().popPose();
        } else {
            dragUnit = -1;
        }
        if (tab == Tab.HIVE) {
            renderUnitIcons(graphics, mouseX, mouseY);
            net.minecraft.world.inventory.Slot trash = menu.trashSlot();
            if (mouseX >= leftPos + trash.x && mouseX < leftPos + trash.x + 16 && mouseY >= topPos + trash.y && mouseY < topPos + trash.y + 16) {
                graphics.renderTooltip(font, Component.translatable("screen.projecthivemind.hive.trash"), mouseX, mouseY);
            }
        }
        if (tab == Tab.EVOLVE) {
            renderEvolveTooltips(graphics, mouseX, mouseY);
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, PANEL_EDGE);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);

        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                int x = leftPos + slot.x;
                int y = topPos + slot.y;
                graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_EDGE);
                boolean trash = slot == menu.trashSlot();
                graphics.fill(x, y, x + 16, y + 16, trash ? 0xFF4A1818 : SLOT_FILL);
                if (trash) {
                    // The trash: a reddish slot with a cross, so it reads as where things are thrown away.
                    graphics.drawString(font, "\u00D7", x + 8 - font.width("\u00D7") / 2, y + 4, 0xFFAA5555, false);
                }
            }
        }

        // The furnace's flame (burning fuel left) between its input and fuel slots, and its arrow (how far the item has cooked).
        if (tab == Tab.HIVE && furnaceShown && !brewingShown && menu.hasFurnace()) {
            int flameX = leftPos + HiveMenu.FURNACE_INPUT_X + 4;
            int flameY = topPos + (HiveMenu.FURNACE_INPUT_Y + HiveMenu.FURNACE_FUEL_Y) / 2 + 1;
            graphics.fill(flameX, flameY, flameX + 8, flameY + 14, 0xFF3A2A18);
            int burn = Math.round(14 * menu.furnaceBurn());
            graphics.fill(flameX, flameY + 14 - burn, flameX + 8, flameY + 14, 0xFFE8741A);
            int arrowX = leftPos + HiveMenu.FURNACE_INPUT_X + 26;
            int arrowY = topPos + HiveMenu.FURNACE_INPUT_Y + 6;
            graphics.fill(arrowX, arrowY, arrowX + 24, arrowY + 6, 0xFF3A2A18);
            graphics.fill(arrowX, arrowY, arrowX + Math.round(24 * menu.furnaceProgress()), arrowY + 6, 0xFFE0E0E0);
        }

        // The brewing stand's progress: the bubbling bar under the ingredient, and the blaze powder's charges beside the fuel.
        if (tab == Tab.HIVE && brewingShown && menu.hasBrewing()) {
            int barX = leftPos + HiveMenu.BREW_INGREDIENT_X + 6;
            int barY = topPos + HiveMenu.BREW_INGREDIENT_Y + 22;
            graphics.fill(barX, barY, barX + 4, barY + 28, 0xFF3A2A18);
            int done = Math.round(28 * (1.0F - menu.brewTime() / (float) com.projecthivemind.HiveBrewing.BREW_TICKS));
            if (menu.brewTime() > 0) {
                graphics.fill(barX, barY + 28 - done, barX + 4, barY + 28, 0xFF6FCBE8);
            }
            int fuelX = leftPos + HiveMenu.BREW_FUEL_X;
            int fuelY = topPos + HiveMenu.BREW_FUEL_Y + 20;
            graphics.fill(fuelX, fuelY, fuelX + 18, fuelY + 4, 0xFF3A2A18);
            graphics.fill(fuelX, fuelY, fuelX + Math.round(18 * menu.brewFuel() / (float) com.projecthivemind.HiveBrewing.FUEL_CHARGES), fuelY + 4, 0xFFE8741A);
        }

        if (tab == Tab.HIVE) {
            StorageScroll scroll = menu.storageScroll();
            HiveStyle.scrollbar(graphics, leftPos + HiveMenu.STORAGE_X + 9 * 18 + 1, topPos + HiveMenu.STORAGE_Y,
                    scroll.visibleRows() * 18, scroll.totalRows(), scroll.visibleRows(), scroll.row());
        }
    }

    /**
     * While the search box has the keyboard, keys are for typing: otherwise the inventory key (E) would close the menu on every E typed.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (storageSearch != null && storageSearch.visible && storageSearch.isFocused() && keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            return storageSearch.keyPressed(keyCode, scanCode, modifiers) || storageSearch.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** The mouse wheel over the hive storage scrolls it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        StorageScroll scroll = menu.storageScroll();
        if (tab == Tab.HIVE && scroll.maxRow() > 0 && mouseX >= leftPos + HiveMenu.STORAGE_X && mouseX < leftPos + HiveMenu.STORAGE_X + 9 * 18 + 6
                && mouseY >= topPos + HiveMenu.STORAGE_Y && mouseY < topPos + HiveMenu.STORAGE_Y + scroll.visibleRows() * 18) {
            int row = HiveStyle.scrolledRow(scroll.row(), scrollY, scroll.maxRow());
            if (row != scroll.row()) {
                PacketDistributor.sendToServer(new ScrollStoragePayload(menu.containerId, row));
            }
            return true;
        }
        if (tab == Tab.EVOLVE && evolveDoneRows() > evolveVisibleRows() && mouseY >= topPos + evolveDoneTop()) {
            evolveScroll = Math.max(0, Math.min(evolveDoneRows() - evolveVisibleRows(), evolveScroll - (int) Math.signum(scrollY)));
            return true;
        }
        if (tab == Tab.PORTALS && portalTarget != PORTAL_LIST && !summonGrid.isEmpty() && mouseX >= leftPos && mouseX < leftPos + imageWidth
                && mouseY >= topPos + PORTAL_TOP && mouseY < topPos + imageHeight - PORTAL_BUTTONS_HEIGHT) {
            portalScroll -= (int) Math.signum(scrollY);
            layoutSummonGrid();
            return true;
        }
        if (tab == Tab.UNITS && shownUnits.size() > unitListVisible() && mouseX >= leftPos + UNIT_LIST_X && mouseX < leftPos + UNIT_LIST_X + UNIT_HEAD + 6
                && mouseY >= topPos + UNIT_LIST_TOP && mouseY < topPos + UNIT_LIST_TOP + unitListVisible() * (UNIT_HEAD + 4)) {
            unitListScroll = Math.max(0, Math.min(shownUnits.size() - unitListVisible(), unitListScroll - (int) Math.signum(scrollY)));
            layoutUnitButtons();
            return true;
        }
        if (tab == Tab.UNITS && viewedUnit >= 0 && maxBehaviorScroll() > 0 && mouseX >= leftPos + BEHAVIOR_X && mouseX < leftPos + imageWidth
                && mouseY >= topPos + BEHAVIOR_TOP - 4 && mouseY < topPos + behaviorViewBottom()) {
            scrollBehavior(-(int) Math.signum(scrollY) * BEHAVIOR_ROW);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.header", menu.level()), titleLabelX, titleLabelY, 0xFFFFFF, false);

        switch (tab) {
            case QUESTS -> renderQuests(graphics);
            case UNITS -> renderUnitPage(graphics);
            case TEAM -> renderTeam(graphics);
            case PORTALS -> renderPortals(graphics);
            case EVOLVE -> renderEvolve(graphics);
            default -> renderHiveLabels(graphics);
        }
    }

    /**
     * The collector page: a note, then a row for crops and one for saplings, each with the label of its item (the button is
     * a widget) and how many spots it has. The spots themselves are only shown in the world, as particles, not as coordinates.
     */
    private void renderCollectorTask(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.collector_note"),
                BEHAVIOR_X + 4, BEHAVIOR_TOP + 4, 0x909090, false);
        SyncUnitsPayload.Entry entry = ClientUnits.entry(viewedUnit);
        int crops = entry == null ? 0 : entry.task().spots().size();
        int saplings = entry == null ? 0 : entry.task().saplingSpots().size();
        graphics.drawString(font, Component.translatable("screen.projecthivemind.collector.seed"), BEHAVIOR_X + 4, CROP_ROW_Y + 6, 0xE0E0E0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.collector.spots", crops, HiveCollector.MAX_PLANT_SPOTS),
                BEHAVIOR_X + 150, CROP_ROW_Y + 6, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.collector.sapling"), BEHAVIOR_X + 4, SAPLING_ROW_Y + 6, 0xE0E0E0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.collector.spots", saplings, HiveCollector.MAX_PLANT_SPOTS),
                BEHAVIOR_X + 150, SAPLING_ROW_Y + 6, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.collector.spot_hint"),
                BEHAVIOR_X + 4, SAPLING_ROW_Y + 30, 0x909090, false);
    }


    /** A unit page: how many of the kind are out, and the labels of the settings (or that there is none). */
    private void renderUnitPage(GuiGraphics graphics) {
        renderUnitListScrollbar(graphics);
        String countKey = "screen.projecthivemind.hive." + unitPage.name().toLowerCase(Locale.ROOT) + "s";
        boolean atLimit = menu.unitCount(unitPage) >= menu.unitCap(unitPage);
        graphics.drawString(font, Component.translatable(countKey, menu.unitCount(unitPage), menu.unitCap(unitPage)),
                UNIT_LIST_X, UNIT_LIST_TOP - 14, atLimit ? 0xFFAA00 : 0xFFFFFF, false);
        if (viewedUnit < 0) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.unit.none"), BEHAVIOR_X + 4, BEHAVIOR_TOP + 4, 0x909090, false);
            return;
        }
        SyncUnitsPayload.Entry viewed = ClientUnits.entry(viewedUnit);
        if (viewed != null) {
            HeartsBar.draw(graphics, BEHAVIOR_X + 4, HEALTH_Y, viewed.health(), viewed.maxHealth());
        }
        if (unitPage == UnitKind.SOLDIER || unitPage == UnitKind.WORKER) {
            SyncUnitsPayload.Entry entry = ClientUnits.entry(viewedUnit);
            Component job = entry != null && entry.hasJob() ? entry.job().copy().append(entry.paused() ? Component.translatable("screen.projecthivemind.job.paused") : Component.empty())
                    : Component.translatable("screen.projecthivemind.job.none");
            graphics.drawString(font, Component.translatable("screen.projecthivemind.job.title", job), BEHAVIOR_X + 4, JOB_TOP, 0xE0E0E0, false);
        }
        if (unitPage != UnitKind.COLLECTOR && unitPage != UnitKind.SOLDIER && unitPage != UnitKind.FEEDER) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.radius"), imageWidth - 12 - 34, BEHAVIOR_TOP - 12, 0xA0A0A0, false);
        }
        switch (unitPage) {
            case COLLECTOR -> renderCollectorTask(graphics);
            case SCOUT -> graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.scout_note"),
                    BEHAVIOR_X + 4, BEHAVIOR_TOP + 5 * BEHAVIOR_ROW + 4, 0x909090, false);
            case WORKER -> renderWorkerNote(graphics);
            default -> {
            }
        }
    }

    // ---- the Team tab ----

    /** Where the first team's block starts, and how tall each block is: a heading line, then the units' heads in one row. */
    private static final int TEAM_TOP = 98 - 14;
    private static final int TEAM_BLOCK = 44;
    private static final int TEAM_HEAD = 20;
    private static final int TEAM_STEP = TEAM_HEAD + 2;
    /** What the Team tab last showed: each unit's id and team, and how many teams there were. */
    private final List<Integer> shownTeam = new ArrayList<>();
    /** The unit whose head is being dragged to another team, or -1. */
    private int dragUnit = -1;
    private int mouseXNow;
    private int mouseYNow;

    /** The blocks of the tab: a team each, then one for the units in no team. */
    private int teamBlocks() {
        return ClientTeams.count() + 1;
    }

    /** Which block of the Team tab this point (in the panel's own coordinates) is over, or -1. */
    private int teamBlockAt(double x, double y) {
        if (x < UNIT_LIST_X - 4 || x > imageWidth - 8 || y < TEAM_TOP) {
            return -1;
        }
        int block = (int) ((y - TEAM_TOP) / TEAM_BLOCK);
        return block < teamBlocks() ? block : -1;
    }

    /**
     * The Team tab: a row for each team the hive has, with the heads of its units, and a last row for the units in no team. Dragging a head
     * from one row to another moves that unit; dropping it on the last row takes it out of its team. Each team's row also has the slider for
     * how far its units keep around the team's scout. Rebuilt whenever a unit joins, leaves, appears or is lost.
     */
    private void refreshTeam(boolean force) {
        // Collectors and feeders cannot be in a team, so they are not listed.
        List<SyncUnitsPayload.Entry> all = ClientUnits.all().stream().filter(entry -> !UnitKind.values()[entry.kind()].passive()).toList();
        int teams = ClientTeams.count();
        List<Integer> key = new ArrayList<>();
        key.add(teams);
        for (SyncUnitsPayload.Entry entry : all) {
            key.add(entry.entityId() * 16 + entry.teamIndex() + 1);
        }
        if (!force && key.equals(shownTeam)) {
            return;
        }
        clearUnitButtons();
        shownTeam.clear();
        shownTeam.addAll(key);
        int columns = Math.max(1, (imageWidth - 24) / TEAM_STEP);
        int[] placed = new int[teams + 1];
        for (SyncUnitsPayload.Entry entry : all) {
            UnitKind kind = UnitKind.values()[entry.kind()];
            int id = entry.entityId();
            int block = entry.teamIndex() >= 0 && entry.teamIndex() < teams ? entry.teamIndex() : teams;
            int index = placed[block]++;
            int x = leftPos + UNIT_LIST_X + (index % columns) * TEAM_STEP;
            int y = topPos + TEAM_TOP + block * TEAM_BLOCK + 16 + (index / columns) * TEAM_STEP;
            Component name = Component.translatable("screen.projecthivemind.unit.numbered",
                    Component.translatable("unit.projecthivemind." + kind.name().toLowerCase(Locale.ROOT)), ClientUnits.ofKind(kind).indexOf(id) + 1);
            // Pressing a head picks it up; letting go over a row (see mouseReleased) puts it there.
            UnitIconButton button = new UnitIconButton(x, y, TEAM_HEAD, TEAM_HEAD, name, () -> unitEntity(id, kind), kind, () -> dragUnit == id,
                    pressed -> dragUnit = id);
            button.setTooltip(Tooltip.create(name.copy().append(Component.translatable("screen.projecthivemind.team.drag"))));
            unitButtons.add(addRenderableWidget(button));
        }
        // How far around its scout each team keeps together (the blue ring of flames round the scout), and beside it how far its soldiers go after
        // hostile mobs (the yellow ring).
        for (int team = 0; team < teams; team++) {
            int index = team;
            int sliderY = topPos + TEAM_TOP + team * TEAM_BLOCK - 3;
            TeamAreaSlider slider = new TeamAreaSlider(leftPos + UNIT_LIST_X + 52, sliderY, 140, 14, ClientTeams.radius(team),
                    com.projecthivemind.entity.HiveTeams.MIN_RADIUS, com.projecthivemind.entity.HiveTeams.MAX_RADIUS, "screen.projecthivemind.team.area",
                    radius -> PacketDistributor.sendToServer(new SetTeamRadiusPayload(index, radius, false)));
            slider.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.team.area.tooltip")));
            unitButtons.add(addRenderableWidget(slider));
            TeamAreaSlider attackSlider = new TeamAreaSlider(leftPos + UNIT_LIST_X + 196, sliderY, 140, 14, ClientTeams.attackRadius(team),
                    com.projecthivemind.entity.HiveTeams.MIN_RADIUS, com.projecthivemind.entity.HiveTeams.MAX_ATTACK_RADIUS, "screen.projecthivemind.team.attack_area",
                    radius -> PacketDistributor.sendToServer(new SetTeamRadiusPayload(index, radius, true)));
            attackSlider.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.team.attack_area.tooltip")));
            unitButtons.add(addRenderableWidget(attackSlider));
        }
    }

    /** Letting go of a dragged head over a row of the Team tab moves the unit to that team (or out of them, over the last row). */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragUnit >= 0 && button == 0) {
            int unit = dragUnit;
            dragUnit = -1;
            int block = teamBlockAt(mouseX - leftPos, mouseY - topPos);
            SyncUnitsPayload.Entry entry = ClientUnits.entry(unit);
            if (block >= 0 && entry != null) {
                int team = block < ClientTeams.count() ? block : -1;
                if (team != entry.teamIndex()) {
                    PacketDistributor.sendToServer(new SetUnitTeamPayload(unit, team));
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ---- the Evolve tab ----

    private static final int EVOLVE_TOP = 98;
    private static final int EVOLVE_ROW = 22;
    private static final int EVOLVE_COLUMN = 176;
    /** The step between the icons of the tasks to do: 16 of them fit across the panel. */
    private static final int EVOLVE_STEP = 18;
    /** How many rows of the Completed list are scrolled off the top. */
    private int evolveScroll;

    /** How many rows of the Completed list fit under its heading. */
    private int evolveVisibleRows() {
        return Math.max(1, (imageHeight - 12 - evolveDoneTop()) / 20);
    }

    /** How many rows the Completed list has in all. */
    private int evolveDoneRows() {
        int done = 0;
        for (com.projecthivemind.EvolveTask task : com.projecthivemind.EvolveTask.values()) {
            done += task.doneIn(menu.evolveMask()) ? 1 : 0;
        }
        return (done + 1) / 2;
    }

    /** One task's icon on the Evolve tab: whether it is done, whether the hive can do it now, and where it is (in the panel's own coordinates). */
    private record EvolveIcon(com.projecthivemind.EvolveTask task, boolean done, boolean ready, int x, int y) {
    }

    /** How many tasks fit in a row under "Tasks": as many as the panel is wide. */
    private int evolveColumns() {
        return Math.max(1, (imageWidth - 24) / EVOLVE_STEP);
    }

    /** How many rows the tasks still to do take. */
    private int evolveTaskRows() {
        int todo = 0;
        for (com.projecthivemind.EvolveTask task : com.projecthivemind.EvolveTask.values()) {
            todo += task.doneIn(menu.evolveMask()) ? 0 : 1;
        }
        return Math.max(1, (todo + evolveColumns() - 1) / evolveColumns());
    }

    /** Where the Completed list starts: under the rows of tasks still to do. */
    private int evolveDoneTop() {
        return EVOLVE_TOP + 24 + evolveTaskRows() * EVOLVE_STEP;
    }

    /**
     * The icons of the tab: the tasks still to do side by side under "Tasks", with those the hive can do now (its storage has the item) first,
     * and those done in two columns under "Completed".
     */
    private List<EvolveIcon> evolveIcons() {
        List<EvolveIcon> icons = new ArrayList<>();
        int mask = menu.evolveMask();
        int readyMask = menu.evolveReady();
        int todo = 0;
        int done = 0;
        for (boolean readyFirst : new boolean[] {true, false}) {
            for (com.projecthivemind.EvolveTask task : com.projecthivemind.EvolveTask.values()) {
                boolean ready = !task.doneIn(mask) && (readyMask & task.bit()) != 0;
                if (!task.doneIn(mask) && ready == readyFirst) {
                    icons.add(new EvolveIcon(task, false, ready, UNIT_LIST_X + (todo % evolveColumns()) * EVOLVE_STEP, EVOLVE_TOP + (todo++ / evolveColumns()) * EVOLVE_STEP));
                }
            }
        }
        for (com.projecthivemind.EvolveTask task : com.projecthivemind.EvolveTask.values()) {
            if (task.doneIn(mask)) {
                // Two columns: the next one goes to the right, then back to the left on the next row. Only the rows in view are there.
                int row = done / 2;
                if (row >= evolveScroll && row < evolveScroll + evolveVisibleRows()) {
                    icons.add(new EvolveIcon(task, true, false, UNIT_LIST_X + (done % 2) * EVOLVE_COLUMN, evolveDoneTop() + (row - evolveScroll) * 20));
                }
                done++;
            }
        }
        return icons;
    }

    private void renderEvolve(GuiGraphics graphics) {
        evolveScroll = Math.max(0, Math.min(evolveScroll, evolveDoneRows() - evolveVisibleRows()));
        // A thin bar beside the Completed list when it has more rows than fit.
        if (evolveDoneRows() > evolveVisibleRows()) {
            int top = evolveDoneTop();
            int height = evolveVisibleRows() * 20;
            int thumb = Math.max(10, height * evolveVisibleRows() / evolveDoneRows());
            int thumbY = top + (height - thumb) * evolveScroll / (evolveDoneRows() - evolveVisibleRows());
            graphics.fill(imageWidth - 8, top, imageWidth - 6, top + height, 0x44000000);
            graphics.fill(imageWidth - 8, thumbY, imageWidth - 6, thumbY + thumb, 0xFFC0C0C0);
        }
        List<EvolveIcon> icons = evolveIcons();
        graphics.drawString(font, Component.translatable("screen.projecthivemind.evolve.tasks"), UNIT_LIST_X, EVOLVE_TOP - 14, 0xFFFFFF, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.evolve.completed"), UNIT_LIST_X, evolveDoneTop() - 14, 0xFFFFFF, false);
        if (icons.stream().allMatch(EvolveIcon::done)) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.evolve.no_tasks"), UNIT_LIST_X + 4, EVOLVE_TOP + 4, 0x909090, false);
        }
        if (icons.stream().noneMatch(EvolveIcon::done)) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.evolve.none_done"), UNIT_LIST_X + 4, evolveDoneTop() + 4, 0x909090, false);
        }
        for (EvolveIcon icon : icons) {
            // A task the hive can do now is highlighted in green.
            graphics.fill(icon.x() - 1, icon.y() - 1, icon.x() + 17, icon.y() + 17, icon.ready() ? 0xFF55FF55 : SLOT_EDGE);
            graphics.fill(icon.x(), icon.y(), icon.x() + 16, icon.y() + 16, icon.ready() ? 0xFF2E6B2E : SLOT_FILL);
            graphics.renderItem(new ItemStack(icon.task().item()), icon.x(), icon.y());
            if (icon.done()) {
                // What it gave, to the right of it.
                graphics.drawString(font, Component.translatable("screen.projecthivemind.evolve.reward." + icon.task().name().toLowerCase(Locale.ROOT)),
                        icon.x() + 24, icon.y() + 4, 0xA0E0A0, false);
            }
        }
    }

    /**
     * Hovering over a task still to do names the item, and says under it either "Click to Consume" (green: the hive has it) or "Requirements not
     * met" (red). Drawn after the rest of the screen, so it is in the screen's own coordinates.
     */
    private void renderEvolveTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        for (EvolveIcon icon : evolveIcons()) {
            int x = leftPos + icon.x();
            int y = topPos + icon.y();
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                ItemStack stack = new ItemStack(icon.task().item());
                if (icon.done()) {
                    graphics.renderTooltip(font, stack, mouseX, mouseY);
                } else {
                    graphics.renderComponentTooltip(font, List.of(stack.getHoverName(), icon.ready()
                            ? Component.translatable("screen.projecthivemind.evolve.click").withStyle(net.minecraft.ChatFormatting.GREEN)
                            : Component.translatable("screen.projecthivemind.evolve.unmet").withStyle(net.minecraft.ChatFormatting.RED)), mouseX, mouseY);
                }
            }
        }
    }

    /** Clicking a task the hive can do consumes its item from the hive's storage. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == Tab.EVOLVE && button == 0) {
            for (EvolveIcon icon : evolveIcons()) {
                int x = leftPos + icon.x();
                int y = topPos + icon.y();
                if (icon.ready() && mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                    PacketDistributor.sendToServer(new com.projecthivemind.network.ConsumeEvolvePayload(menu.containerId, icon.task().ordinal()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---- the portal network ----

    /** The Portals tab is on the list of portals (as opposed to the page for choosing units to summon to one). */
    private static final int PORTAL_LIST = -2;
    private static final int PORTAL_TOP = 98;
    /** Room kept under the grid for the Back and Summon buttons. */
    private static final int PORTAL_BUTTONS_HEIGHT = 36;

    /** Where the Portals tab is: the list, the Hive Heart (-1) or the portal with this index. */
    private int portalTarget = PORTAL_LIST;
    private final Set<Integer> summonChoice = new LinkedHashSet<>();
    private final List<UnitIconButton> summonGrid = new ArrayList<>();
    private Button summonButton;
    private int portalScroll;
    private List<Object> portalKey = List.of();

    private List<SyncUnitsPayload.Entry> summonable() {
        // Collectors cannot be summoned.
        return ClientUnits.all().stream().filter(entry -> !UnitKind.values()[entry.kind()].passive()).toList();
    }

    private static Component portalName(int target) {
        return target < 0 ? Component.translatable("screen.projecthivemind.portals.heart")
                : Component.translatable("screen.projecthivemind.portals.portal", target + 1);
    }

    private static String dimensionName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dimension.substring(dimension.indexOf(':') + 1);
        };
    }

    private int portalColumns() {
        return Math.max(1, (imageWidth - 24 - 8) / TEAM_STEP);
    }

    private int portalRows() {
        return Math.max(1, (imageHeight - PORTAL_TOP - PORTAL_BUTTONS_HEIGHT) / TEAM_STEP);
    }

    /** Build what the Portals tab shows, when something it depends on has changed (or when forced). */
    private void refreshPortals(boolean force) {
        boolean summoning = ClientPortals.summoning();
        if (portalTarget >= ClientPortals.portals().size()) {
            portalTarget = PORTAL_LIST;
        }
        List<Object> key = new ArrayList<>(List.of(summoning, portalTarget, ClientPortals.portals().size(), ClientPortals.max()));
        if (portalTarget != PORTAL_LIST && !summoning) {
            summonable().forEach(entry -> key.add(entry.entityId()));
        }
        if (!force && key.equals(portalKey)) {
            return;
        }
        portalKey = key;
        clearUnitButtons();
        summonGrid.clear();
        summonButton = null;
        if (summoning) {
            return;
        }
        int x = leftPos + UNIT_LIST_X;
        if (portalTarget == PORTAL_LIST) {
            int y = topPos + PORTAL_TOP;
            Button heart = Button.builder(Component.translatable("screen.projecthivemind.portals.heart").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD),
                    button -> openSummonPage(-1)).bounds(x, y, 220, 24).build();
            heart.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.portals.heart.tooltip")));
            unitButtons.add(addRenderableWidget(heart));
            y += 30;
            for (int i = 0; i < ClientPortals.portals().size() && y + 22 <= topPos + imageHeight - 10; i++) {
                int index = i;
                SyncPortalsPayload.Portal portal = ClientPortals.portals().get(i);
                Component label = Component.translatable("screen.projecthivemind.portals.entry", i + 1, dimensionName(portal.dimension()),
                        portal.pos().getX(), portal.pos().getY(), portal.pos().getZ());
                unitButtons.add(addRenderableWidget(Button.builder(label, button -> openSummonPage(index)).bounds(x, y, 220, 20).build()));
                // To the right of the portal: delete it (after asking).
                Button delete = Button.builder(Component.translatable("screen.projecthivemind.portals.delete").withStyle(net.minecraft.ChatFormatting.RED),
                        button -> askToDeletePortal(index)).bounds(x + 224, y, 50, 20).build();
                delete.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.portals.delete.tooltip")));
                unitButtons.add(addRenderableWidget(delete));
                y += 24;
            }
            return;
        }
        // Choosing the units to summon: a grid of heads, in rows that scroll, with the buttons under it.
        List<SyncUnitsPayload.Entry> entries = summonable();
        for (SyncUnitsPayload.Entry entry : entries) {
            UnitKind kind = UnitKind.values()[entry.kind()];
            int id = entry.entityId();
            Component name = Component.translatable("screen.projecthivemind.unit.numbered",
                    Component.translatable("unit.projecthivemind." + kind.name().toLowerCase(Locale.ROOT)), ClientUnits.ofKind(kind).indexOf(id) + 1);
            UnitIconButton button = new UnitIconButton(x, topPos + PORTAL_TOP, UNIT_HEAD, UNIT_HEAD, name, () -> unitEntity(id, kind), kind,
                    () -> summonChoice.contains(id), pressed -> {
                if (!summonChoice.remove(id)) {
                    summonChoice.add(id);
                }
                updateSummonButton();
            });
            button.setTooltip(Tooltip.create(entry.away() ? name.copy().append(Component.translatable("screen.projecthivemind.unit.away_short")) : name));
            summonGrid.add(button);
            unitButtons.add(addRenderableWidget(button));
        }
        int bottom = topPos + imageHeight - 28;
        unitButtons.add(addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.portals.back"), button -> {
            portalTarget = PORTAL_LIST;
            summonChoice.clear();
            refreshPortals(true);
        }).bounds(x, bottom, 70, 20).build()));
        summonButton = addRenderableWidget(Button.builder(Component.empty(), button -> askToSummon()).bounds(x + 76, bottom, 150, 20).build());
        unitButtons.add(summonButton);
        updateSummonButton();
        layoutSummonGrid();
    }

    /** Deleting a portal cannot be undone, so the player is asked first. Whatever the answer, this screen is shown again. */
    private void askToDeletePortal(int index) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                PacketDistributor.sendToServer(new com.projecthivemind.network.DeletePortalPayload(index));
            }
            minecraft.setScreen(this);
        }, Component.translatable("screen.projecthivemind.portals.delete_title", index + 1),
                Component.translatable("screen.projecthivemind.portals.delete_message"),
                CommonComponents.GUI_YES, CommonComponents.GUI_NO));
    }

    private void openSummonPage(int target) {
        portalTarget = target;
        summonChoice.clear();
        portalScroll = 0;
        refreshPortals(true);
    }

    private void updateSummonButton() {
        if (summonButton != null) {
            summonButton.setMessage(Component.translatable("screen.projecthivemind.portals.summon", summonChoice.size()));
            summonButton.active = !summonChoice.isEmpty();
        }
    }

    /** Put the heads in their rows for the scroll, hiding those that are scrolled out of the area. */
    private void layoutSummonGrid() {
        int columns = portalColumns();
        int rows = portalRows();
        int maxScroll = Math.max(0, (summonGrid.size() + columns - 1) / columns - rows);
        portalScroll = Math.max(0, Math.min(maxScroll, portalScroll));
        for (int i = 0; i < summonGrid.size(); i++) {
            UnitIconButton button = summonGrid.get(i);
            int row = i / columns - portalScroll;
            button.setX(leftPos + UNIT_LIST_X + (i % columns) * TEAM_STEP);
            button.setY(topPos + PORTAL_TOP + row * TEAM_STEP);
            button.visible = row >= 0 && row < rows;
        }
    }

    /** Summoning kills the units, so the player is asked first. Whatever the answer, this screen is shown again. */
    private void askToSummon() {
        if (summonChoice.isEmpty()) {
            return;
        }
        int target = portalTarget;
        List<Integer> ids = List.copyOf(summonChoice);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                PacketDistributor.sendToServer(new com.projecthivemind.network.SummonUnitsPayload(target, ids));
                summonChoice.clear();
                portalTarget = PORTAL_LIST;
            }
            minecraft.setScreen(this);
        }, Component.translatable("screen.projecthivemind.portals.confirm_title", ids.size(), portalName(target)),
                Component.translatable("screen.projecthivemind.portals.confirm_message"),
                CommonComponents.GUI_YES, CommonComponents.GUI_NO));
    }

    private void renderPortals(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.title"), UNIT_LIST_X, PORTAL_TOP - 28, 0xFFFFFF, false);
        if (portalTarget == PORTAL_LIST || ClientPortals.summoning()) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.count", ClientPortals.portals().size(), ClientPortals.max()),
                    UNIT_LIST_X, PORTAL_TOP - 16, 0xA0A0A0, false);
        }
        if (ClientPortals.summoning()) {
            int y = PORTAL_TOP;
            graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.summoning_at", portalName(ClientPortals.summonTarget())),
                    UNIT_LIST_X, y, 0xFFDD55, false);
            y += 14;
            graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.next_in", ClientPortals.secondsToNext()), UNIT_LIST_X, y, 0xE0E0E0, false);
            y += 18;
            graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.waiting"), UNIT_LIST_X, y, 0xA0A0A0, false);
            y += 12;
            int number = 1;
            for (int ordinal : ClientPortals.queue()) {
                if (y + 10 > imageHeight - 8) {
                    graphics.drawString(font, Component.literal("..."), UNIT_LIST_X + 4, y, 0x909090, false);
                    break;
                }
                UnitKind kind = UnitKind.values()[ordinal];
                graphics.drawString(font, Component.literal(number++ + ". ").append(Component.translatable("unit.projecthivemind." + kind.name().toLowerCase(Locale.ROOT))),
                        UNIT_LIST_X + 4, y, 0xE0E0E0, false);
                y += 11;
            }
            return;
        }
        if (portalTarget == PORTAL_LIST) {
            if (ClientPortals.portals().isEmpty()) {
                graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.none"), UNIT_LIST_X, PORTAL_TOP + 34, 0x909090, false);
            }
            return;
        }
        graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.pick", portalName(portalTarget)), UNIT_LIST_X, PORTAL_TOP - 16, 0xFFDD55, false);
        if (summonGrid.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.portals.no_units"), UNIT_LIST_X + 4, PORTAL_TOP + 10, 0x909090, false);
        }
    }

    private void renderTeam(GuiGraphics graphics) {
        int teams = ClientTeams.count();
        int over = dragUnit >= 0 ? teamBlockAt(mouseXNow - leftPos, mouseYNow - topPos) : -1;
        for (int block = 0; block <= teams; block++) {
            int top = TEAM_TOP + block * TEAM_BLOCK;
            if (block == over) {
                graphics.fill(UNIT_LIST_X - 4, top - 2, imageWidth - 8, top + TEAM_BLOCK - 4, 0x40FFFF55);
            }
            boolean last = block == teams;
            graphics.drawString(font, last ? Component.translatable("screen.projecthivemind.team.others") : Component.translatable("screen.projecthivemind.team.title", block + 1),
                    UNIT_LIST_X, top + 1, last ? 0xA0A0A0 : 0xFFFFFF, false);
            final int thisBlock = block;
            boolean empty = ClientUnits.all().stream().filter(entry -> !UnitKind.values()[entry.kind()].passive())
                    .noneMatch(entry -> (entry.teamIndex() >= 0 && entry.teamIndex() < teams ? entry.teamIndex() : teams) == thisBlock);
            if (empty) {
                graphics.drawString(font, Component.translatable(last ? "screen.projecthivemind.team.none_left" : "screen.projecthivemind.team.empty"), UNIT_LIST_X + 4, top + 22, 0x909090, false);
            }
        }
        int y = imageHeight - 46;
        for (String key : new String[] {"rule_follow", "rule_border", "rule_orders"}) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.team." + key), UNIT_LIST_X, y, 0x909090, false);
            y += 12;
        }
    }

    // ---- the Quests tab ----

    private static final int QUEST_X = 12;
    private static final int QUEST_Y = 84;
    private static final int DONE = 0x77DD77;
    private static final int TODO = 0xE0E0E0;

    /** The level-up quest, its progress, and what the next level unlocks. */
    private void renderQuests(GuiGraphics graphics) {
        int level = menu.level();
        HiveLevel.Quest quest = HiveLevels.get(level).quest();
        if (quest == null || !HiveLevels.hasNext(level)) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.quest.max_level"), QUEST_X, QUEST_Y, 0xA0A0A0, false);
            return;
        }
        HiveLevel current = HiveLevels.get(level);
        HiveLevel next = HiveLevels.get(level + 1);
        int y = QUEST_Y;
        graphics.drawString(font, Component.translatable("screen.projecthivemind.quest.title", next.level()), QUEST_X, y, 0xFFDD55, false);
        y += 14;
        // Only the parts this level's quest actually asks for.
        if (quest.logs() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.logs", quest.logs()), menu.questLogs(), quest.logs(), 1, "");
        }
        if (quest.chunks() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.chunks", quest.chunks()), menu.questChunks(), quest.chunks(), 1, "");
        }
        if (quest.kills() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.kills", quest.kills()), menu.questKills(), quest.kills(), 1, "");
        }
        if (quest.survivalTicks() > 0) {
            // Shown in minutes of play: 24000 ticks is a whole day and night, 20 minutes.
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.survive"), menu.questSeconds(), quest.survivalTicks() / 20, 60, " min");
        }
        if (quest.reachY() != null) {
            int lowest = menu.questLowestY();
            boolean reached = lowest <= quest.reachY();
            y = questLineText(graphics, y, Component.translatable("screen.projecthivemind.quest.depth", quest.reachY()), reached,
                    (lowest > 30000 ? "-" : String.valueOf(lowest)) + " / " + quest.reachY());
        }
        if (quest.coal() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.coal", quest.coal()), menu.questCoal(), quest.coal(), 1, "");
        }
        if (quest.ironIngots() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.iron", quest.ironIngots()), menu.questIron(), quest.ironIngots(), 1, "");
        }
        if (quest.nether()) {
            y = questLineText(graphics, y, Component.translatable("screen.projecthivemind.quest.nether"), menu.questNether(), menu.questNether() ? "1 / 1" : "0 / 1");
        }
        if (quest.dragon()) {
            y = questLineText(graphics, y, Component.translatable("screen.projecthivemind.quest.dragon"), menu.questDragon(), menu.questDragon() ? "1 / 1" : "0 / 1");
        }
        if (quest.blazeRods() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.blaze", quest.blazeRods()), menu.questBlaze(), quest.blazeRods(), 1, "");
        }

        y += 8;
        graphics.drawString(font, Component.translatable("screen.projecthivemind.quest.unlocks", next.level()), QUEST_X, y, 0xFFDD55, false);
        y += 14;
        // Only what actually changes at the next level.
        for (UnitKind kind : new UnitKind[] {UnitKind.SOLDIER, UnitKind.WORKER, UnitKind.SCOUT, UnitKind.COLLECTOR, UnitKind.FEEDER}) {
            if (next.cap(kind) != current.cap(kind)) {
                y = unlockLine(graphics, y, Component.translatable("screen.projecthivemind.quest.unlock_cap",
                        Component.translatable("command.projecthivemind." + kind.name().toLowerCase(Locale.ROOT) + "s"), next.cap(kind)));
            }
        }
        if (next.maxHealth() != current.maxHealth()) {
            y = unlockLine(graphics, y, Component.translatable("screen.projecthivemind.quest.unlock_health", hearts((int) next.maxHealth())));
        }
        if (next.storageSlots() != current.storageSlots()) {
            String key = next.storageSlots() > StorageScroll.MAX_VISIBLE ? "screen.projecthivemind.quest.unlock_storage_scroll" : "screen.projecthivemind.quest.unlock_storage";
            y = unlockLine(graphics, y, Component.translatable(key, next.storageSlots()));
        }
        if (next.infectionRadius() != current.infectionRadius()) {
            int size = next.infectionRadius() * 2 + 1;
            unlockLine(graphics, y, Component.translatable("screen.projecthivemind.quest.unlock_area", size, size));
        }
    }

    /** A goal of the quest whose progress is a text, not a count: the lowest height reached. */
    private int questLineText(GuiGraphics graphics, int y, Component text, boolean done, String progress) {
        graphics.drawString(font, done ? "✔" : "•", QUEST_X, y, done ? DONE : TODO, false);
        graphics.drawString(font, text, QUEST_X + 12, y, done ? DONE : TODO, false);
        graphics.drawString(font, progress, imageWidth - 12 - font.width(progress), y, done ? DONE : TODO, false);
        return y + 12;
    }

    /** One goal of the quest: a tick or a dot, what it asks, and how far along it is. Returns the next line's y. */
    private int questLine(GuiGraphics graphics, int y, Component text, int have, int need, int unitDivisor, String unitSuffix) {
        boolean done = have >= need;
        graphics.drawString(font, done ? "✔" : "•", QUEST_X, y, done ? DONE : TODO, false);
        graphics.drawString(font, text, QUEST_X + 12, y, done ? DONE : TODO, false);
        String progress = Math.min(have, need) / unitDivisor + " / " + need / unitDivisor + unitSuffix;
        graphics.drawString(font, progress, imageWidth - 12 - font.width(progress), y, done ? DONE : TODO, false);
        return y + 12;
    }

    private int unlockLine(GuiGraphics graphics, int y, Component text) {
        graphics.drawString(font, "•", QUEST_X, y, 0xA0A0A0, false);
        graphics.drawString(font, text, QUEST_X + 12, y, 0xE0E0E0, false);
        return y + 12;
    }

    /** Where the row of unit counts sits: under the tool row, which moves down as the storage grows. */
    private int countsY() {
        return HiveMenu.toolsY(menu.storageRows()) + 32;
    }

    private void renderHiveLabels(GuiGraphics graphics) {
        for (int column = 0; column < KINDS_IN_COUNTS.length; column++) {
            drawUnitCount(graphics, column, KINDS_IN_COUNTS[column]);
        }

        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.armor"), HiveMenu.ARMOR_X, LABEL_Y, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.storage"), HiveMenu.STORAGE_X, LABEL_Y, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.food"), HiveMenu.ARMOR_X,
                HiveMenu.ARMOR_Y + 4 * 18 + 22, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.scout_hand"), HiveMenu.STORAGE_X + 22,
                HiveMenu.scoutHandY(menu.storageRows()) + 4, 0xA0A0A0, false);
        // The workstation on the right is named for the open tab.
        // (With no workstation unlocked yet there is nothing to name.)
        String workstation = jukeboxShown && menu.hasJukebox() ? "screen.projecthivemind.hive.jukebox"
                : enchantShown && menu.hasEnchanting() ? "screen.projecthivemind.hive.enchanting"
                : brewingShown && menu.hasBrewing() ? "screen.projecthivemind.hive.brewing"
                : furnaceShown && menu.hasFurnace() ? "screen.projecthivemind.hive.furnace"
                : menu.hasCrafting() ? "screen.projecthivemind.hive.crafting" : "screen.projecthivemind.hive.crafting_small";
        graphics.drawString(font, Component.translatable(workstation), HiveMenu.GRID_X, LABEL_Y, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.tools"),
                HiveMenu.TOOLS_X + com.projecthivemind.HiveEquipment.TOOL_SLOTS * 18 + 6, HiveMenu.toolsY(menu.storageRows()) + 5, 0xA0A0A0, false);
    }

    /**
     * "1/1" beside the unit's icon: units out now, out of the most the hive allows (orange at the limit). What the hive's
     * next 10-second interval will do for them is in the icon's popup. The icon itself is drawn in {@link #renderUnitIcons}.
     */
    private void drawUnitCount(GuiGraphics graphics, int column, UnitKind kind) {
        int x = HiveMenu.STORAGE_X + column * COUNTS_SPACING;
        boolean atLimit = menu.unitCount(kind) >= menu.unitCap(kind);
        graphics.drawString(font, menu.unitCount(kind) + "/" + menu.unitCap(kind), x + COUNT_ICON + 4, countsY() + 3,
                atLimit ? 0xFFAA00 : 0xFFFFFF, false);
    }

    /** The size of a unit's icon in the row of counts. */
    private static final int COUNT_ICON = 18;

    /**
     * The icons of the row of unit counts, on the Hive tab: each kind's head in a small box. Hovering over one names the kind of unit in text.
     * Drawn after the rest of the screen, so it is in the screen's own coordinates.
     */
    private void renderUnitIcons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int column = 0; column < KINDS_IN_COUNTS.length; column++) {
            UnitKind kind = KINDS_IN_COUNTS[column];
            int x = leftPos + HiveMenu.STORAGE_X + column * COUNTS_SPACING;
            int y = topPos + countsY();
            graphics.fill(x, y, x + COUNT_ICON, y + COUNT_ICON, 0x66000000);
            HeadIcons.draw(graphics, x + 1, y + 1, x + COUNT_ICON - 1, y + COUNT_ICON - 1, HeadIcons.standIn(kind), kind);
            if (mouseX >= x && mouseX < x + COUNT_ICON && mouseY >= y && mouseY < y + COUNT_ICON) {
                // The kind in text, and under it what the hive's next 10-second interval will do for them, with the countdown.
                boolean spawning = menu.unitStatus(kind) == HiveMenu.STATUS_SPAWNING;
                Component status = spawning ? Component.translatable("screen.projecthivemind.hive.status.spawning", menu.secondsUntilSpawn())
                        : Component.translatable("screen.projecthivemind.hive.status.idle");
                graphics.renderComponentTooltip(font, List.of(Component.translatable("command.projecthivemind." + kind.name().toLowerCase(Locale.ROOT) + "s"),
                        status.copy().withStyle(spawning ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.GRAY)), mouseX, mouseY);
            }
        }
    }

    /** The order of the row of counts: the same as the command bar used to have, then the units you do not command. */
    private static final UnitKind[] KINDS_IN_COUNTS = {UnitKind.SCOUT, UnitKind.SOLDIER, UnitKind.WORKER, UnitKind.COLLECTOR, UnitKind.FEEDER};

    /** Health points as hearts: 2 points per heart, dropping a trailing ".0". */
    private static String hearts(int healthPoints) {
        float hearts = healthPoints / 2.0F;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.format(Locale.ROOT, "%.1f", hearts);
    }
}
