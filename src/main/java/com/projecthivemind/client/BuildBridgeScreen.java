package com.projecthivemind.client;

import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.build.BridgeJob;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.network.BuildBridgePayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The order for a bridge: what its deck is made of, whether it has fences along the edges and of what kind, and whether torches go
 * along the way, then Build. The workers and the destination were picked before this opened. The materials all come from the hive.
 */
public class BuildBridgeScreen extends Screen {
    private static final int WIDTH = 270;
    private static final int HEIGHT = 196;

    private final List<Integer> workers;
    private final BlockPos dest;
    /** The construction block whose settings these are, when the screen is opened again from its menu (Options) rather than for a new bridge. */
    @Nullable
    private BlockPos editing;
    @Nullable
    private Item deck;
    private Item fence = Items.OAK_FENCE;
    private boolean fences = true;
    private boolean torches = true;
    private int deckWidth = BridgeJob.MIN_WIDTH;
    private final Button[] widthButtons = new Button[BridgeJob.MAX_WIDTH - BridgeJob.MIN_WIDTH + 1];
    private Button deckButton;
    private Button fencesButton;
    private Button fenceTypeButton;
    private Button torchesButton;
    private Button buildButton;

    public BuildBridgeScreen(List<Integer> workers, BlockPos dest) {
        super(Component.translatable("screen.projecthivemind.bridge.title"));
        this.workers = List.copyOf(workers);
        this.dest = dest.immutable();
    }

    /** The settings of a bridge under construction, to change: what is shown is what it was ordered with. */
    public BuildBridgeScreen(BlockPos construction, net.minecraft.nbt.CompoundTag config) {
        super(Component.translatable("screen.projecthivemind.bridge.title"));
        this.workers = List.of();
        this.dest = construction.immutable();
        this.editing = construction.immutable();
        this.deck = itemNamed(config.getString("Deck"));
        this.fences = config.contains("Fence");
        Item chosenFence = fences ? itemNamed(config.getString("Fence")) : null;
        this.fence = chosenFence != null ? chosenFence : Items.OAK_FENCE;
        this.torches = config.getBoolean("Torches");
        this.deckWidth = Math.max(BridgeJob.MIN_WIDTH, Math.min(BridgeJob.MAX_WIDTH, config.getInt("Width")));
    }

    @Nullable
    private static Item itemNamed(String name) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(name);
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        int full = WIDTH - 24;
        deckButton = addRenderableWidget(Button.builder(Component.empty(), button -> minecraft.setScreen(new SeedPickerScreen(this,
                Component.translatable("screen.projecthivemind.bridge.deck_title"), item -> HiveWorker.fillBlock(item) != null,
                item -> deck = item != null ? item : deck))).bounds(left + 12, top + 28, full, 20).build());
        fencesButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            fences = !fences;
            refresh();
        }).bounds(left + 12, top + 52, full, 20).build());
        fenceTypeButton = addRenderableWidget(Button.builder(Component.empty(), button -> minecraft.setScreen(new SeedPickerScreen(this,
                Component.translatable("screen.projecthivemind.bridge.fence_title"), item -> HiveWorker.fenceBlock(item) != null,
                item -> fence = item != null ? item : fence))).bounds(left + 12, top + 76, full, 20).build());
        torchesButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            torches = !torches;
            refresh();
        }).bounds(left + 12, top + 100, full, 20).build());
        int cell = (full - 4 * 4) / 5;
        for (int i = 0; i < widthButtons.length; i++) {
            int chosen = BridgeJob.MIN_WIDTH + i;
            widthButtons[i] = addRenderableWidget(Button.builder(Component.literal(String.valueOf(chosen)), button -> {
                deckWidth = chosen;
                refresh();
            }).bounds(left + 12 + i * (cell + 4), top + 134, cell, 20).build());
        }
        buildButton = addRenderableWidget(Button.builder(Component.translatable(editing != null ? "screen.projecthivemind.construction.save" : "screen.projecthivemind.bridge.build"), button -> {
            if (deck != null) {
                if (editing != null) {
                    net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                    tag.putString("Deck", BuiltInRegistries.ITEM.getKey(deck).toString());
                    if (fences) {
                        tag.putString("Fence", BuiltInRegistries.ITEM.getKey(fence).toString());
                    }
                    tag.putBoolean("Torches", torches);
                    tag.putInt("Width", deckWidth);
                    PacketDistributor.sendToServer(new com.projecthivemind.network.UpdateConstructionPayload(editing, tag));
                } else {
                    PacketDistributor.sendToServer(new BuildBridgePayload(workers, dest, BuiltInRegistries.ITEM.getKey(deck).toString(),
                            fences ? BuiltInRegistries.ITEM.getKey(fence).toString() : "", torches, deckWidth));
                }
                onClose();
            }
        }).bounds(left + 12, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.cancel"), button -> onClose())
                .bounds(left + 16 + (WIDTH - 28) / 2, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        refresh();
    }

    /** Put the choices on the buttons, and only let Build be pressed once a deck block is chosen. */
    private void refresh() {
        deckButton.setMessage(Component.translatable("screen.projecthivemind.bridge.deck",
                deck == null ? Component.translatable("screen.projecthivemind.bridge.choose") : deck.getDescription()));
        fencesButton.setMessage(Component.translatable(fences ? "screen.projecthivemind.bridge.fences_on" : "screen.projecthivemind.bridge.fences_off"));
        fenceTypeButton.setMessage(Component.translatable("screen.projecthivemind.bridge.fence_type", fence.getDescription()));
        fenceTypeButton.active = fences;
        torchesButton.setMessage(Component.translatable(torches ? "screen.projecthivemind.bridge.torches_on" : "screen.projecthivemind.bridge.torches_off"));
        buildButton.active = deck != null;
        for (int i = 0; i < widthButtons.length; i++) {
            widthButtons[i].active = BridgeJob.MIN_WIDTH + i != deckWidth;
        }
    }

    /** A picker that has just closed leaves new choices behind: show them. */
    @Override
    public void tick() {
        super.tick();
        if (deckButton != null) {
            refresh();
        }
    }

    /** The panel and its text are drawn here, as part of the background, so that the blur does not cover them. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = (height - HEIGHT) / 2;
        HiveStyle.panel(graphics, left, top, WIDTH, HEIGHT);
        graphics.drawString(font, title, left + 12, top + 12, HiveStyle.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.bridge.width"), left + 12, top + 124, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.bridge.note"), left + 12, top + 158, 0x909090, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
