package com.projecthivemind.client;

import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.projecthivemind.ProjectHivemind;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The RTS camera for a bodyless hivemind: a top-down view with a free mouse cursor.
 *
 * <p>The player entity is the camera. The server keeps it in spectator mode, which gives collision-less flight and
 * keeps the chunks around the camera loaded. On top of that this class:
 * <ul>
 *   <li>keeps the mouse cursor free instead of grabbed, and stops clicks from doing vanilla things;</li>
 *   <li>lets the player hold the middle mouse button to rotate and tilt the view with the mouse;</li>
 *   <li>rotates the view with Q and R as a keyboard alternative;</li>
 *   <li>turns the scroll wheel into zoom (moving the camera up and down);</li>
 *   <li>hides the survival HUD.</li>
 * </ul>
 * WASD (pan) and Space/Shift (up/down) are vanilla flight controls, which already move horizontally relative to yaw.
 * The starting angle is set by the server when the Hive Heart is placed; after that the angle belongs to the player.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class HiveCamera {
    /** Keep the tilt between almost-horizontal and almost-straight-down so the view cannot flip over. */
    private static final float MIN_PITCH = 5.0F;
    private static final float MAX_PITCH = 88.0F;
    private static final float ROTATE_DEGREES_PER_SECOND = 120.0F;
    /** Upward speed added per scroll notch; vanilla flight friction turns this into roughly 1.5 blocks. */
    private static final double ZOOM_IMPULSE = 0.6D;
    /** Entity#turn takes mouse units, which it scales by this factor into degrees. */
    private static final double MOUSE_UNITS_TO_DEGREES = 0.15D;

    private static final Set<ResourceLocation> HIDDEN_HUD_LAYERS = Set.of(
            VanillaGuiLayers.CROSSHAIR,
            VanillaGuiLayers.HOTBAR,
            VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.SPECTATOR_TOOLTIP,
            VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.FOOD_LEVEL,
            VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.VEHICLE_HEALTH,
            VanillaGuiLayers.EXPERIENCE_BAR,
            VanillaGuiLayers.EXPERIENCE_LEVEL);

    private static long lastFrameNanos;
    /** Where the cursor was (window coordinates) when a screen last opened over the RTS view, or null. */
    private static double[] cursorBeforeScreen;

    /** True while the middle mouse button is held and the mouse is captured for rotating. */
    private static boolean rotating;
    private static double cursorXBeforeRotate;
    private static double cursorYBeforeRotate;

    private HiveCamera() {
    }

    /** True while the RTS view is in control: hive stage, in a world, and no menu open. */
    static boolean controlling(Minecraft minecraft) {
        return ClientState.hiveMode() && minecraft.player != null && minecraft.screen == null;
    }

    /** True while the middle mouse button is held to rotate the view; clicks are not commands then. */
    static boolean isRotating() {
        return rotating;
    }

    private static void startRotating(Minecraft minecraft) {
        cursorXBeforeRotate = minecraft.mouseHandler.xpos();
        cursorYBeforeRotate = minecraft.mouseHandler.ypos();
        rotating = true;
        // Capturing the mouse hides the cursor and lets vanilla mouse-look drive the player's yaw and pitch.
        minecraft.mouseHandler.grabMouse();
    }

    private static void stopRotating(Minecraft minecraft) {
        if (!rotating) {
            return;
        }
        rotating = false;
        minecraft.mouseHandler.releaseMouse();
        // Releasing recentres the cursor; put it back where the player had it before the drag.
        GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), cursorXBeforeRotate, cursorYBeforeRotate);
    }

    /** Vanilla re-grabs the mouse whenever a menu closes or the window refocuses; let go of it again. */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (rotating) {
            // End the drag if the button came up while we weren't looking (menu opened, focus lost, left the hive...).
            boolean stillHeld = GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
            if (!stillHeld || !controlling(minecraft)) {
                stopRotating(minecraft);
            }
            return;
        }
        if (controlling(minecraft) && minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
            // Closing a screen recentres the cursor; put it back where it was when the screen opened.
            if (cursorBeforeScreen != null) {
                GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), cursorBeforeScreen[0], cursorBeforeScreen[1]);
                cursorBeforeScreen = null;
            }
        }
    }

    /** Remember where the cursor is as a screen opens over the RTS view, so it can go back there afterwards. */
    @SubscribeEvent
    static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ClientState.hiveMode() && minecraft.screen == null && !minecraft.mouseHandler.isMouseGrabbed()) {
            double[] x = new double[1];
            double[] y = new double[1];
            GLFW.glfwGetCursorPos(minecraft.getWindow().getWindow(), x, y);
            cursorBeforeScreen = new double[]{x[0], y[0]};
        }
    }

    /**
     * Middle mouse button held = rotate. Every other click is swallowed until the hive has its own click commands,
     * because vanilla would re-grab the mouse (or attack/use) on a click.
     */
    @SubscribeEvent
    static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!controlling(minecraft)) {
            return;
        }
        event.setCanceled(true);
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                startRotating(minecraft);
            } else if (event.getAction() == GLFW.GLFW_RELEASE) {
                stopRotating(minecraft);
            }
        }
    }

    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!controlling(minecraft)) {
            return;
        }
        event.setCanceled(true);
        LocalPlayer player = minecraft.player;
        Vec3 motion = player.getDeltaMovement();
        // Scroll up zooms in (camera goes down), scroll down zooms out.
        player.setDeltaMovement(motion.x, motion.y - event.getScrollDeltaY() * ZOOM_IMPULSE, motion.z);
    }

    /** Per-frame (not per-tick) so rotation stays smooth. */
    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        long now = Util.getNanos();
        float seconds = lastFrameNanos == 0L ? 0.0F : Math.min((now - lastFrameNanos) / 1.0E9F, 0.1F);
        lastFrameNanos = now;

        Minecraft minecraft = Minecraft.getInstance();
        if (!controlling(minecraft)) {
            return;
        }
        LocalPlayer player = minecraft.player;
        long window = minecraft.getWindow().getWindow();

        float direction = 0.0F;
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Q)) {
            direction -= 1.0F;
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R)) {
            direction += 1.0F;
        }
        if (direction != 0.0F) {
            player.turn(direction * ROTATE_DEGREES_PER_SECOND * seconds / MOUSE_UNITS_TO_DEGREES, 0.0D);
        }

        // Mouse-look (while dragging) can tilt past the limits; pull it back. Entity#turn keeps the old rotation in
        // step so the view does not stutter.
        float pitch = player.getXRot();
        float clamped = Mth.clamp(pitch, MIN_PITCH, MAX_PITCH);
        if (clamped != pitch) {
            player.turn(0.0D, (clamped - pitch) / MOUSE_UNITS_TO_DEGREES);
        }
    }

    @SubscribeEvent
    static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (ClientState.hiveMode() && HIDDEN_HUD_LAYERS.contains(event.getName())) {
            event.setCanceled(true);
        }
    }
}
