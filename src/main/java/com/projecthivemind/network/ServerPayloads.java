package com.projecthivemind.network;

import com.projecthivemind.CollectorBehavior;
import com.projecthivemind.HiveActions;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ScoutBehavior;
import com.projecthivemind.SoldierBehavior;
import com.projecthivemind.WorkerBehavior;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.menu.ScoutTradeMenu;
import com.projecthivemind.menu.ScrollableStorage;
import com.projecthivemind.menu.SpectatorClickable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
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

    /** Spectators cannot use vanilla container clicks, so the hive menu's clicks arrive here and are applied if safe. */
    public static void onHiveMenuClick(HiveMenuClickPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof SpectatorClickable)
                || player.containerMenu.containerId != payload.containerId()
                || !player.containerMenu.stillValid(player)) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
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

    /** The mouse wheel moved the hive storage window in the open menu. */
    public static void onScrollStorage(ScrollStoragePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof ScrollableStorage menu
                && player.containerMenu.containerId == payload.containerId()) {
            menu.storageScroll().scrollTo(payload.row());
            player.containerMenu.broadcastChanges();
        }
    }

    /** The player pressed a trade in the scout's trade screen. */
    public static void onTrade(TradePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof ScoutTradeMenu menu
                && menu.containerId == payload.containerId()
                && menu.stillValid(player)) {
            menu.trade(payload.offerIndex(), player);
            menu.broadcastChanges();
        }
    }

    public static void onSelection(SelectionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && payload.unitIds().size() <= BlockActionPayload.MAX_UNITS) {
            HivemindManager.setSelection(player, payload.unitIds());
        }
    }

    public static void onSetBehavior(SetBehaviorPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // The constructor clamps the radius, so a bad client cannot set a silly one.
            HivemindManager.setSoldierBehavior(player, SoldierBehavior.fromFlags(payload.flags(), payload.unitAreaRadius()));
        }
    }

    public static void onSetWorkerBehavior(SetWorkerBehaviorPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // The constructor clamps the radius, so a bad client cannot set a silly one.
            HivemindManager.setWorkerBehavior(player, WorkerBehavior.fromFlags(payload.flags(), payload.unitAreaRadius()));
        }
    }

    public static void onSetCollectorBehavior(SetCollectorBehaviorPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // The constructor clamps the range, so a bad client cannot set a silly one.
            HivemindManager.setCollectorBehavior(player, new CollectorBehavior(payload.extraRange()));
        }
    }

    public static void onSetScoutBehavior(SetScoutBehaviorPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setScoutBehavior(player, ScoutBehavior.fromFlags(payload.flags(), payload.unitAreaRadius()));
        }
    }

    public static void onMobAction(MobActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.handleMob(player, payload);
        }
    }

    public static void onBlockAction(BlockActionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.handle(player, payload);
        }
    }

    public static void onToggleInventoryMode(ToggleInventoryModePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.toggleInventoryMode(player);
        }
    }

    public static void onReturnToHeart(ReturnToHeartPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.returnToHeart(player);
        }
    }

    public static void onOpenHiveMenu(OpenHiveMenuPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.openMenu(player);
        }
    }
}
