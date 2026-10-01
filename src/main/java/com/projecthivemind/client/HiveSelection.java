package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

import com.projecthivemind.BlockAction;
import com.projecthivemind.MobAction;
import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.BlockActionPayload;
import com.projecthivemind.network.MobActionPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
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
 * RTS unit control with the free cursor. Left click is the quick command: click one of your units to select it (again
 * to deselect it), click a block to send the selected units there, click a mob outside the hive to attack it. Right
 * click opens a context menu with every choice for what is under the cursor.
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

        // Any screen, or leaving hive mode, dismisses the context menu.
        if (ContextMenu.isOpen() && !HiveCamera.controlling(minecraft)) {
            ContextMenu.close();
        }

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
        ClientActions.reset();
        ContextMenu.close();
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
        int button = event.getButton();

        // While the context menu is up, a click is for the menu: left picks an option, anything else dismisses it.
        // A right click elsewhere carries on below, so it opens a fresh menu where the cursor now is.
        if (ContextMenu.isOpen()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                ContextMenu.click(minecraft);
                return;
            }
            ContextMenu.close();
            if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                return;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            leftClick(minecraft);
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            openMenuUnderCursor(minecraft);
        }
    }

    /**
     * Left click is the quick command. In order: one of your own units is selected or deselected; a mob outside the
     * hive is attacked by the selected soldiers; a block is walked to by every selected unit. The right-click menu has
     * the full list of choices.
     */
    private static void leftClick(Minecraft minecraft) {
        Optional<Ray> ray = cursorRay(minecraft);
        if (ray.isEmpty()) {
            return;
        }
        if (toggleUnitUnderCursor(minecraft, ray.get())) {
            return;
        }

        BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.get().from(), ray.get().to(),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        boolean blockHit = hit.getType() != HitResult.Type.MISS;
        MobPick mob = pickMob(minecraft, ray.get(), true);
        boolean mobHit = mob != null && (!blockHit || mob.distanceSqr() < ray.get().from().distanceToSqr(hit.getLocation()));
        if (!mobHit && !blockHit) {
            return;
        }

        List<Integer> selected = List.copyOf(ClientSelection.selected());
        if (selected.isEmpty()) {
            minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.select_units_first"), false);
            return;
        }
        if (mobHit) {
            if (!selectionHas(minecraft, selected, UnitKind.SOLDIER)) {
                minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.no_soldiers"), false);
                return;
            }
            PacketDistributor.sendToServer(new MobActionPayload(selected, mob.mob().getId(), MobAction.ATTACK));
        } else {
            PacketDistributor.sendToServer(new BlockActionPayload(selected, hit.getBlockPos(), BlockAction.WALK_TO, false));
            puffAbove(hit.getBlockPos());
        }
    }

    // ---- selecting ----

    /** Select or deselect the nearest of your own units under the cursor. Returns false if there is none. */
    private static boolean toggleUnitUnderCursor(Minecraft minecraft, Ray ray) {
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            // Units show through walls, so they can be picked through walls too: no block check.
            if (!(entity instanceof HiveUnit unit) || unit.kind() == UnitKind.COLLECTOR
                    || !minecraft.player.getUUID().equals(unit.ownerId())) {
                continue;
            }
            AABB box = entity.getBoundingBox().inflate(PICK_MARGIN);
            Optional<Vec3> hit = box.clip(ray.from(), ray.to());
            if (hit.isPresent()) {
                double distance = ray.from().distanceToSqr(hit.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = entity;
                }
            }
        }
        if (closest == null) {
            return false;
        }
        ClientSelection.toggle(closest.getId());
        return true;
    }

    // ---- commanding ----

    /**
     * Right click: open the context menu for whatever is under the cursor, a mob or a block, whichever is nearer along
     * the line of sight. The options depend on what is there, what is selected, and what units are already doing.
     */
    private static void openMenuUnderCursor(Minecraft minecraft) {
        Optional<Ray> ray = cursorRay(minecraft);
        if (ray.isEmpty()) {
            return;
        }
        List<Integer> selected = List.copyOf(ClientSelection.selected());

        // OUTLINE, not COLLIDER: thin things like flowers and tall grass can be picked too.
        BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.get().from(), ray.get().to(),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        boolean blockHit = hit.getType() != HitResult.Type.MISS;

        // A mob in front of the block (or with no block behind it) is what was clicked; a mob behind a block is not.
        MobPick mob = pickMob(minecraft, ray.get(), false);
        if (mob != null && (!blockHit || mob.distanceSqr() < ray.get().from().distanceToSqr(hit.getLocation()))) {
            openMobMenu(minecraft, mob.mob(), selected);
            return;
        }
        if (!blockHit) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        boolean working = ClientActions.isActive(pos);
        if (selected.isEmpty() && !working) {
            minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.select_units_first"), false);
            return;
        }

        List<ContextMenu.Option> options = new ArrayList<>();
        if (!selected.isEmpty()) {
            options.add(option("action.projecthivemind.walk_to", selected, pos, BlockAction.WALK_TO));
            // Digging and interacting are worker jobs: only offer them if a worker is part of the selection.
            if (selectionHas(minecraft, selected, UnitKind.WORKER)) {
                options.add(option("action.projecthivemind.dig", selected, pos, BlockAction.DIG));
                options.add(option("action.projecthivemind.interact", selected, pos, BlockAction.INTERACT));
            }
        }
        if (working) {
            options.add(option("action.projecthivemind.cancel", List.of(), pos, BlockAction.CANCEL));
        }
        int[] cursor = ContextMenu.cursor(minecraft);
        ContextMenu.open(minecraft, cursor[0], cursor[1], options, pos, -1);
    }

    /** The mob under the cursor and how far away along the ray it was hit. */
    private record MobPick(Mob mob, double distanceSqr) {
    }

    /**
     * The nearest mob the ray passes through that can be targeted. Your own units and Hive Hearts never are. With
     * {@code nonHiveOnly} no hive unit is, whoever owns it: that is what the left-click quick attack uses, while the
     * right-click menu still offers Attack on another player's units. Unlike unit selection this respects walls: the
     * caller compares the distance with the nearest block, so a mob behind a block is not picked.
     */
    @Nullable
    private static MobPick pickMob(Minecraft minecraft, Ray ray, boolean nonHiveOnly) {
        MobPick closest = null;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || !mob.isAlive() || mob instanceof HiveHeart) {
                continue;
            }
            if (mob instanceof HiveUnit unit && (nonHiveOnly || minecraft.player.getUUID().equals(unit.ownerId()))) {
                continue;
            }
            Optional<Vec3> hit = mob.getBoundingBox().inflate(PICK_MARGIN).clip(ray.from(), ray.to());
            if (hit.isPresent()) {
                double distance = ray.from().distanceToSqr(hit.get());
                if (closest == null || distance < closest.distanceSqr()) {
                    closest = new MobPick(mob, distance);
                }
            }
        }
        return closest;
    }

    /** The menu for a mob: Attack (soldiers only), and Cancel on a mob that units are already attacking. */
    private static void openMobMenu(Minecraft minecraft, Mob mob, List<Integer> selected) {
        boolean attacked = ClientActions.isAttacked(mob.getId());
        boolean hasSoldier = selectionHas(minecraft, selected, UnitKind.SOLDIER);

        List<ContextMenu.Option> options = new ArrayList<>();
        if (hasSoldier) {
            options.add(mobOption("action.projecthivemind.attack", selected, mob, MobAction.ATTACK));
        }
        if (attacked) {
            options.add(mobOption("action.projecthivemind.cancel", List.of(), mob, MobAction.CANCEL));
        }
        if (options.isEmpty()) {
            String message = selected.isEmpty() ? "message.projecthivemind.select_units_first" : "message.projecthivemind.no_soldiers";
            minecraft.gui.setOverlayMessage(Component.translatable(message), false);
            return;
        }
        int[] cursor = ContextMenu.cursor(minecraft);
        ContextMenu.open(minecraft, cursor[0], cursor[1], options, null, mob.getId());
    }

    private static ContextMenu.Option mobOption(String labelKey, List<Integer> units, Mob mob, MobAction action) {
        return new ContextMenu.Option(Component.translatable(labelKey),
                () -> PacketDistributor.sendToServer(new MobActionPayload(units, mob.getId(), action)));
    }

    /** True if any of the selected units is of this kind. */
    private static boolean selectionHas(Minecraft minecraft, List<Integer> selected, UnitKind kind) {
        for (int id : selected) {
            if (minecraft.level.getEntity(id) instanceof HiveUnit unit && unit.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static ContextMenu.Option option(String labelKey, List<Integer> units, BlockPos pos, BlockAction action) {
        return new ContextMenu.Option(Component.translatable(labelKey), () -> {
            PacketDistributor.sendToServer(new BlockActionPayload(units, pos, action, false));
            if (action == BlockAction.WALK_TO) {
                puffAbove(pos);
            }
        });
    }

    /** A little puff where the units are headed, so the command is visibly received. */
    private static void puffAbove(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 target = Vec3.atBottomCenterOf(pos.above());
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
