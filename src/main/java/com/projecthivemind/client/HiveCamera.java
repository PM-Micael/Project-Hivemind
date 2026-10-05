package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.DropItemPayload;

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
import net.neoforged.neoforge.network.PacketDistributor;
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
 *   <li>lets the player hold the rotate control (middle mouse button by default, see Controls) to rotate and tilt the view with the mouse;</li>
 *   <li>rotates the view with Q and R as a keyboard alternative;</li>
 *   <li>turns the scroll wheel into zoom (moving the camera up and down);</li>
 *   <li>hides the survival HUD.</li>
 * </ul>
 * WASD (pan) and Space/Shift (up/down) are vanilla flight controls, which already move horizontally relative to yaw.
 * The starting angle is set by the server when the Hive Heart is placed; after that the angle belongs to the player.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class HiveCamera {
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

    /**
     * The drop key (Q) in the RTS view: every selected scout drops one item from its hand. (It used to turn the camera;
     * R still does, and so does dragging with the middle mouse button.)
     */
    @SubscribeEvent
    static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (controlling(minecraft) && !ClientControl.active() && isRotateKey(event.getKey())) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                startRotating(minecraft);
            } else if (event.getAction() == GLFW.GLFW_RELEASE && !isRotateHeld(minecraft)) {
                stopRotating(minecraft);
            }
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS || event.getKey() != GLFW.GLFW_KEY_Q || !controlling(minecraft) || ClientControl.active()
                || minecraft.level == null) {
            return;
        }
        List<Integer> scouts = new ArrayList<>();
        for (int id : ClientSelection.selected()) {
            if (minecraft.level.getEntity(id) instanceof HiveUnit unit && unit.kind() == UnitKind.SCOUT) {
                scouts.add(id);
            }
        }
        if (!scouts.isEmpty()) {
            PacketDistributor.sendToServer(new DropItemPayload(scouts));
        }
    }

    private HiveCamera() {
    }

    /**
     * True while the RTS view is in control: hive stage, in a world, and no menu open. Over the view of a scout the player controls, only while the
     * rotate control is held (the cursor is out): then clicks are commands, as in the strategy view.
     */
    static boolean controlling(Minecraft minecraft) {
        return ClientState.hiveMode() && minecraft.player != null && minecraft.screen == null && (!ClientControl.active() || ClientControl.cursorMode());
    }

    /** The two controls that rotate the view (Controls: "Rotate camera" and the second one beside it). */
    private static final net.minecraft.client.KeyMapping[] ROTATE_CONTROLS = {ClientEvents.ROTATE_CAMERA, ClientEvents.ROTATE_CAMERA_ALT};

    /** True if this keyboard key is one of the controls set to rotate the view. */
    private static boolean isRotateKey(int key) {
        for (net.minecraft.client.KeyMapping control : ROTATE_CONTROLS) {
            if (!control.isUnbound() && control.getKey().getType() == InputConstants.Type.KEYSYM && control.getKey().getValue() == key) {
                return true;
            }
        }
        return false;
    }

    /** True if this mouse button is the one the player has set to rotate the view (Controls: "Rotate camera"). */
    static boolean isRotateMouse(int button) {
        for (net.minecraft.client.KeyMapping control : ROTATE_CONTROLS) {
            com.mojang.blaze3d.platform.InputConstants.Key key = control.getKey();
            if (!control.isUnbound() && key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE && key.getValue() == button) {
                return true;
            }
        }
        return false;
    }

    /** True while the control set to rotate the view is held, whether it is a mouse button or a key. */
    static boolean isRotateHeld(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        for (net.minecraft.client.KeyMapping control : ROTATE_CONTROLS) {
            com.mojang.blaze3d.platform.InputConstants.Key key = control.getKey();
            if (control.isUnbound()) {
                continue;
            }
            boolean held = key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
                    ? GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS
                    : InputConstants.isKeyDown(window, key.getValue());
            if (held) {
                return true;
            }
        }
        return false;
    }

    /** True while the rotate control is held to turn the view; clicks are not commands then. */
    static boolean isRotating() {
        return rotating;
    }

    private static void startRotating(Minecraft minecraft) {
        if (rotating) {
            return; // already turning because the other control is held; keep the first cursor position
        }
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
            boolean stillHeld = isRotateHeld(minecraft);
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
     * The rotate control held (the middle mouse button unless the player changed it) = rotate. Every other click is swallowed until the hive has its own click commands,
     * because vanilla would re-grab the mouse (or attack/use) on a click.
     */
    @SubscribeEvent
    static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!controlling(minecraft)) {
            return;
        }
        event.setCanceled(true);
        if (!ClientControl.active() && isRotateMouse(event.getButton())) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                startRotating(minecraft);
            } else if (event.getAction() == GLFW.GLFW_RELEASE && !isRotateHeld(minecraft)) {
                stopRotating(minecraft);
            }
        }
    }

    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!controlling(minecraft) || ClientControl.active()) {
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
        if (!controlling(minecraft) || ClientControl.active()) {
            return;
        }
        LocalPlayer player = minecraft.player;
        long window = minecraft.getWindow().getWindow();

        float direction = 0.0F;
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R)) {
            direction += 1.0F;
        }
        if (direction != 0.0F) {
            player.turn(direction * ROTATE_DEGREES_PER_SECOND * seconds / MOUSE_UNITS_TO_DEGREES, 0.0D);
        }
    }

    @SubscribeEvent
    static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (ClientState.hiveMode() && HIDDEN_HUD_LAYERS.contains(event.getName())) {
            event.setCanceled(true);
        }
    }
}
