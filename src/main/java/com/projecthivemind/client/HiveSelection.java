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
import com.projecthivemind.ScoutItems;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.network.OpenHiveMenuPayload;
import com.projecthivemind.network.ReturnToBasePayload;
import com.projecthivemind.network.ScoutUsePayload;
import com.projecthivemind.network.SetCollectorTaskPayload;
import com.projecthivemind.network.SyncUnitsPayload;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.BlockActionPayload;
import com.projecthivemind.network.MobActionPayload;
import com.projecthivemind.network.SelectionPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
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
    /** The selection as the server was last told it. */
    private static Set<Integer> lastSentSelection = Set.of();

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


    /** Units picked with a team hotkey that the client may not have loaded yet, and the player tick until which they are waited for. */
    private static final Set<Integer> PENDING = new HashSet<>();
    private static int pendingUntil;

    /** Wait for these units to be loaded (the camera is on its way to them) before forgetting them for not being there. */
    static void expectUnits(Set<Integer> ids, int untilTick) {
        PENDING.clear();
        PENDING.addAll(ids);
        pendingUntil = untilTick;
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
            // A unit far away is not loaded on this client (there is no entity for it), but the hive still knows it: it stays selected for as long
            // as the hive lists it, so it can be picked and then gone to. It is only forgotten when it is dead or gone.
            boolean exists = entity != null ? entity.isAlive() : ClientUnits.entry(id) != null;
            // Units following a team scout cannot be selected: the team does what the scout is told.
            if (exists && !isTeamFollower(id)) {
                alive.add(id);
            }
        }
        ClientSelection.retain(alive);

        // Tell the server what is selected whenever that changes: selected units follow orders only, so the server
        // has to leave them out of the hive's default behaviour.
        Set<Integer> selection = ClientSelection.selected();
        if (!selection.equals(lastSentSelection)) {
            lastSentSelection = selection;
            PacketDistributor.sendToServer(new SelectionPayload(List.copyOf(selection)));
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSelection.reset();
        ClientActions.reset();
        ContextMenu.close();
        haveCamera = false;
        lastSentSelection = Set.of();
    }

    /**
     * HIGH priority: {@link HiveCamera} cancels every click so vanilla cannot grab the mouse, and a cancelled event
     * never reaches listeners that run after the cancel. This must see the click first.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!HiveCamera.controlling(minecraft) || HiveCamera.isRotating() || event.getAction() != GLFW.GLFW_PRESS
                || HiveCamera.isRotateMouse(event.getButton())) {
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
        if (hasPassiveSelected(minecraft, selected)) {
            // A selected collector only takes tasks from the right-click menu.
            return;
        }
        if (selected.isEmpty()) {
            minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.select_units_first"), false);
            return;
        }
        if (mobHit) {
            // Scouts trade with a villager; soldiers attack anything else.
            if (mob.mob() instanceof AbstractVillager && selectionHas(minecraft, selected, UnitKind.SCOUT)) {
                PacketDistributor.sendToServer(new MobActionPayload(selected, mob.mob().getId(), MobAction.TRADE));
                return;
            }
            if (!selectionHas(minecraft, selected, UnitKind.SOLDIER) && !selectionHas(minecraft, selected, UnitKind.SCOUT)) {
                minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.no_soldiers"), false);
                return;
            }
            PacketDistributor.sendToServer(new MobActionPayload(selected, mob.mob().getId(), MobAction.ATTACK));
        } else {
            BlockPos pos = hit.getBlockPos();
            List<Integer> scouts = unitsOfKind(minecraft, selected, UnitKind.SCOUT);
            if (!scouts.isEmpty() && minecraft.level.getBlockEntity(pos) instanceof Container) {
                // Scouts open a container; everyone else selected just walks to it.
                PacketDistributor.sendToServer(new BlockActionPayload(scouts, pos, BlockAction.INTERACT, false));
                List<Integer> others = new ArrayList<>(selected);
                others.removeAll(scouts);
                if (!others.isEmpty()) {
                    PacketDistributor.sendToServer(new BlockActionPayload(others, pos, BlockAction.WALK_TO, false));
                }
            } else {
                PacketDistributor.sendToServer(new BlockActionPayload(selected, pos, BlockAction.WALK_TO, false));
            }
            puffAbove(pos);
        }
    }

    /** The ids among these units that are of this kind. */
    /** True if one of these units cannot be commanded (a collector or a feeder): it is selected alone, and takes no orders from clicks. */
    private static boolean hasPassiveSelected(Minecraft minecraft, List<Integer> ids) {
        for (UnitKind kind : UnitKind.values()) {
            if (kind.passive() && !unitsOfKind(minecraft, ids, kind).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The selected workers, and the workers of the team of each selected scout (those follow it, and build when it is told to). */
    private static List<Integer> withTeamWorkers(Minecraft minecraft, List<Integer> selected) {
        List<Integer> workers = new ArrayList<>(unitsOfKind(minecraft, selected, UnitKind.WORKER));
        for (int scoutId : unitsOfKind(minecraft, selected, UnitKind.SCOUT)) {
            SyncUnitsPayload.Entry scout = ClientUnits.entry(scoutId);
            if (scout == null || scout.teamIndex() < 0) {
                continue;
            }
            for (SyncUnitsPayload.Entry entry : ClientUnits.all()) {
                if (entry.kind() == UnitKind.WORKER.ordinal() && entry.teamIndex() == scout.teamIndex() && !entry.away() && !workers.contains(entry.entityId())) {
                    workers.add(entry.entityId());
                }
            }
        }
        return workers;
    }

    private static List<Integer> unitsOfKind(Minecraft minecraft, List<Integer> ids, UnitKind kind) {
        List<Integer> result = new ArrayList<>();
        for (int id : ids) {
            if (minecraft.level.getEntity(id) instanceof HiveUnit unit && unit.kind() == kind) {
                result.add(id);
            }
        }
        return result;
    }

    // ---- selecting ----

    /** Select or deselect the nearest of your own units under the cursor. Returns false if there is none. */
    private static boolean toggleUnitUnderCursor(Minecraft minecraft, Ray ray) {
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            // Units show through walls, so they can be picked through walls too: no block check.
            if (!(entity instanceof HiveUnit unit)
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
        // A collector is selected on its own, one at a time, only to give it tasks inside the hive border; nothing else
        // can be selected with it. Selecting anything else lets it go.
        boolean collector = closest instanceof HiveUnit pickedUnit && pickedUnit.kind().passive();
        if (collector) {
            boolean wasSelected = ClientSelection.isSelected(closest.getId());
            ClientSelection.retain(Set.of());
            if (!wasSelected) {
                ClientSelection.select(closest.getId());
            }
            return true;
        }
        for (int id : List.copyOf(ClientSelection.selected())) {
            if (minecraft.level.getEntity(id) instanceof HiveUnit other && other.kind().passive()) {
                ClientSelection.deselect(id);
            }
        }
        ClientSelection.toggle(closest.getId());
        return true;
    }

    /** True for a unit in a team with a scout that is not that scout: it follows the scout, and cannot be selected by clicking. */
    private static boolean isTeamFollower(int entityId) {
        com.projecthivemind.network.SyncUnitsPayload.Entry entry = ClientUnits.entry(entityId);
        if (entry == null || entry.teamIndex() < 0 || entry.kind() == UnitKind.SCOUT.ordinal()) {
            return false;
        }
        return ClientUnits.all().stream().anyMatch(other -> other.kind() == UnitKind.SCOUT.ordinal() && other.teamIndex() == entry.teamIndex());
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
        // One of the player's own units under the cursor: its menu is offered, whatever is behind it.
        HiveUnit ownUnit = unitUnderCursor(minecraft);
        if (ownUnit instanceof Entity ownEntity) {
            List<ContextMenu.Option> unitOptions = new ArrayList<>(List.of(new ContextMenu.Option(Component.translatable("action.projecthivemind.open_menu"), () -> {
                HiveScreen.requestUnitPage(ownUnit.kind(), ownEntity.getId());
                PacketDistributor.sendToServer(new OpenHiveMenuPayload());
            }), new ContextMenu.Option(Component.translatable("action.projecthivemind.return_to_base"), () -> {
                ClientSelection.deselect(ownEntity.getId());
                PacketDistributor.sendToServer(new ReturnToBasePayload(ownEntity.getId()));
            })));
            // With soldiers selected, another of the player's units (not a soldier) can be given a bodyguard.
            List<Integer> selectedNow = List.copyOf(ClientSelection.selected());
            List<Integer> guards = unitsOfKind(minecraft, selectedNow, UnitKind.SOLDIER);
            if (!guards.isEmpty() && ownUnit.kind() != UnitKind.SOLDIER) {
                unitOptions.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.guard"),
                        () -> PacketDistributor.sendToServer(new MobActionPayload(selectedNow, ownEntity.getId(), MobAction.GUARD))));
            }
            // A unit with a job (or a bridge, wall or staircase to build) can be told to drop it.
            com.projecthivemind.network.SyncUnitsPayload.Entry ownEntry = ClientUnits.entry(ownEntity.getId());
            if (ownEntry != null && ownEntry.hasJob()) {
                unitOptions.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.cancel_job"),
                        () -> PacketDistributor.sendToServer(new com.projecthivemind.network.CancelJobPayload(ownEntity.getId()))));
            }
            if (ownUnit.kind() == UnitKind.WORKER) {
                // A worker can be given the wall round the hive: pick what it is built from.
                unitOptions.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.build_wall"),
                        () -> minecraft.setScreen(new SeedPickerScreen(null, Component.translatable("screen.projecthivemind.wall.title"),
                                item -> com.projecthivemind.entity.HiveWorker.fillBlock(item) != null,
                                item -> PacketDistributor.sendToServer(new com.projecthivemind.network.BuildWallPayload(ownEntity.getId(),
                                        item == null ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()))))));
            }
            int[] unitCursor = ContextMenu.cursor(minecraft);
            ContextMenu.open(minecraft, unitCursor[0], unitCursor[1], unitOptions, null, ownEntity.getId());
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
        // A hive portal has its own menu: whether it brings back the units of its team when they die.
        int portalIndex = portalIndexAt(minecraft, hit.getBlockPos());
        if (portalIndex >= 0) {
            boolean on = ClientPortals.portals().get(portalIndex).resummon();
            List<ContextMenu.Option> portalOptions = List.of(new ContextMenu.Option(
                    Component.translatable(on ? "action.projecthivemind.portal_resummon.on" : "action.projecthivemind.portal_resummon.off"),
                    () -> PacketDistributor.sendToServer(new com.projecthivemind.network.TogglePortalResummonPayload(portalIndex))));
            int[] portalCursor = ContextMenu.cursor(minecraft);
            ContextMenu.open(minecraft, portalCursor[0], portalCursor[1], portalOptions, hit.getBlockPos(), -1);
            return;
        }
        // A construction block has its own menu: Work, Options and, once it is done, Finish.
        com.projecthivemind.network.SyncConstructionsPayload.Info construction = ClientConstructions.at(hit.getBlockPos());
        if (construction != null) {
            openConstructionMenu(minecraft, construction, selected);
            return;
        }
        // One collector selected: the only things to offer are its tasks, and only inside the hive border.
        List<Integer> collectors = unitsOfKind(minecraft, selected, UnitKind.COLLECTOR);
        if (!collectors.isEmpty()) {
            openCollectorMenu(minecraft, collectors.get(0), hit.getBlockPos());
            return;
        }
        if (hasPassiveSelected(minecraft, selected)) {
            return; // a feeder takes no tasks from the menu
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
            // Digging is a worker job, interacting a worker's or a scout's: only offer what the selection can do.
            boolean hasWorker = selectionHas(minecraft, selected, UnitKind.WORKER);
            if (hasWorker || selectionHas(minecraft, selected, UnitKind.SCOUT)) {
                options.add(option("action.projecthivemind.dig", selected, pos, BlockAction.DIG));
            }
            // Workers can be set to keep mining a spot: what forms there again and again (a cobblestone generator).
            if (hasWorker) {
                options.add(option("action.projecthivemind.repeat_dig", selected, pos, BlockAction.REPEAT_DIG));
            }
            // A scout opens containers (chests, furnaces, hoppers...) for the player.
            boolean scoutCanOpen = selectionHas(minecraft, selected, UnitKind.SCOUT)
                    && minecraft.level.getBlockEntity(pos) instanceof Container;
            // A scout holding something can use it here: place it as a block, or use it.
            ItemStack scoutItem = scoutHandItem(minecraft, selected);
            if (ScoutItems.usable(scoutItem)) {
                Direction face = hit.getDirection();
                options.add(new ContextMenu.Option(Component.translatable(scoutItem.getItem() instanceof BlockItem
                        ? "action.projecthivemind.place_block" : "action.projecthivemind.use_item"),
                        () -> PacketDistributor.sendToServer(new ScoutUsePayload(selected, pos, face))));
            }
            // A scout can place a hive portal on the face that was clicked, once the hive has portals to place.
            if (ClientPortals.max() > 0 && selectionHas(minecraft, selected, UnitKind.SCOUT)) {
                Direction portalFace = hit.getDirection();
                options.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.place_portal"),
                        () -> PacketDistributor.sendToServer(new com.projecthivemind.network.PlacePortalPayload(selected, pos, portalFace, false))));
            }
            // Workers can construct on the block: a staircase down, a tower or shaft, a bridge.
            // A scout that is selected also builds: it is the team's conduit, so the build goes to the workers following it.
            List<Integer> builders = withTeamWorkers(minecraft, selected);
            if (!builders.isEmpty()) {
                // One entry for every construction: it leads to the list of what can be built (staircase, tower, bridge...).
                options.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.construct"),
                        () -> minecraft.setScreen(new ConstructScreen(builders, pos))));
            }
            // Workers put up a torch from the hive against the face that was clicked, unless it is the underside.
            if (!builders.isEmpty() && hit.getDirection() != Direction.DOWN) {
                Direction torchFace = hit.getDirection();
                options.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.place_torch"),
                        () -> PacketDistributor.sendToServer(new com.projecthivemind.network.PlaceTorchPayload(builders, pos, torchFace))));
            }
            if (hasWorker || scoutCanOpen) {
                options.add(option("action.projecthivemind.interact", selected, pos, BlockAction.INTERACT));
            }
        }
        if (working) {
            options.add(option("action.projecthivemind.cancel", List.of(), pos, BlockAction.CANCEL));
        }
        int[] cursor = ContextMenu.cursor(minecraft);
        ContextMenu.open(minecraft, cursor[0], cursor[1], options, pos, -1);
    }

    /** The index in the hive's portal list of the portal at this block, or -1 if it is not one. */
    private static int portalIndexAt(Minecraft minecraft, BlockPos pos) {
        String dimension = minecraft.level.dimension().location().toString();
        List<com.projecthivemind.network.SyncPortalsPayload.Portal> portals = ClientPortals.portals();
        for (int i = 0; i < portals.size(); i++) {
            if (portals.get(i).pos().equals(pos) && portals.get(i).dimension().equals(dimension)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The menu of a construction block. "Work" puts the selected workers on it (a selected team scout puts its team's workers on it), unless it is
     * done. "Options" opens the settings it was started with, to change. With workers selected, "Finish" (at any time, done or not) asks to confirm and
     * then takes the block away and the workers off it.
     */
    private static void openConstructionMenu(Minecraft minecraft, com.projecthivemind.network.SyncConstructionsPayload.Info construction, List<Integer> selected) {
        BlockPos pos = construction.pos();
        List<Integer> workers = withTeamWorkers(minecraft, selected);
        List<ContextMenu.Option> options = new ArrayList<>();
        boolean done = construction.state() == 2;
        if (!workers.isEmpty() && !done) {
            options.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.construction_work", construction.workers(), construction.max()),
                    () -> PacketDistributor.sendToServer(new com.projecthivemind.network.AssignConstructionPayload(workers, pos))));
        }
        options.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.construction_options"), () -> {
            net.minecraft.nbt.CompoundTag config = construction.config();
            switch (construction.kind()) {
                case 0 -> {
                    if (config.getInt("TunnelSize") > 0) {
                        minecraft.setScreen(new BuildTunnelScreen(pos, config));
                    } else if (config.getBoolean("Generator")) {
                        minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.generator_no_options"), false);
                    } else {
                        minecraft.setScreen(new BuildBridgeScreen(pos, config));
                    }
                }
                case 1 -> minecraft.setScreen(new DigStaircaseScreen(pos, config));
                default -> minecraft.setScreen(new BuildTowerScreen(pos, config));
            }
        }));
        // Finish can be chosen at any time: before it is done the work simply stops where it is, and what is built stays.
        if (!workers.isEmpty()) {
            options.add(new ContextMenu.Option(Component.translatable("action.projecthivemind.construction_finish"),
                    () -> minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
                        if (confirmed) {
                            PacketDistributor.sendToServer(new com.projecthivemind.network.FinishConstructionPayload(pos));
                        }
                        minecraft.setScreen(null);
                    }, Component.translatable("screen.projecthivemind.construction.finish_title"),
                            Component.translatable(done ? "screen.projecthivemind.construction.finish_message" : "screen.projecthivemind.construction.finish_early_message")))));
        }
        int[] cursor = ContextMenu.cursor(minecraft);
        ContextMenu.open(minecraft, cursor[0], cursor[1], options, pos, -1);
    }

    /** True if this block is inside the hive border (as the Heart last told us): collectors only work in there. */
    private static boolean insideHiveBorder(BlockPos pos) {
        BlockPos center = ClientState.borderCenter();
        int radius = ClientState.borderRadius();
        return center != null && Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius;
    }

    /** The menu for a block with a collector selected: set the soil it plants on (and clear it, if one is set). */
    private static void openCollectorMenu(Minecraft minecraft, int collectorId, BlockPos pos) {
        if (!insideHiveBorder(pos)) {
            minecraft.gui.setOverlayMessage(Component.translatable("message.projecthivemind.plant_outside"), false);
            return;
        }
        List<ContextMenu.Option> options = new ArrayList<>();
        SyncUnitsPayload.Entry entry = ClientUnits.entry(collectorId);
        // For each kind of planting: a block that is one of its spots already can be taken off, any other can be added.
        for (HiveCollector.PlantKind kind : HiveCollector.PlantKind.values()) {
            boolean sapling = kind == HiveCollector.PlantKind.SAPLING;
            java.util.List<BlockPos> spots = entry == null ? java.util.List.of() : sapling ? entry.task().saplingSpots() : entry.task().spots();
            int offset = sapling ? 10 : 0;
            if (spots.contains(pos)) {
                options.add(new ContextMenu.Option(Component.translatable(sapling ? "action.projecthivemind.remove_sapling_spot" : "action.projecthivemind.remove_plant_spot"),
                        () -> PacketDistributor.sendToServer(new SetCollectorTaskPayload(collectorId, 3 + offset, "", pos))));
            } else {
                options.add(new ContextMenu.Option(Component.translatable(sapling ? "action.projecthivemind.add_sapling_spot" : "action.projecthivemind.add_plant_spot"),
                        () -> PacketDistributor.sendToServer(new SetCollectorTaskPayload(collectorId, 1 + offset, "", pos))));
            }
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
            // A mob the hive cannot see is not drawn, so it cannot be clicked either.
            if (SightEvents.isHidden(mob)) {
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

    /** The menu for a mob: Attack (soldiers and scouts), and Cancel on a mob that units are already attacking. */
    private static void openMobMenu(Minecraft minecraft, Mob mob, List<Integer> selected) {
        boolean attacked = ClientActions.isAttacked(mob.getId());
        boolean hasSoldier = selectionHas(minecraft, selected, UnitKind.SOLDIER) || selectionHas(minecraft, selected, UnitKind.SCOUT);
        boolean scoutCanTrade = mob instanceof AbstractVillager && selectionHas(minecraft, selected, UnitKind.SCOUT);

        List<ContextMenu.Option> options = new ArrayList<>();
        if (hasSoldier) {
            options.add(mobOption("action.projecthivemind.attack", selected, mob, MobAction.ATTACK));
        }
        if (scoutCanTrade) {
            options.add(mobOption("action.projecthivemind.trade", selected, mob, MobAction.TRADE));
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

    /** What the selected scout is holding: the first of the selected units that is a scout and has something in its hand. */
    private static ItemStack scoutHandItem(Minecraft minecraft, List<Integer> selected) {
        for (int id : selected) {
            if (minecraft.level.getEntity(id) instanceof HiveScout scout && !scout.getMainHandItem().isEmpty()) {
                return scout.getMainHandItem();
            }
        }
        return ItemStack.EMPTY;
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

    /**
     * The player's own unit under the cursor, or null: the nearest one the cursor ray passes through, of any kind. Used
     * for the name that shows when the cursor rests on a unit. Units show through walls, so no block check.
     */
    @Nullable
    public static HiveUnit unitUnderCursor(Minecraft minecraft) {
        Optional<Ray> ray = cursorRay(minecraft);
        if (ray.isEmpty()) {
            return null;
        }
        HiveUnit closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof HiveUnit unit) || !minecraft.player.getUUID().equals(unit.ownerId())) {
                continue;
            }
            Optional<Vec3> hit = entity.getBoundingBox().inflate(PICK_MARGIN).clip(ray.get().from(), ray.get().to());
            if (hit.isPresent()) {
                double distance = ray.get().from().distanceToSqr(hit.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = unit;
                }
            }
        }
        return closest;
    }

    /** The index in the hive's portal list of the portal under the cursor (none of the player's units in front of it), or -1. */
    public static int portalUnderCursor(Minecraft minecraft) {
        Optional<Ray> ray = cursorRay(minecraft);
        if (ray.isEmpty() || ClientPortals.portals().isEmpty()) {
            return -1;
        }
        BlockHitResult hit = minecraft.level.clip(new ClipContext(ray.get().from(), ray.get().to(),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.MISS ? -1 : portalIndexAt(minecraft, hit.getBlockPos());
    }

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
