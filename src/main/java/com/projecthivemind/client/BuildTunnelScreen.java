package com.projecthivemind.client;

import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.build.BridgeJob;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.network.BuildTunnelPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The order for a tunnel: how big it is (1x2, 2x2, 3x3 or 5x5), which way it runs and how long it is, and the block laid where its floor is
 * missing, then Build. The workers and the start were picked before this opened. The block comes from the hive.
 */
public class BuildTunnelScreen extends Screen {
    private static final int WIDTH = 270;
    private static final int HEIGHT = 250;
    private static final String[] SIZE_NAMES = {"1x2", "2x2", "3x3", "5x5"};
    private static final Direction[] WAYS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final List<Integer> workers;
    private final BlockPos site;
    /** The construction block whose settings these are, when the screen is opened again from its menu (Options) rather than for a new tunnel. */
    @Nullable
    private BlockPos editing;
    private Item block = Items.COBBLESTONE;
    private int size = 3;
    private Direction way = Direction.NORTH;
    private int length = 16;
    private final Button[] sizeButtons = new Button[BridgeJob.TUNNEL_SIZE_COUNT];
    private final Button[] wayButtons = new Button[WAYS.length];
    private Button blockButton;
    private Button buildButton;
    private Button endlessButton;
    private final java.util.List<Button> lengthButtons = new java.util.ArrayList<>();
    /** The tunnel goes on until it is cancelled: a new stretch is dug whenever the last is done. */
    private boolean endless;

    public BuildTunnelScreen(List<Integer> workers, BlockPos site) {
        super(Component.translatable("screen.projecthivemind.tunnel.title"));
        this.workers = List.copyOf(workers);
        this.site = site.immutable();
        // It runs the way the camera is looking, to begin with.
        if (net.minecraft.client.Minecraft.getInstance().player != null) {
            this.way = net.minecraft.client.Minecraft.getInstance().player.getDirection();
        }
    }

    /** The settings of a tunnel under construction, to change: what is shown is what it was ordered with. */
    public BuildTunnelScreen(BlockPos construction, CompoundTag config) {
        super(Component.translatable("screen.projecthivemind.tunnel.title"));
        this.workers = List.of();
        this.site = construction.immutable();
        this.editing = construction.immutable();
        Item chosen = itemNamed(config.getString("Deck"));
        this.block = chosen != null ? chosen : Items.COBBLESTONE;
        this.size = Math.max(1, Math.min(BridgeJob.TUNNEL_SIZE_COUNT, config.getInt("TunnelSize")));
        this.length = Math.max(BridgeJob.TUNNEL_MIN_LENGTH, Math.min(BridgeJob.TUNNEL_MAX_LENGTH, config.getInt("Length")));
        this.way = Direction.from2DDataValue(config.getInt("Direction") & 3);
        this.endless = config.getBoolean("Endless");
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
        blockButton = addRenderableWidget(Button.builder(Component.empty(), button -> minecraft.setScreen(new SeedPickerScreen(this,
                Component.translatable("screen.projecthivemind.tunnel.block_title"), item -> HiveWorker.fillBlock(item) != null,
                item -> block = item != null ? item : block))).bounds(left + 12, top + 28, full, 20).build());
        int sizeCell = (full - 3 * 4) / 4;
        for (int i = 0; i < sizeButtons.length; i++) {
            int chosen = i + 1;
            sizeButtons[i] = addRenderableWidget(Button.builder(Component.literal(SIZE_NAMES[i]), button -> {
                size = chosen;
                refresh();
            }).bounds(left + 12 + i * (sizeCell + 4), top + 68, sizeCell, 20).build());
        }
        for (int i = 0; i < wayButtons.length; i++) {
            Direction chosen = WAYS[i];
            wayButtons[i] = addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tunnel.way." + chosen.getName()), button -> {
                way = chosen;
                refresh();
            }).bounds(left + 12 + i * (sizeCell + 4), top + 108, sizeCell, 20).build());
        }
        int step = (full - 3 * 4 - 90) / 4;
        int[] changes = {-10, -1, 1, 10};
        for (int i = 0; i < changes.length; i++) {
            int change = changes[i];
            int x = i < 2 ? left + 12 + i * (step + 4) : left + 12 + 2 * (step + 4) + 90 + 4 + (i - 2) * (step + 4);
            lengthButtons.add(addRenderableWidget(Button.builder(Component.literal(change > 0 ? "+" + change : String.valueOf(change)), button -> {
                length = Math.max(BridgeJob.TUNNEL_MIN_LENGTH, Math.min(BridgeJob.TUNNEL_MAX_LENGTH, length + change));
                refresh();
            }).bounds(x, top + 148, step, 20).build()));
        }
        endlessButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            endless = !endless;
            refresh();
        }).bounds(left + 12, top + 172, full, 20).build());
        buildButton = addRenderableWidget(Button.builder(Component.translatable(editing != null ? "screen.projecthivemind.construction.save" : "screen.projecthivemind.tunnel.build"), button -> {
            if (editing != null) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Deck", BuiltInRegistries.ITEM.getKey(block).toString());
                tag.putInt("TunnelSize", size);
                tag.putInt("Length", length);
                tag.putInt("Direction", way.get2DDataValue());
                tag.putBoolean("Endless", endless);
                PacketDistributor.sendToServer(new com.projecthivemind.network.UpdateConstructionPayload(editing, tag));
            } else {
                PacketDistributor.sendToServer(new BuildTunnelPayload(workers, site, way.get2DDataValue() | (endless ? 4 : 0), size, length,
                        BuiltInRegistries.ITEM.getKey(block).toString()));
            }
            onClose();
        }).bounds(left + 12, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.projecthivemind.tower.cancel"), button -> onClose())
                .bounds(left + 16 + (WIDTH - 28) / 2, top + HEIGHT - 30, (WIDTH - 28) / 2, 20).build());
        refresh();
    }

    /** Put the choices on the buttons: the chosen size and direction are the ones that cannot be pressed. */
    private void refresh() {
        blockButton.setMessage(Component.translatable("screen.projecthivemind.tunnel.block", block.getDescription()));
        endlessButton.setMessage(Component.translatable(endless ? "screen.projecthivemind.tunnel.endless.on" : "screen.projecthivemind.tunnel.endless.off"));
        endlessButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.projecthivemind.tunnel.endless.tooltip")));
        for (Button lengthButton : lengthButtons) {
            lengthButton.active = !endless;
        }
        for (int i = 0; i < sizeButtons.length; i++) {
            sizeButtons[i].active = i + 1 != size;
        }
        for (int i = 0; i < wayButtons.length; i++) {
            wayButtons[i].active = WAYS[i] != way;
        }
    }

    /** A picker that has just closed leaves a new choice behind: show it. */
    @Override
    public void tick() {
        super.tick();
        if (blockButton != null) {
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
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tunnel.size"), left + 12, top + 56, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tunnel.way"), left + 12, top + 96, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tunnel.length"), left + 12, top + 136, 0xA0A0A0, false);
        Component shown = Component.translatable("screen.projecthivemind.tunnel.blocks", length);
        int step = (WIDTH - 24 - 3 * 4 - 90) / 4;
        graphics.drawCenteredString(font, shown, left + 12 + 2 * (step + 4) + 45, top + 154, 0xFFFFFF);
        graphics.drawString(font, Component.translatable("screen.projecthivemind.tunnel.note"), left + 12, top + 200, 0x909090, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
