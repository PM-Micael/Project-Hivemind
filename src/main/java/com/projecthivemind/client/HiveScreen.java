package com.projecthivemind.client;

import java.util.Locale;

import javax.annotation.Nullable;

import com.projecthivemind.SoldierBehavior;
import com.projecthivemind.UnitKind;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.SetBehaviorPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive menu. Three tabs: Hive (shared storage, crafting, gear slots, unit counts), Quests, and Behavior (what
 * the hive's units do on their own). Drawn with plain rectangles until there is real art.
 */
public class HiveScreen extends AbstractContainerScreen<HiveMenu> {
    private static final int PANEL = 0xF0201414;
    private static final int PANEL_EDGE = 0xFF6B2A2A;
    private static final int SLOT_EDGE = 0xFF120A0A;
    private static final int SLOT_FILL = 0xFF3A2424;

    /** Where the row of unit counts sits, and how far apart its entries are. */
    private static final int COUNTS_Y = 148;
    private static final int COUNTS_SPACING = 72;

    // Behavior tab layout, relative to the panel.
    private static final int BEHAVIOR_X = 12;
    private static final int BEHAVIOR_TOP = 58;
    private static final int BEHAVIOR_ROW = 18;

    private enum Tab {
        HIVE, QUESTS, BEHAVIOR
    }

    private Tab tab = Tab.HIVE;
    private Button hiveTab;
    private Button questsTab;
    private Button behaviorTab;

    private Checkbox allInHiveArea;
    private Checkbox hostileInHiveArea;
    private EditBox unitAreaBox;
    private Checkbox allInUnitArea;
    private Checkbox hostileInUnitArea;
    private Checkbox threats;
    /** The last valid radius typed, used while the box is empty or being edited. */
    private int unitAreaRadius;
    /** True while the widgets are being filled from the server's values, so that does not count as the player editing. */
    private boolean filling;
    /** The widgets show the server's real settings. Until then they are disabled, so nothing wrong can be sent back. */
    private boolean behaviorLoaded;

    public HiveScreen(HiveMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 300;
        this.imageHeight = 168;
        this.titleLabelX = 8;
        this.titleLabelY = 8;
    }

    @Override
    protected void init() {
        super.init();
        hiveTab = tabButton(0, "screen.projecthivemind.hive.tab_hive", Tab.HIVE);
        questsTab = tabButton(1, "screen.projecthivemind.hive.tab_quests", Tab.QUESTS);
        behaviorTab = tabButton(2, "screen.projecthivemind.hive.tab_behavior", Tab.BEHAVIOR);

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

    private Button tabButton(int index, String key, Tab target) {
        return addRenderableWidget(Button.builder(Component.translatable(key), button -> showTab(target))
                .bounds(leftPos + 8 + index * 62, topPos + 28, 58, 18).build());
    }

    private void showTab(Tab newTab) {
        tab = newTab;
        // Only the Hive tab shows the slots; on the others they are hidden and cannot be clicked.
        menu.slotsHidden = newTab != Tab.HIVE;
        hiveTab.active = newTab != Tab.HIVE;
        questsTab.active = newTab != Tab.QUESTS;
        behaviorTab.active = newTab != Tab.BEHAVIOR;

        boolean behavior = newTab == Tab.BEHAVIOR;
        allInHiveArea.visible = behavior;
        hostileInHiveArea.visible = behavior;
        unitAreaBox.visible = behavior;
        allInUnitArea.visible = behavior;
        hostileInUnitArea.visible = behavior;
        threats.visible = behavior;
    }

    // ---- the Behavior tab ----

    /**
     * Build the checkboxes and the radius field, disabled and empty. They are filled in, and enabled, by
     * {@link #loadBehavior()} once the server's real settings have reached the client: a menu that has just opened
     * does not have them yet, and showing placeholders would hide the real settings and let them be overwritten.
     */
    private void initBehaviorWidgets() {
        behaviorLoaded = false;
        filling = true;
        // The unit area radius comes first: it limits every option below except the last.
        unitAreaBox = addRenderableWidget(new EditBox(font, leftPos + BEHAVIOR_X + 132, topPos + BEHAVIOR_TOP,
                32, 16, Component.translatable("screen.projecthivemind.behavior.unit_area")));
        unitAreaBox.setMaxLength(2);
        unitAreaBox.setFilter(text -> text.matches("\\d*"));
        unitAreaBox.setTooltip(Tooltip.create(Component.translatable("screen.projecthivemind.behavior.unit_area.tooltip")));
        unitAreaBox.setResponder(text -> {
            if (!text.isEmpty()) {
                unitAreaRadius = Integer.parseInt(text);
            }
            sendBehavior();
        });

        allInHiveArea = behaviorBox(1, "screen.projecthivemind.behavior.all_in_hive");
        hostileInHiveArea = behaviorBox(2, "screen.projecthivemind.behavior.hostile_in_hive");
        allInUnitArea = behaviorBox(3, "screen.projecthivemind.behavior.all_in_unit");
        hostileInUnitArea = behaviorBox(4, "screen.projecthivemind.behavior.hostile_in_unit");
        threats = behaviorBox(5, "screen.projecthivemind.behavior.threats");
        setBehaviorEnabled(false);
        filling = false;
    }

    private Checkbox behaviorBox(int row, String key) {
        Checkbox box = Checkbox.builder(Component.translatable(key), font)
                .maxWidth(imageWidth - 2 * BEHAVIOR_X)
                .onValueChange((checkbox, value) -> sendBehavior())
                .build();
        box.setPosition(leftPos + BEHAVIOR_X, topPos + BEHAVIOR_TOP + row * BEHAVIOR_ROW);
        return addRenderableWidget(box);
    }

    private void setBehaviorEnabled(boolean enabled) {
        allInHiveArea.active = enabled;
        hostileInHiveArea.active = enabled;
        unitAreaBox.setEditable(enabled);
        allInUnitArea.active = enabled;
        hostileInUnitArea.active = enabled;
        threats.active = enabled;
    }

    /** Fill the widgets from the server's real settings, once, and let the player edit them. */
    private void loadBehavior() {
        SoldierBehavior current = SoldierBehavior.fromFlags(menu.behaviorFlags(), menu.unitAreaRadius());
        filling = true;
        unitAreaRadius = current.unitAreaRadius();
        setChecked(allInHiveArea, current.allInHiveArea());
        setChecked(hostileInHiveArea, current.hostileInHiveArea());
        unitAreaBox.setValue(String.valueOf(current.unitAreaRadius()));
        setChecked(allInUnitArea, current.allInUnitArea());
        setChecked(hostileInUnitArea, current.hostileInUnitArea());
        setChecked(threats, current.threats());
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

    /** Send the whole set of settings to the server whenever the player changes one. */
    private void sendBehavior() {
        if (filling || !behaviorLoaded) {
            return;
        }
        int flags = new SoldierBehavior(allInHiveArea.selected(), hostileInHiveArea.selected(), unitAreaRadius,
                allInUnitArea.selected(), hostileInUnitArea.selected(), threats.selected()).flags();
        PacketDistributor.sendToServer(new SetBehaviorPayload(flags, unitAreaRadius));
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
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.header", menu.level()), titleLabelX, titleLabelY, 0xFFFFFF, false);

        String hearts = "♥ " + hearts(menu.health()) + " / " + hearts(menu.maxHealth());
        graphics.drawString(font, hearts, imageWidth - 8 - font.width(hearts), titleLabelY, 0xFF5555, false);

        switch (tab) {
            case QUESTS -> graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.no_quests"), 8, 58, 0xA0A0A0, false);
            case BEHAVIOR -> renderBehaviorLabels(graphics);
            default -> renderHiveLabels(graphics);
        }
    }

    private void renderBehaviorLabels(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.soldiers"), BEHAVIOR_X, 48, 0xFFFFFF, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.behavior.unit_area"),
                BEHAVIOR_X + 4, BEHAVIOR_TOP + 4, 0xE0E0E0, false);
    }

    private void renderHiveLabels(GuiGraphics graphics) {
        // Same order as the command bar's keys: scouts, soldiers, workers; then the collectors, which you do not command.
        drawUnitCount(graphics, 0, "screen.projecthivemind.hive.scouts", UnitKind.SCOUT);
        drawUnitCount(graphics, 1, "screen.projecthivemind.hive.soldiers", UnitKind.SOLDIER);
        drawUnitCount(graphics, 2, "screen.projecthivemind.hive.workers", UnitKind.WORKER);
        drawUnitCount(graphics, 3, "screen.projecthivemind.hive.collectors", UnitKind.COLLECTOR);

        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.armor"), HiveMenu.ARMOR_X, 44, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.storage"), HiveMenu.STORAGE_X, 44, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.crafting"), HiveMenu.GRID_X, 44, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.hive.tools"),
                HiveMenu.TOOLS_X + 5 * 18 + 6, HiveMenu.TOOLS_Y + 5, 0xA0A0A0, false);
    }

    /**
     * "Workers 1/1": units out now, out of the most the hive allows (orange at the limit). Underneath, what the hive's
     * next 10-second interval will do for them, with the countdown.
     */
    private void drawUnitCount(GuiGraphics graphics, int column, String labelKey, UnitKind kind) {
        int x = HiveMenu.STORAGE_X + column * COUNTS_SPACING;
        boolean atLimit = menu.unitCount(kind) >= menu.unitCap(kind);
        graphics.drawString(font, Component.translatable(labelKey, menu.unitCount(kind), menu.unitCap(kind)),
                x, COUNTS_Y, atLimit ? 0xFFAA00 : 0xFFFFFF, false);

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
        graphics.drawString(font, status, x, COUNTS_Y + 10, colour, false);
    }

    /** Health points as hearts: 2 points per heart, dropping a trailing ".0". */
    private static String hearts(int healthPoints) {
        float hearts = healthPoints / 2.0F;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.format(Locale.ROOT, "%.1f", hearts);
    }
}
