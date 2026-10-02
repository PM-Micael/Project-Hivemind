package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
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
import com.projecthivemind.network.ToggleTeamPayload;
import com.projecthivemind.network.SetTeamRadiusPayload;
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
    private static final int COUNTS_SPACING = 80;

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

    private enum Tab {
        HIVE, QUESTS, UNITS, TEAM
    }

    /** The order of the row of unit buttons: the same as the command bar's keys, then the collectors. */
    private static final UnitKind[] KINDS = {UnitKind.SCOUT, UnitKind.SOLDIER, UnitKind.WORKER, UnitKind.COLLECTOR};

    private Tab tab = Tab.HIVE;
    /** Which kind's page the Units tab is showing. */
    private UnitKind unitPage = UnitKind.SOLDIER;
    private Button hiveTab;
    private Button questsTab;
    private Button teamTab;
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
    private final EditBox[] workerRadii = new EditBox[2];
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
    private Checkbox fleeHostiles;
    private Checkbox scoutStay;
    private Checkbox scoutWander;
    private Checkbox workerWander;
    private Checkbox flattenGround;
    private Checkbox channelCrops;
    private Checkbox useBoneMeal;
    /** How far the unit page's settings are scrolled up, in pixels; and how far the widgets have been moved for it so far. */
    private int behaviorScroll;
    private int appliedScroll;
    private SeedButton fillButton;

    private final List<AbstractWidget> soldierWidgets = new ArrayList<>();
    private final List<AbstractWidget> workerWidgets = new ArrayList<>();
    private final List<AbstractWidget> collectorWidgets = new ArrayList<>();
    private final List<AbstractWidget> scoutWidgets = new ArrayList<>();

    /** True while the widgets are being filled from the server's values, so that does not count as the player editing. */
    private boolean filling;
    /** The widgets show the viewed unit's real settings. Until then they are disabled, so nothing wrong can be sent back. */
    private boolean behaviorLoaded;
    /** The tick box for going back to the viewed unit's set-aside job; true while it is being set from the server's word. */
    private Checkbox jobResumeBox;
    private boolean jobFilling;
    /** Ends the viewed unit's job, after the player has confirmed. */
    private Button cancelJobButton;

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
        hiveTab = tabButton(0, "screen.projecthivemind.hive.tab_hive", Tab.HIVE);
        questsTab = tabButton(1, "screen.projecthivemind.hive.tab_quests", Tab.QUESTS);
        teamTab = tabButton(2, "screen.projecthivemind.hive.tab_team", Tab.TEAM);
        kindTabs.clear();
        for (int i = 0; i < KINDS.length; i++) {
            UnitKind kind = KINDS[i];
            Component name = Component.translatable("command.projecthivemind." + kind.name().toLowerCase(Locale.ROOT) + "s");
            UnitIconButton button = new UnitIconButton(leftPos + 8 + i * 26, topPos + UNIT_TAB_Y, 24, 20, name,
                    () -> HeadIcons.standIn(kind), kind, () -> tab == Tab.UNITS && unitPage == kind, pressed -> showTab(Tab.UNITS, kind));
            button.setTooltip(Tooltip.create(name));
            kindTabs.put(kind, addRenderableWidget(button));
        }

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

    private Button tabButton(int index, String key, Tab target) {
        return addRenderableWidget(Button.builder(Component.translatable(key), button -> showTab(target, unitPage))
                .bounds(leftPos + 8 + index * 66, topPos + 28, 62, 18).build());
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

    /** Switch tab. Each tab shows its own slots, and the Units tab shows the page of one kind of unit. */
    private void showTab(Tab newTab, UnitKind kind) {
        tab = newTab;
        unitPage = kind;
        behaviorScroll = 0;
        // Only the Hive tab has slots; on the others they are hidden and cannot be clicked. The server is told, so that
        // shift-click agrees.
        menu.visibleGroups = newTab == Tab.HIVE ? HiveMenu.GROUP_STORAGE | HiveMenu.GROUP_GEAR | HiveMenu.GROUP_CRAFT : 0;
        PacketDistributor.sendToServer(new SetMenuViewPayload(menu.containerId, menu.visibleGroups));
        hiveTab.active = newTab != Tab.HIVE;
        questsTab.active = newTab != Tab.QUESTS;
        teamTab.active = newTab != Tab.TEAM;

        if (newTab == Tab.TEAM) {
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
        soldierWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.SOLDIER);
        workerWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.WORKER);
        collectorWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.COLLECTOR);
        scoutWidgets.forEach(widget -> widget.visible = units && unitPage == UnitKind.SCOUT);
        applyBehaviorScroll();
    }

    // ---- scrolling the settings ----

    /** The bottom of the settings area, in the panel's own coordinates: a little above the panel's edge. */
    private int behaviorViewBottom() {
        return imageHeight - 8;
    }

    /** Where the unit page's settings end if nothing is scrolled: how tall the settings of this kind of unit are. */
    private int behaviorContentBottom() {
        return switch (unitPage) {
            case SOLDIER -> BEHAVIOR_TOP + 4 * BEHAVIOR_ROW;
            case WORKER -> BEHAVIOR_TOP + 11 * BEHAVIOR_ROW;
            case SCOUT -> BEHAVIOR_TOP + 5 * BEHAVIOR_ROW;
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
            for (List<AbstractWidget> group : List.of(soldierWidgets, workerWidgets, collectorWidgets, scoutWidgets)) {
                for (AbstractWidget widget : group) {
                    widget.setY(widget.getY() + delta);
                }
            }
            appliedScroll = behaviorScroll;
        }
        int top = topPos + BEHAVIOR_TOP - 4;
        int bottom = topPos + behaviorViewBottom();
        for (List<AbstractWidget> group : List.of(soldierWidgets, workerWidgets, collectorWidgets, scoutWidgets)) {
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
        int y = BEHAVIOR_TOP + 10 * BEHAVIOR_ROW + 4 - behaviorScroll;
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
            UnitIconButton button = new UnitIconButton(leftPos + UNIT_LIST_X, topPos + UNIT_LIST_TOP + i * (UNIT_HEAD + 4), UNIT_HEAD, UNIT_HEAD,
                    name, () -> unitEntity(id, unitPage), unitPage, () -> id == viewedUnit, pressed -> {
                viewUnit(id);
                // Clicking a head also highlights that one unit in the world, and only it.
                ClientSelection.retain(Set.of());
                ClientSelection.select(id);
                // ...and the camera goes to it, 3 blocks away, looking straight at it.
                PacketDistributor.sendToServer(new FocusUnitPayload(id));
            });
            button.setTooltip(Tooltip.create(name));
            unitButtons.add(addRenderableWidget(button));
        }
        if (!ids.contains(viewedUnit)) {
            viewUnit(ids.isEmpty() ? -1 : ids.get(0));
        }
    }

    /** The live unit to draw, or a stand-in of its kind when it is out of the client's sight. */
    @Nullable
    private static LivingEntity unitEntity(int id, UnitKind kind) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.getEntity(id) instanceof LivingEntity living) {
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

        // Soldiers: four options with a radius each, then the one that reaches anywhere.
        allInHiveArea = behaviorBox(soldierWidgets, 0, "screen.projecthivemind.behavior.all_in_hive", this::sendSoldierBehavior);

        hostileInHiveArea = behaviorBox(soldierWidgets, 1, "screen.projecthivemind.behavior.hostile_in_hive", this::sendSoldierBehavior);
        soldierStay = behaviorBox(soldierWidgets, 2, "screen.projecthivemind.behavior.stay_inside", this::sendSoldierBehavior);
        soldierWander = behaviorBox(soldierWidgets, 3, "screen.projecthivemind.behavior.wander", this::sendSoldierBehavior);

        // Workers: what to work on, each with how far to look for it.
        mineOre = behaviorBox(workerWidgets, 0, "screen.projecthivemind.behavior.mine_ore", this::sendWorkerBehavior);
        workerRadii[0] = radiusBox(workerWidgets, 0, 0, this::sendWorkerBehavior);
        chopLogs = behaviorBox(workerWidgets, 1, "screen.projecthivemind.behavior.chop_logs", this::sendWorkerBehavior);
        workerRadii[1] = radiusBox(workerWidgets, 1, 1, this::sendWorkerBehavior);
        digThrough = behaviorBox(workerWidgets, 2, "screen.projecthivemind.behavior.dig_through", this::sendWorkerBehavior);
        workerStay = behaviorBox(workerWidgets, 3, "screen.projecthivemind.behavior.stay_inside", this::sendWorkerBehavior);
        // The widgets are made at their unscrolled places.
        behaviorScroll = 0;
        appliedScroll = 0;
        harvestCrops = behaviorBox(workerWidgets, 4, "screen.projecthivemind.behavior.harvest_crops", this::sendWorkerBehavior);
        clearPlants = behaviorBox(workerWidgets, 5, "screen.projecthivemind.behavior.clear_plants", this::sendWorkerBehavior);
        workerWander = behaviorBox(workerWidgets, 6, "screen.projecthivemind.behavior.wander", this::sendWorkerBehavior);
        // Flatten the ground: the box, and at the end of its row the block that fills the gaps (chosen from a list).
        flattenGround = behaviorBox(workerWidgets, 7, "screen.projecthivemind.behavior.flatten_ground", this::sendWorkerBehavior);
        fillButton = addRenderableWidget(new SeedButton(leftPos + imageWidth - 12 - 22, topPos + BEHAVIOR_TOP + 7 * BEHAVIOR_ROW - 2, 20, 20,
                this::currentFill, button -> openFillPicker()));
        fillButton.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.behavior.flatten_ground.tooltip")));
        workerWidgets.add(fillButton);
        channelCrops = behaviorBox(workerWidgets, 8, "screen.projecthivemind.behavior.channel_crops", this::sendWorkerBehavior);
        // Bound to channelling: it can only be ticked while that is.
        useBoneMeal = behaviorBox(workerWidgets, 9, "screen.projecthivemind.behavior.use_bone_meal", this::sendWorkerBehavior);

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

        // Scouts: the two things they may do on their own, each with its radius.
        pickUpItems = behaviorBox(scoutWidgets, 0, "screen.projecthivemind.behavior.scout_pickup", this::sendScoutBehavior);
        scoutRadii[0] = radiusBox(scoutWidgets, 0, 0, this::sendScoutBehavior);
        fleeHostiles = behaviorBox(scoutWidgets, 1, "screen.projecthivemind.behavior.scout_flee", this::sendScoutBehavior);
        scoutRadii[1] = radiusBox(scoutWidgets, 1, 1, this::sendScoutBehavior);
        scoutStay = behaviorBox(scoutWidgets, 2, "screen.projecthivemind.behavior.stay_inside", this::sendScoutBehavior);
        scoutWander = behaviorBox(scoutWidgets, 3, "screen.projecthivemind.behavior.wander", this::sendScoutBehavior);

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
        for (List<AbstractWidget> group : List.of(soldierWidgets, workerWidgets, collectorWidgets, scoutWidgets)) {
            for (AbstractWidget widget : group) {
                if (widget instanceof EditBox editBox) {
                    editBox.setEditable(enabled);
                } else {
                    widget.active = enabled;
                }
            }
        }
        if (enabled && useBoneMeal != null) {
            useBoneMeal.active = channelCrops.selected();
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
                setChecked(channelCrops, worker.channelCrops());
                setChecked(useBoneMeal, worker.useBoneMeal());
                int[] values = worker.radii();
                for (int i = 0; i < workerRadii.length; i++) {
                    workerRadii[i].setValue(String.valueOf(values[i]));
                }
            }
            case COLLECTOR -> {
                // Nothing to fill in: a collector's page shows its tasks, which come with the unit list.
            }
            case SCOUT -> {
                ScoutBehavior scout = ScoutBehavior.from(flags, radii);
                setChecked(pickUpItems, scout.collectItems());
                setChecked(fleeHostiles, scout.fleeHostiles());
                setChecked(scoutStay, scout.stayInside());
                setChecked(scoutWander, scout.wander());
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
        // The bone meal tick belongs to channelling: it can only be changed while that is ticked.
        useBoneMeal.active = channelCrops.selected();
        if (canSend()) {
            WorkerBehavior behavior = new WorkerBehavior(mineOre.selected(), number(workerRadii[0]), chopLogs.selected(),
                    number(workerRadii[1]), digThrough.selected(), workerStay.selected(), harvestCrops.selected(), clearPlants.selected(), workerWander.selected(), flattenGround.selected(), channelCrops.selected(), useBoneMeal.selected());
            sendBehavior(behavior.flags(), behavior.radii());
        }
    }

    private void sendScoutBehavior() {
        if (canSend()) {
            ScoutBehavior behavior = new ScoutBehavior(pickUpItems.selected(), number(scoutRadii[0]), fleeHostiles.selected(), number(scoutRadii[1]), scoutStay.selected(), scoutWander.selected());
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
        super.render(graphics, mouseX, mouseY, partialTick);
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
                graphics.fill(x, y, x + 16, y + 16, SLOT_FILL);
            }
        }

        if (tab == Tab.HIVE) {
            StorageScroll scroll = menu.storageScroll();
            HiveStyle.scrollbar(graphics, leftPos + HiveMenu.STORAGE_X + 9 * 18 + 1, topPos + HiveMenu.STORAGE_Y,
                    scroll.visibleRows() * 18, scroll.totalRows(), scroll.visibleRows(), scroll.row());
        }
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
        if (unitPage != UnitKind.COLLECTOR && unitPage != UnitKind.SOLDIER) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.radius"), imageWidth - 12 - 34, BEHAVIOR_TOP - 12, 0xA0A0A0, false);
        }
        switch (unitPage) {
            case COLLECTOR -> renderCollectorTask(graphics);
            case SCOUT -> graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.scout_note"),
                    BEHAVIOR_X + 4, BEHAVIOR_TOP + 4 * BEHAVIOR_ROW + 4, 0x909090, false);
            case WORKER -> renderWorkerNote(graphics);
            default -> {
            }
        }
    }

    // ---- the Team tab ----

    private static final int TEAM_TOP = 98;
    private static final int TEAM_STEP = UNIT_HEAD + 4;
    /** What the Team tab last showed: each unit's id, and whether it was in the team. */
    private final List<Integer> shownTeam = new ArrayList<>();
    private int teamOthersTop;

    /**
     * The Team tab: the heads of the units in the team, and under them the heads of the rest. Clicking a head moves that unit
     * into the team or back out of it. Rebuilt whenever a unit joins, leaves, appears or is lost.
     */
    private void refreshTeam(boolean force) {
        // Collectors cannot be in a team, so they are not listed.
        List<SyncUnitsPayload.Entry> all = ClientUnits.all().stream().filter(entry -> entry.kind() != UnitKind.COLLECTOR.ordinal()).toList();
        List<Integer> key = new ArrayList<>();
        for (SyncUnitsPayload.Entry entry : all) {
            key.add(entry.entityId() * 2 + (entry.team() ? 1 : 0));
        }
        if (!force && key.equals(shownTeam)) {
            return;
        }
        clearUnitButtons();
        shownTeam.clear();
        shownTeam.addAll(key);
        int columns = Math.max(1, (imageWidth - 24) / TEAM_STEP);
        int members = 0;
        int others = 0;
        int memberCount = (int) all.stream().filter(SyncUnitsPayload.Entry::team).count();
        teamOthersTop = TEAM_TOP + Math.max(1, (memberCount + columns - 1) / columns) * TEAM_STEP + 22;
        for (SyncUnitsPayload.Entry entry : all) {
            UnitKind kind = UnitKind.values()[entry.kind()];
            int id = entry.entityId();
            boolean inTeam = entry.team();
            int index = inTeam ? members++ : others++;
            int x = leftPos + UNIT_LIST_X + (index % columns) * TEAM_STEP;
            int y = topPos + (inTeam ? TEAM_TOP : teamOthersTop) + (index / columns) * TEAM_STEP;
            Component name = Component.translatable("screen.projecthivemind.unit.numbered",
                    Component.translatable("unit.projecthivemind." + kind.name().toLowerCase(Locale.ROOT)), ClientUnits.ofKind(kind).indexOf(id) + 1);
            UnitIconButton button = new UnitIconButton(x, y, UNIT_HEAD, UNIT_HEAD, name, () -> unitEntity(id, kind), kind, () -> inTeam,
                    pressed -> PacketDistributor.sendToServer(new ToggleTeamPayload(id)));
            button.setTooltip(Tooltip.create(name.copy().append(Component.translatable(inTeam
                    ? "screen.projecthivemind.team.click_remove" : "screen.projecthivemind.team.click_add"))));
            unitButtons.add(addRenderableWidget(button));
        }
        // How far around its scout the team keeps together: the ring of flames round the scout shows it.
        TeamAreaSlider slider = new TeamAreaSlider(leftPos + UNIT_LIST_X, topPos + imageHeight - 72, 180, 18, ClientTeams.radius(0),
                radius -> PacketDistributor.sendToServer(new SetTeamRadiusPayload(0, radius)));
        slider.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.team.area.tooltip")));
        unitButtons.add(addRenderableWidget(slider));
    }

    private void renderTeam(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.team.title", 1), UNIT_LIST_X, TEAM_TOP - 14, 0xFFFFFF, false);
        boolean none = ClientUnits.all().stream().noneMatch(SyncUnitsPayload.Entry::team);
        if (none) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.team.empty"), UNIT_LIST_X + 4, TEAM_TOP + 10, 0x909090, false);
        }
        graphics.drawString(font, Component.translatable("screen.projecthivemind.team.others"), UNIT_LIST_X, teamOthersTop - 14, 0xA0A0A0, false);
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
        if (quest.rawIron() > 0) {
            y = questLine(graphics, y, Component.translatable("screen.projecthivemind.quest.iron", quest.rawIron()), menu.questIron(), quest.rawIron(), 1, "");
        }

        y += 8;
        graphics.drawString(font, Component.translatable("screen.projecthivemind.quest.unlocks", next.level()), QUEST_X, y, 0xFFDD55, false);
        y += 14;
        // Only what actually changes at the next level.
        for (UnitKind kind : new UnitKind[] {UnitKind.SOLDIER, UnitKind.WORKER, UnitKind.SCOUT, UnitKind.COLLECTOR}) {
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
        // Same order as the command bar's keys: scouts, soldiers, workers; then the collectors, which you do not command.
        drawUnitCount(graphics, 0, "screen.projecthivemind.hive.scouts", UnitKind.SCOUT);
        drawUnitCount(graphics, 1, "screen.projecthivemind.hive.soldiers", UnitKind.SOLDIER);
        drawUnitCount(graphics, 2, "screen.projecthivemind.hive.workers", UnitKind.WORKER);
        drawUnitCount(graphics, 3, "screen.projecthivemind.hive.collectors", UnitKind.COLLECTOR);

        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.armor"), HiveMenu.ARMOR_X, LABEL_Y, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.storage"), HiveMenu.STORAGE_X, LABEL_Y, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.food"), HiveMenu.ARMOR_X,
                HiveMenu.ARMOR_Y + 4 * 18 + 22, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.scout_hand"), HiveMenu.STORAGE_X + 22,
                HiveMenu.scoutHandY(menu.storageRows()) + 4, 0xA0A0A0, false);
        // The workstation on the right is named for the open tab.
        String workstation = "screen.projecthivemind.hive.crafting";
        graphics.drawString(font, Component.translatable(workstation), HiveMenu.GRID_X, LABEL_Y, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.tools"),
                HiveMenu.TOOLS_X + 5 * 18 + 6, HiveMenu.toolsY(menu.storageRows()) + 5, 0xA0A0A0, false);
    }

    /**
     * "Workers 1/1": units out now, out of the most the hive allows (orange at the limit). Underneath, what the hive's
     * next 10-second interval will do for them, with the countdown.
     */
    private void drawUnitCount(GuiGraphics graphics, int column, String labelKey, UnitKind kind) {
        int x = HiveMenu.STORAGE_X + column * COUNTS_SPACING;
        boolean atLimit = menu.unitCount(kind) >= menu.unitCap(kind);
        graphics.drawString(font, Component.translatable(labelKey, menu.unitCount(kind), menu.unitCap(kind)),
                x, countsY(), atLimit ? 0xFFAA00 : 0xFFFFFF, false);

        int seconds = menu.secondsUntilSpawn();
        Component status;
        int colour;
        switch (menu.unitStatus(kind)) {
            case HiveMenu.STATUS_SPAWNING -> {
                status = Component.translatable("screen.projecthivemind.hive.status.spawning", seconds);
                colour = 0x77DD77;
            }
            default -> {
                status = Component.translatable("screen.projecthivemind.hive.status.idle");
                colour = 0x808080;
            }
        }
        graphics.drawString(font, status, x, countsY() + 10, colour, false);
    }

    /** Health points as hearts: 2 points per heart, dropping a trailing ".0". */
    private static String hearts(int healthPoints) {
        float hearts = healthPoints / 2.0F;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.format(Locale.ROOT, "%.1f", hearts);
    }
}
