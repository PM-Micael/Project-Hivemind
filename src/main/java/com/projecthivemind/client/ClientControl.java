package com.projecthivemind.client;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;
import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.network.ControlHotbarPayload;
import com.projecthivemind.network.ControlInputPayload;
import com.projecthivemind.network.ControlRequestPayload;
import com.projecthivemind.network.ControlSelectPayload;
import com.projecthivemind.network.DropItemPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The player's side of controlling a scout (see ScoutControl on the server). The game's own spectator camera puts the view in the scout's
 * eyes; this class turns what the player presses into the input the server drives the scout with, keeps the player's own entity from doing
 * anything (it is a camera, and must neither fly off nor sneak out of the scout), and draws what a player expects to see: a crosshair, a
 * hotbar and the scout's health.
 *
 * <p>Whether a scout is being controlled is simply whether the camera is one: the server sets it, so the two sides cannot disagree.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class ClientControl {
    private static final ResourceLocation HOTBAR = ResourceLocation.withDefaultNamespace("hud/hotbar");
    private static final ResourceLocation HOTBAR_SELECTION = ResourceLocation.withDefaultNamespace("hud/hotbar_selection");
    private static final ResourceLocation CROSSHAIR = ResourceLocation.withDefaultNamespace("hud/crosshair");

    private static final ItemStack[] STACKS = new ItemStack[ControlHotbarPayload.SLOTS];
    private static final int[] COUNTS = new int[ControlHotbarPayload.SLOTS];
    private static int selected;
    private static boolean wasActive;
    private static boolean ignoreAttack;
    private static final int DOUBLE_TAP_TICKS = 7;
    private static boolean sprinting;
    private static boolean wasForward;
    private static int tickCounter;
    private static int lastForwardPress = -100;

    static {
        java.util.Arrays.fill(STACKS, ItemStack.EMPTY);
    }

    private ClientControl() {
    }

    /** The scout the player controls, or null. */
    @Nullable
    public static HiveScout scout() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.getCameraEntity() instanceof HiveScout scout && scout.isAlive() ? scout : null;
    }

    public static boolean active() {
        return scout() != null;
    }

    /** Ask the server to hand over this scout. */
    public static void request(int scoutId) {
        PacketDistributor.sendToServer(new ControlRequestPayload(scoutId));
    }

    public static void setHotbar(List<ItemStack> stacks, List<Integer> counts, int selectedSlot) {
        for (int i = 0; i < STACKS.length; i++) {
            STACKS[i] = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
            COUNTS[i] = i < counts.size() ? counts.get(i) : 0;
        }
        selected = Mth.clamp(selectedSlot, 0, STACKS.length - 1);
    }

    private static void select(int slot) {
        selected = Math.floorMod(slot, STACKS.length);
        PacketDistributor.sendToServer(new ControlSelectPayload(selected));
    }

    // ---- input ----

    /** Before the game handles its own keys: the hotbar, drop and release keys are ours while a scout is controlled, and the input goes out. */
    @SubscribeEvent
    static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        HiveScout scout = scout();
        boolean active = scout != null;
        if (active != wasActive) {
            wasActive = active;
            if (active) {
                begin(minecraft, player, scout);
            } else {
                end(minecraft);
            }
        }
        if (!active) {
            return;
        }
        Options options = minecraft.options;
        boolean playing = minecraft.screen == null && minecraft.isWindowActive();
        if (minecraft.screen == null) {
            for (int i = 0; i < options.keyHotbarSlots.length; i++) {
                while (options.keyHotbarSlots[i].consumeClick()) {
                    select(i);
                }
            }
            while (options.keyDrop.consumeClick()) {
                PacketDistributor.sendToServer(new DropItemPayload(List.of(scout.getId())));
            }
            while (ClientEvents.RELEASE_CONTROL.consumeClick()) {
                request(-1);
            }
        }
        float forward = playing ? (options.keyUp.isDown() ? 1.0F : 0.0F) - (options.keyDown.isDown() ? 1.0F : 0.0F) : 0.0F;
        float strafe = playing ? (options.keyLeft.isDown() ? 1.0F : 0.0F) - (options.keyRight.isDown() ? 1.0F : 0.0F) : 0.0F;
        // Sprinting as in the vanilla game: the sprint key with forward held, or forward pressed twice in quick succession; it lasts until
        // forward is let go of (or sneaking starts).
        boolean forwardHeld = forward > 0.0F;
        if (forwardHeld && !wasForward) {
            if (tickCounter - lastForwardPress <= DOUBLE_TAP_TICKS) {
                sprinting = true;
            }
            lastForwardPress = tickCounter;
        }
        wasForward = forwardHeld;
        tickCounter++;
        if (forwardHeld && playing && options.keySprint.isDown()) {
            sprinting = true;
        }
        if (!forwardHeld || options.keyShift.isDown()) {
            sprinting = false;
        }
        // The click that chose "Take control" may still be down: it is not an attack until it has been let go of.
        if (ignoreAttack && !options.keyAttack.isDown()) {
            ignoreAttack = false;
        }
        int flags = 0;
        if (playing) {
            flags |= options.keyJump.isDown() ? ControlInputPayload.JUMP : 0;
            flags |= options.keyShift.isDown() ? ControlInputPayload.SNEAK : 0;
            flags |= sprinting ? ControlInputPayload.SPRINT : 0;
            flags |= options.keyAttack.isDown() && !ignoreAttack ? ControlInputPayload.ATTACK : 0;
            flags |= options.keyUse.isDown() ? ControlInputPayload.USE : 0;
        }
        PacketDistributor.sendToServer(new ControlInputPayload(forward, strafe, flags, player.getYRot(), player.getXRot()));
    }

    private static void begin(Minecraft minecraft, LocalPlayer player, HiveScout scout) {
        // Looking starts where the scout looks, not wherever the camera was.
        player.setYRot(scout.getYRot());
        player.setXRot(scout.getXRot());
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
        java.util.Arrays.fill(STACKS, ItemStack.EMPTY);
        java.util.Arrays.fill(COUNTS, 0);
        selected = 0;
        ignoreAttack = true;
        if (minecraft.screen == null) {
            minecraft.mouseHandler.grabMouse();
        }
        minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.control_hint",
                ClientEvents.RELEASE_CONTROL.getTranslatedKeyMessage()), false);
    }

    private static void end(Minecraft minecraft) {
        java.util.Arrays.fill(STACKS, ItemStack.EMPTY);
        java.util.Arrays.fill(COUNTS, 0);
        // The RTS camera takes the mouse back (HiveCamera releases it again on the next tick).
    }

    /**
     * The player's own entity is only a camera: its keys must not move it, and above all must not sneak, since a sneaking spectator comes out
     * of the entity it is watching. What the player presses goes to the scout instead.
     */
    @SubscribeEvent
    static void onMovementInput(MovementInputUpdateEvent event) {
        if (!active()) {
            return;
        }
        var input = event.getInput();
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    /** The mouse wheel picks the hotbar slot. */
    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active() || minecraft.screen != null) {
            return;
        }
        event.setCanceled(true);
        double delta = event.getScrollDeltaY();
        if (delta != 0.0D) {
            select(selected + (delta > 0.0D ? -1 : 1));
        }
    }

    /**
     * Each frame, so it is smooth: the scout's view is the player's mouse look. (The server sends its own idea of where the scout looks, a
     * little late; the player's look is what counts.)
     */
    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        HiveScout scout = scout();
        LocalPlayer player = Minecraft.getInstance().player;
        if (scout == null || player == null) {
            return;
        }
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        scout.setYRot(yaw);
        scout.yRotO = yaw;
        scout.setXRot(pitch);
        scout.xRotO = pitch;
        scout.yHeadRot = yaw;
        scout.yHeadRotO = yaw;
        scout.yBodyRot = yaw;
        scout.yBodyRotO = yaw;
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        wasActive = false;
        java.util.Arrays.fill(STACKS, ItemStack.EMPTY);
        java.util.Arrays.fill(COUNTS, 0);
    }

    // ---- drawing ----

    /** A GUI layer above everything else: crosshair, hotbar and the scout's health. */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        HiveScout scout = scout();
        if (scout == null || minecraft.options.hideGui) {
            return;
        }
        drawCrosshair(graphics);
        drawHotbar(graphics, minecraft);
        drawScoutHealth(graphics, minecraft, scout);
    }

    private static void drawCrosshair(GuiGraphics graphics) {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
        graphics.blitSprite(CROSSHAIR, (graphics.guiWidth() - 15) / 2, (graphics.guiHeight() - 15) / 2, 15, 15);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void drawHotbar(GuiGraphics graphics, Minecraft minecraft) {
        int left = graphics.guiWidth() / 2 - 91;
        int top = graphics.guiHeight() - 22;
        graphics.blitSprite(HOTBAR, left, top, 182, 22);
        graphics.blitSprite(HOTBAR_SELECTION, left - 1 + selected * 20, top - 1, 24, 23);
        for (int i = 0; i < STACKS.length; i++) {
            ItemStack stack = STACKS[i];
            if (stack.isEmpty()) {
                continue;
            }
            int x = left + 3 + i * 20;
            int y = top + 3;
            graphics.renderItem(stack, x, y);
            if (COUNTS[i] <= 0) {
                // The hive has run out of it: shown as it was, dimmed.
                graphics.fill(x, y, x + 16, y + 16, 0xA0000000);
            } else if (COUNTS[i] > 1) {
                graphics.renderItemDecorations(minecraft.font, stack, x, y, shortCount(COUNTS[i]));
            }
        }
    }

    /** A count that fits in a slot: up to 999, then thousands. */
    private static String shortCount(int count) {
        return count < 1000 ? String.valueOf(count) : count < 100000 ? (count / 1000) + "k" : "99k+";
    }

    private static void drawScoutHealth(GuiGraphics graphics, Minecraft minecraft, HiveScout scout) {
        float health = scout.getHealth();
        float max = scout.getMaxHealth();
        int width = 60;
        int left = graphics.guiWidth() / 2 + 91 + 8;
        int top = graphics.guiHeight() - 14;
        graphics.fill(left - 1, top - 1, left + width + 1, top + 7, 0xFF000000);
        graphics.fill(left, top, left + width, top + 6, 0xFF2A0707);
        int filled = Math.round(width * Mth.clamp(health / max, 0.0F, 1.0F));
        graphics.fill(left, top, left + filled, top + 6, 0xFFD01818);
        graphics.drawString(minecraft.font, Component.translatable("hud.projecthivemind.scout_health", Math.round(health), Math.round(max)),
                left, top - 10, 0xFFFFFF, true);
    }
}
