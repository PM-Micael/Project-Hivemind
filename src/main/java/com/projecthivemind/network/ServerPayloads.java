package com.projecthivemind.network;

import com.projecthivemind.HivemindManager;
import com.projecthivemind.menu.HiveMenu;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Handlers that run on the server. Every request is re-validated by {@link HivemindManager}; never trust the client. */
public final class ServerPayloads {
    private ServerPayloads() {
    }

    public static void onChooseMode(ChooseModePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.choose(player, payload.hivemind());
        }
    }

    public static void onSpawnUnit(SpawnUnitPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.spawnUnit(player, payload.kind());
        }
    }

    /** Spectators cannot use vanilla container clicks, so the hive menu's clicks arrive here and are applied if safe. */
    public static void onHiveMenuClick(HiveMenuClickPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof HiveMenu menu)
                || menu.containerId != payload.containerId()
                || !menu.stillValid(player)) {
            return;
        }
        // Allowed: pick up / place, shift-click, drag-spread, double-click collect. Everything else is refused:
        // number-key swaps would trade with the hidden hotbar, and throws would drop items from the camera.
        ClickType type = payload.clickType();
        boolean allowed = type == ClickType.PICKUP || type == ClickType.QUICK_MOVE
                || type == ClickType.QUICK_CRAFT || type == ClickType.PICKUP_ALL;
        // Slot -999 is "outside the window": it ends a drag-spread, but must never drop the cursor item into the world.
        boolean outside = payload.slot() == OUTSIDE_SLOT;
        if (!allowed || (outside && type != ClickType.QUICK_CRAFT) || (!outside && !menu.isValidSlotIndex(payload.slot()))) {
            menu.sendAllDataToRemote();
            return;
        }
        menu.clicked(payload.slot(), payload.button(), type, player);
        menu.broadcastChanges();
    }

    private static final int OUTSIDE_SLOT = -999;

    public static void onMoveUnits(MoveUnitsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.commandMove(player, payload.unitIds(), new Vec3(payload.x(), payload.y(), payload.z()));
        }
    }

    public static void onToggleInventoryMode(ToggleInventoryModePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.toggleInventoryMode(player);
        }
    }

    public static void onOpenHiveMenu(OpenHiveMenuPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.openMenu(player);
        }
    }
}
