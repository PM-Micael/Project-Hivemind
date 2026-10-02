package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.ScoutBehavior;
import com.projecthivemind.SoldierBehavior;
import com.projecthivemind.UnitKind;
import com.projecthivemind.menu.StorageScroll;
import com.projecthivemind.WorkerBehavior;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.ReturnToHeartPayload;
import com.projecthivemind.network.ScrollStoragePayload;
import com.projecthivemind.network.SetBehaviorPayload;
import com.projecthivemind.network.SetMenuViewPayload;
import com.projecthivemind.network.SetCollectorBehaviorPayload;
import com.projecthivemind.network.SetScoutBehaviorPayload;
import com.projecthivemind.network.SetWorkerBehaviorPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive menu. Three tabs: Hive (shared storage, crafting, gear slots, unit counts), Quests, and Behavior (what
 * the hive's units do on their own, with a page each for soldiers and workers). Drawn with plain rectangles until
 * there is real art.
 */
public class HiveScreen extends AbstractContainerScreen<HiveMenu> {
    private static final int PANEL = 0xF0201414;
    private static final int PANEL_EDGE = 0xFF6B2A2A;
    private static final int SLOT_EDGE = 0xFF120A0A;
    private static final int SLOT_FILL = 0xFF3A2424;

    /** Where the row of unit counts sits, and how far apart its entries are. */
    private static final int COUNTS_SPACING = 80;

    // Behavior tab layout, relative to the panel.
    /** Where the labels over the slot grids sit, just above the slots. */
    private static final int STATION_TAB_Y = 50;
    private static final int LABEL_Y = HiveMenu.STORAGE_Y - 10;
    private static final int PAGE_BUTTONS_Y = 76;
    private static final int BEHAVIOR_X = 12;
    private static final int BEHAVIOR_TOP = 102;
    private static final int BEHAVIOR_ROW = 18;

    private enum Tab {
        CRAFTING, FURNACE, QUESTS, BEHAVIOR
    }

    /** Which unit's behaviour the Behavior tab is showing. */
    private enum Page {
        SOLDIERS, WORKERS, COLLECTORS, SCOUTS
    }

    private Tab tab = Tab.CRAFTING;
    private Page page = Page.SOLDIERS;
    private Button craftingTab;
    /** Only there once the hive has a furnace (level 3). More tabs for more built-in inventories go the same way. */
    @Nullable
    private Button furnaceTab;
    private Button questsTab;
    private Button behaviorTab;
    private Button soldiersPage;
    private Button workersPage;
    private Button collectorsPage;
    private Button scoutsPage;

    // Soldier settings.
    private EditBox soldierAreaBox;
    private Checkbox allInHiveArea;
    private Checkbox hostileInHiveArea;
    private Checkbox allInUnitArea;
    private Checkbox hostileInUnitArea;
    private Checkbox threats;
    private int soldierRadius;

    // Worker settings.
    private EditBox workerAreaBox;
    private Checkbox mineOre;
    private Checkbox chopLogs;
    private Checkbox digThrough;
    private int workerRadius;

    // Collector setting: just the one.
    private EditBox collectorRangeBox;
    private int collectorRange;

    private final List<AbstractWidget> soldierWidgets = new ArrayList<>();
    private final List<AbstractWidget> workerWidgets = new ArrayList<>();
    // Scout settings: the radius and two checkboxes.
    private EditBox scoutAreaBox;
    private int scoutRadius;
    private Checkbox pickUpItems;
    private Checkbox fleeHostiles;

    private final List<AbstractWidget> collectorWidgets = new ArrayList<>();
    private final List<AbstractWidget> scoutWidgets = new ArrayList<>();

    /** True while the widgets are being filled from the server's values, so that does not count as the player editing. */
    private boolean filling;
    /** The widgets show the server's real settings. Until then they are disabled, so nothing wrong can be sent back. */
    private boolean behaviorLoaded;

    public HiveScreen(HiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // Wide enough for five tabs, with room to spare for more.
        this.imageWidth = 360;
        this.imageHeight = HiveMenu.panelHeight(menu.storageRows());
        this.titleLabelX = 8;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();
        int tabIndex = 0;
        // Top row: the text tabs. Second row: one icon tab per crafting station, in the order they are unlocked.
        questsTab = tabButton(0, "screen.projecthivemind.hive.tab_quests", Tab.QUESTS);
        behaviorTab = tabButton(1, "screen.projecthivemind.hive.tab_behavior", Tab.BEHAVIOR);
        craftingTab = stationTab(tabIndex++, Items.CRAFTING_TABLE, "screen.projecthivemind.hive.tab_hive", Tab.CRAFTING);
        furnaceTab = menu.hasFurnace() ? stationTab(tabIndex++, Items.FURNACE, "screen.projecthivemind.hive.tab_furnace", Tab.FURNACE) : null;

        soldiersPage = pageButton(0, "screen.projecthivemind.behavior.page_soldiers", Page.SOLDIERS);
        workersPage = pageButton(1, "screen.projecthivemind.behavior.page_workers", Page.WORKERS);
        collectorsPage = pageButton(2, "screen.projecthivemind.behavior.page_collectors", Page.COLLECTORS);
        scoutsPage = pageButton(3, "screen.projecthivemind.behavior.page_scouts", Page.SCOUTS);

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

        initBehaviorWidgets();
        showTab(tab);
    }


    /** A crafting station's tab: a small button showing the station's block instead of a name, with the name as its tooltip. */
    private Button stationTab(int index, Item icon, String nameKey, Tab target) {
        Component name = Component.translatable(nameKey);
        ItemStack stack = new ItemStack(icon);
        Button button = new StationTabButton(leftPos + 8 + index * 26, topPos + STATION_TAB_Y, name, stack, pressed -> showTab(target));
        button.setTooltip(Tooltip.create(name));
        return addRenderableWidget(button);
    }

    /** A button that shows an item (a crafting station's block) where a label would be. */
    private static final class StationTabButton extends Button {
        private final ItemStack icon;

        StationTabButton(int x, int y, Component name, ItemStack icon, Button.OnPress onPress) {
            super(x, y, 24, 20, name, onPress, DEFAULT_NARRATION);
            this.icon = icon;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            graphics.renderItem(icon, getX() + 4, getY() + 2);
        }

        /** The name is only a tooltip: drawing it on the button as well would put scrolling text behind the icon. */
        @Override
        public void renderString(GuiGraphics graphics, Font font, int color) {
        }
    }

    private Button tabButton(int index, String key, Tab target) {
        return addRenderableWidget(Button.builder(Component.translatable(key), button -> showTab(target))
                .bounds(leftPos + 8 + index * 66, topPos + 28, 62, 18).build());
    }

    private Button pageButton(int index, String key, Page target) {
        return addRenderableWidget(Button.builder(Component.translatable(key), button -> {
            page = target;
            showTab(Tab.BEHAVIOR);
        }).bounds(leftPos + 8 + index * 70, topPos + PAGE_BUTTONS_Y, 66, 16).build());
    }

    private void showTab(Tab newTab) {
        tab = newTab;
        // The storage and the gear slots are on every inventory tab. The right-hand side is the tab's workstation: the
        // crafting grid, the furnace, and so on. Slots not shown cannot be clicked; the server is told so shift-click agrees.
        menu.visibleGroups = switch (newTab) {
            case CRAFTING -> HiveMenu.GROUP_STORAGE | HiveMenu.GROUP_GEAR | HiveMenu.GROUP_CRAFT;
            case FURNACE -> HiveMenu.GROUP_STORAGE | HiveMenu.GROUP_GEAR | HiveMenu.GROUP_FURNACE;
            default -> 0;
        };
        PacketDistributor.sendToServer(new SetMenuViewPayload(menu.containerId, menu.visibleGroups));
        craftingTab.active = newTab != Tab.CRAFTING;
        if (furnaceTab != null) {
            furnaceTab.active = newTab != Tab.FURNACE;
        }
        questsTab.active = newTab != Tab.QUESTS;
        behaviorTab.active = newTab != Tab.BEHAVIOR;

        boolean behavior = newTab == Tab.BEHAVIOR;
        soldiersPage.visible = behavior;
        workersPage.visible = behavior;
        collectorsPage.visible = behavior;
        scoutsPage.visible = behavior;
        soldiersPage.active = page != Page.SOLDIERS;
        workersPage.active = page != Page.WORKERS;
        collectorsPage.active = page != Page.COLLECTORS;
        scoutsPage.active = page != Page.SCOUTS;
        scoutWidgets.forEach(widget -> widget.visible = behavior && page == Page.SCOUTS);
        soldierWidgets.forEach(widget -> widget.visible = behavior && page == Page.SOLDIERS);
        workerWidgets.forEach(widget -> widget.visible = behavior && page == Page.WORKERS);
        collectorWidgets.forEach(widget -> widget.visible = behavior && page == Page.COLLECTORS);
    }

    // ---- the Behavior tab ----

    /**
     * Build the checkboxes and the radius fields, disabled and empty. They are filled in, and enabled, by
     * {@link #loadBehavior()} once the server's real settings have reached the client: a menu that has just opened
     * does not have them yet, and showing placeholders would hide the real settings and let them be overwritten.
     */
    private void initBehaviorWidgets() {
        behaviorLoaded = false;
        filling = true;
        soldierWidgets.clear();
        workerWidgets.clear();
        collectorWidgets.clear();
        scoutWidgets.clear();

        // Soldiers: the radius first, since it limits every option below except the last.
        soldierAreaBox = areaBox(soldierWidgets, "screen.projecthivemind.behavior.unit_area",
                "screen.projecthivemind.behavior.soldier_area.tooltip", text -> {
            soldierRadius = Integer.parseInt(text);
            sendSoldierBehavior();
        });
        allInHiveArea = behaviorBox(soldierWidgets, 1, "screen.projecthivemind.behavior.all_in_hive", this::sendSoldierBehavior);
        hostileInHiveArea = behaviorBox(soldierWidgets, 2, "screen.projecthivemind.behavior.hostile_in_hive", this::sendSoldierBehavior);
        allInUnitArea = behaviorBox(soldierWidgets, 3, "screen.projecthivemind.behavior.all_in_unit", this::sendSoldierBehavior);
        hostileInUnitArea = behaviorBox(soldierWidgets, 4, "screen.projecthivemind.behavior.hostile_in_unit", this::sendSoldierBehavior);
        threats = behaviorBox(soldierWidgets, 5, "screen.projecthivemind.behavior.threats", this::sendSoldierBehavior);

        // Workers: the radius first again, then what to work on.
        workerAreaBox = areaBox(workerWidgets, "screen.projecthivemind.behavior.unit_area",
                "screen.projecthivemind.behavior.worker_area.tooltip", text -> {
            workerRadius = Integer.parseInt(text);
            sendWorkerBehavior();
        });
        mineOre = behaviorBox(workerWidgets, 1, "screen.projecthivemind.behavior.mine_ore", this::sendWorkerBehavior);
        chopLogs = behaviorBox(workerWidgets, 2, "screen.projecthivemind.behavior.chop_logs", this::sendWorkerBehavior);
        digThrough = behaviorBox(workerWidgets, 3, "screen.projecthivemind.behavior.dig_through", this::sendWorkerBehavior);

        // Collectors: just how far past the hive area they may reach for items.
        collectorRangeBox = areaBox(collectorWidgets, "screen.projecthivemind.behavior.collector_range",
                "screen.projecthivemind.behavior.collector_range.tooltip", text -> {
            collectorRange = Integer.parseInt(text);
            sendCollectorBehavior();
        });

        // Scouts: the radius first, then the two things they may do on their own.
        scoutAreaBox = areaBox(scoutWidgets, "screen.projecthivemind.behavior.unit_area",
                "screen.projecthivemind.behavior.scout_area.tooltip", text -> {
            scoutRadius = Integer.parseInt(text);
            sendScoutBehavior();
        });
        pickUpItems = behaviorBox(scoutWidgets, 1, "screen.projecthivemind.behavior.scout_pickup", this::sendScoutBehavior);
        fleeHostiles = behaviorBox(scoutWidgets, 2, "screen.projecthivemind.behavior.scout_flee", this::sendScoutBehavior);

        setBehaviorEnabled(false);
        filling = false;
    }

    /**
     * The number field at the top of a page, placed just after its label so it never overlaps it, whatever the
     * label's length. Reports each valid number the player types.
     */
    private EditBox areaBox(List<AbstractWidget> group, String labelKey, String tooltipKey, java.util.function.Consumer<String> onNumber) {
        Component label = Component.translatable(labelKey);
        int fieldX = leftPos + BEHAVIOR_X + 4 + font.width(label) + 8;
        EditBox box = addRenderableWidget(new EditBox(font, fieldX, topPos + BEHAVIOR_TOP, 32, 16, label));
        box.setMaxLength(2);
        box.setFilter(text -> text.matches("\\d*"));
        box.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        box.setResponder(text -> {
            if (!text.isEmpty() && !filling && behaviorLoaded) {
                onNumber.accept(text);
            }
        });
        group.add(box);
        return box;
    }

    private Checkbox behaviorBox(List<AbstractWidget> group, int row, String key, Runnable onChange) {
        Checkbox box = Checkbox.builder(Component.translatable(key), font)
                .maxWidth(imageWidth - 2 * BEHAVIOR_X)
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
    }

    /** Fill the widgets from the server's real settings, once, and let the player edit them. */
    private void loadBehavior() {
        SoldierBehavior soldiers = SoldierBehavior.fromFlags(menu.behaviorFlags(), menu.unitAreaRadius());
        WorkerBehavior workers = WorkerBehavior.fromFlags(menu.workerFlags(), menu.workerAreaRadius());
        filling = true;
        soldierRadius = soldiers.unitAreaRadius();
        soldierAreaBox.setValue(String.valueOf(soldierRadius));
        setChecked(allInHiveArea, soldiers.allInHiveArea());
        setChecked(hostileInHiveArea, soldiers.hostileInHiveArea());
        setChecked(allInUnitArea, soldiers.allInUnitArea());
        setChecked(hostileInUnitArea, soldiers.hostileInUnitArea());
        setChecked(threats, soldiers.threats());

        workerRadius = workers.unitAreaRadius();
        workerAreaBox.setValue(String.valueOf(workerRadius));
        setChecked(mineOre, workers.mineOre());
        setChecked(chopLogs, workers.chopLogs());
        setChecked(digThrough, workers.digThrough());

        collectorRange = menu.collectorRange();
        collectorRangeBox.setValue(String.valueOf(collectorRange));

        ScoutBehavior scouts = ScoutBehavior.fromFlags(menu.scoutFlags(), menu.scoutAreaRadius());
        scoutRadius = scouts.unitAreaRadius();
        scoutAreaBox.setValue(String.valueOf(scoutRadius));
        setChecked(pickUpItems, scouts.collectItems());
        setChecked(fleeHostiles, scouts.fleeHostiles());
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

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!behaviorLoaded && menu.behaviorReady()) {
            loadBehavior();
        }
    }

    /** Send the whole set of soldier settings to the server whenever the player changes one. */
    private void sendSoldierBehavior() {
        if (filling || !behaviorLoaded) {
            return;
        }
        int flags = new SoldierBehavior(allInHiveArea.selected(), hostileInHiveArea.selected(), soldierRadius,
                allInUnitArea.selected(), hostileInUnitArea.selected(), threats.selected()).flags();
        PacketDistributor.sendToServer(new SetBehaviorPayload(flags, soldierRadius));
    }

    /** Send the whole set of worker settings to the server whenever the player changes one. */
    private void sendWorkerBehavior() {
        if (filling || !behaviorLoaded) {
            return;
        }
        int flags = new WorkerBehavior(workerRadius, mineOre.selected(), chopLogs.selected(), digThrough.selected()).flags();
        PacketDistributor.sendToServer(new SetWorkerBehaviorPayload(flags, workerRadius));
    }

    /** Send the scout settings to the server whenever the player changes one. */
    private void sendScoutBehavior() {
        if (filling || !behaviorLoaded) {
            return;
        }
        int flags = new ScoutBehavior(scoutRadius, pickUpItems.selected(), fleeHostiles.selected()).flags();
        PacketDistributor.sendToServer(new SetScoutBehaviorPayload(flags, scoutRadius));
    }

    /** Send the collector range to the server whenever the player changes it. */
    private void sendCollectorBehavior() {
        if (filling || !behaviorLoaded) {
            return;
        }
        PacketDistributor.sendToServer(new SetCollectorBehaviorPayload(collectorRange));
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

        if (tab == Tab.CRAFTING || tab == Tab.FURNACE) {
            StorageScroll scroll = menu.storageScroll();
            HiveStyle.scrollbar(graphics, leftPos + HiveMenu.STORAGE_X + 9 * 18 + 1, topPos + HiveMenu.STORAGE_Y,
                    scroll.visibleRows() * 18, scroll.totalRows(), scroll.visibleRows(), scroll.row());
            if (tab == Tab.FURNACE) {
                drawFurnace(graphics);
            }
        }
    }

    /** The flame between the furnace's input and fuel, and the arrow from input to output, filled as it burns and cooks. */
    private void drawFurnace(GuiGraphics graphics) {
        int flameX = leftPos + HiveMenu.FURNACE_INPUT_X + 4;
        int flameY = topPos + HiveMenu.FURNACE_INPUT_Y + 20;
        graphics.fill(flameX, flameY, flameX + 8, flameY + 14, SLOT_EDGE);
        int burn = Math.round(14 * menu.furnaceBurn());
        graphics.fill(flameX, flameY + 14 - burn, flameX + 8, flameY + 14, 0xFFFF9A2E);

        int arrowX = leftPos + HiveMenu.FURNACE_INPUT_X + 26;
        int arrowY = topPos + HiveMenu.FURNACE_OUTPUT_Y + 6;
        graphics.fill(arrowX, arrowY, arrowX + 24, arrowY + 4, SLOT_EDGE);
        graphics.fill(arrowX, arrowY, arrowX + Math.round(24 * menu.furnaceProgress()), arrowY + 4, 0xFFE8E8E8);
    }

    /** The mouse wheel over the hive storage scrolls it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        StorageScroll scroll = menu.storageScroll();
        if ((tab == Tab.CRAFTING || tab == Tab.FURNACE) && scroll.maxRow() > 0 && mouseX >= leftPos + HiveMenu.STORAGE_X && mouseX < leftPos + HiveMenu.STORAGE_X + 9 * 18 + 6
                && mouseY >= topPos + HiveMenu.STORAGE_Y && mouseY < topPos + HiveMenu.STORAGE_Y + scroll.visibleRows() * 18) {
            int row = HiveStyle.scrolledRow(scroll.row(), scrollY, scroll.maxRow());
            if (row != scroll.row()) {
                PacketDistributor.sendToServer(new ScrollStoragePayload(menu.containerId, row));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.header", menu.level()), titleLabelX, titleLabelY, 0xFFFFFF, false);

        switch (tab) {
            case QUESTS -> renderQuests(graphics);

            case BEHAVIOR -> renderBehaviorLabels(graphics);
            default -> renderHiveLabels(graphics);
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
        if (next.level() >= HiveLevels.FURNACE_LEVEL && current.level() < HiveLevels.FURNACE_LEVEL) {
            y = unlockLine(graphics, y, Component.translatable("screen.projecthivemind.quest.unlock_furnace"));
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

    private void renderBehaviorLabels(GuiGraphics graphics) {
        String labelKey = page == Page.COLLECTORS ? "screen.projecthivemind.behavior.collector_range" : "screen.projecthivemind.behavior.unit_area";
        graphics.drawString(font, Component.translatable(labelKey), BEHAVIOR_X + 4, BEHAVIOR_TOP + 4, 0xE0E0E0, false);
        if (page == Page.COLLECTORS) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.collector_note"),
                    BEHAVIOR_X + 4, BEHAVIOR_TOP + BEHAVIOR_ROW + 4, 0x909090, false);
        }
        if (page == Page.SCOUTS) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.scout_note"),
                    BEHAVIOR_X + 4, BEHAVIOR_TOP + 3 * BEHAVIOR_ROW + 4, 0x909090, false);
        }
        if (page == Page.WORKERS) {
            graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.worker_note"),
                    BEHAVIOR_X + 4, BEHAVIOR_TOP + 4 * BEHAVIOR_ROW + 4, 0x909090, false);
        }
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
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.scout_hand"), HiveMenu.STORAGE_X + 22,
                HiveMenu.scoutHandY(menu.storageRows()) + 4, 0xA0A0A0, false);
        // The workstation on the right is named for the open tab.
        String workstation = tab == Tab.FURNACE ? "screen.projecthivemind.hive.furnace" : "screen.projecthivemind.hive.crafting";
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
            case HiveMenu.STATUS_REFRESHING -> {
                status = Component.translatable("screen.projecthivemind.hive.status.refreshing", seconds);
                colour = 0xFFDD55;
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
