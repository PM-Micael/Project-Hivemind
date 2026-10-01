package com.projecthivemind.client;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.MoveUnitsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * RTS unit control with the free cursor: left click a unit to select it (again to deselect it), right click the
 * ground to send the selected units there.
 *
 * <p>The cursor position is turned into a ray through the world using the same projection and view matrices the
 * game rendered the last frame with, so the ray matches exactly what is under the cursor.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class HiveSelection {
    private static final double RAY_LENGTH = 400.0D;
    /** Makes small units (the collector) and thin ones easier to click. */
    private static final double PICK_MARGIN = 0.3D;

    // The last frame's camera, copied so it is safe to use from input handlers.
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    private static Vec3 cameraPosition = Vec3.ZERO;
    private static boolean haveCamera;

    private HiveSelection() {
    }

    /** A line from the camera through a point on the screen. */
    private record Ray(Vec3 from, Vec3 to) {
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            PROJECTION.set(event.getProjectionMatrix());
            VIEW.set(event.getModelViewMatrix());
            cameraPosition = event.getCamera().getPosition();
            haveCamera = true;
        }
    }

    /** Keep the player id current (units outline for their owner only) and forget units that are gone. */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ClientSelection.setLocalPlayer(minecraft.player.getUUID());

        Set<Integer> alive = new HashSet<>();
        for (int id : ClientSelection.selected()) {
            Entity entity = minecraft.level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                alive.add(id);
            }
        }
        ClientSelection.retain(alive);
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSelection.reset();
        haveCamera = false;
    }

    /**
     * HIGH priority: {@link HiveCamera} cancels every click so vanilla cannot grab the mouse, and a cancelled event
     * never reaches listeners that run after the cancel. This must see the click first.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!HiveCamera.controlling(minecraft) || HiveCamera.isRotating() || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            selectUnderCursor(minecraft);
        } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            moveSelectedToCursor(minecraft);
        }
    }

    // ---- selecting ----

    private static void selectUnderCursor(Minecraft minecraft) {
        Optional<Ray> ray = cursorRay(minecraft);
        if (ray.isEmpty()) {
            return;
        }
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            // Units show through walls, so they can be picked through walls too: no block check.
            if (!(entity instanceof HiveUnit unit) || unit.kind() == UnitKind.COLLECTOR
                    || !minecraft.player.getUUID().equals(unit.ownerId())) {
                continue;
            }
            AABB box = entity.getBoundingBox().inflate(PICK_MARGIN);
            Optional<Vec3> hit = box.clip(ray.get().from(), ray.get().to());
            if (hit.isPresent()) {
                double distance = ray.get().from().distanceToSqr(hit.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = entity;
                }
            }
        }
        if (closest != null) {
            ClientSelection.toggle(closest.getId());
        }
    }

    // ---- commanding ----

    private static void moveSelectedToCursor(Minecraft minecraft) {
        Set<Integer> selected = ClientSelection.selected();
        Optional<Ray> ray = cursorRay(minecraft);
        if (selected.isEmpty() || ray.isEmpty()) {
            return;
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.get().from(), ray.get().to(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
        if (hit.getType() == HitResult.Type.MISS) {
            return;
        }
        // Stand in the space just outside the face that was clicked: on top of the ground, or beside a wall.
        Vec3 target = Vec3.atBottomCenterOf(hit.getBlockPos().relative(hit.getDirection()));
        PacketDistributor.sendToServer(new MoveUnitsPayload(List.copyOf(selected), target.x, target.y, target.z));

        // A little puff where they are headed, so the command is visibly received.
        for (int i = 0; i < 8; i++) {
            double angle = i * (Math.PI / 4.0D);
            minecraft.level.addParticle(ParticleTypes.HAPPY_VILLAGER, target.x + Math.cos(angle) * 0.4D, target.y + 0.1D,
                    target.z + Math.sin(angle) * 0.4D, 0.0D, 0.02D, 0.0D);
        }
    }

    // ---- cursor to world ----

    private static Optional<Ray> cursorRay(Minecraft minecraft) {
        if (!haveCamera || minecraft.level == null || minecraft.player == null) {
            return Optional.empty();
        }
        long window = minecraft.getWindow().getWindow();
        double[] cursorX = new double[1];
        double[] cursorY = new double[1];
        GLFW.glfwGetCursorPos(window, cursorX, cursorY);
        int width = minecraft.getWindow().getScreenWidth();
        int height = minecraft.getWindow().getScreenHeight();

        // Unproject the cursor at the near and far planes. The view matrix has no translation (the world is drawn
        // relative to the camera), so add the camera position back to get world coordinates.
        Matrix4f combined = new Matrix4f(PROJECTION).mul(VIEW);
        int[] viewport = {0, 0, width, height};
        float windowY = (float) (height - cursorY[0]);
        Vector3f near = combined.unproject((float) cursorX[0], windowY, 0.0F, viewport, new Vector3f());
        Vector3f far = combined.unproject((float) cursorX[0], windowY, 1.0F, viewport, new Vector3f());

        Vec3 direction = new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
        Vec3 from = cameraPosition.add(near.x, near.y, near.z);
        return Optional.of(new Ray(from, from.add(direction.scale(RAY_LENGTH))));
    }
}
