package com.projecthivemind.network;

import com.projecthivemind.HiveActions;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ScoutItems;
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

    /** The hive menu changed tab: remember which slots are on show. Only the known groups are kept. */
    public static void onSetMenuView(SetMenuViewPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof HiveMenu menu
                && menu.containerId == payload.containerId()) {
            menu.visibleGroups = payload.groups() & (HiveMenu.GROUP_STORAGE | HiveMenu.GROUP_CRAFT | HiveMenu.GROUP_FURNACE | HiveMenu.GROUP_GEAR);
        }
    }

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

    public static void onScoutUse(ScoutUsePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.scoutUse(player, payload);
        }
    }
    public static void onPlaceTorch(com.projecthivemind.network.PlaceTorchPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.placeTorch(player, payload);
        }
    }


    public static void onSignText(SignTextPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ScoutItems.writeSign(player, payload.pos(), payload.lines());
        }
    }

    public static void onFocusUnit(FocusUnitPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.focusUnit(player, payload.unitId());
        }
    }

    public static void onDropItem(DropItemPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.scoutDrop(player, payload);
        }
    }

    public static void onSetWorkerFill(SetWorkerFillPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setWorkerFill(player, payload.unitId(), payload.item());
        }
    }

    public static void onSetCollectorTask(SetCollectorTaskPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setCollectorTask(player, payload.unitId(), payload.op(), payload.seed(), payload.pos());
        }
    }

    public static void onSetUnitBehavior(SetUnitBehaviorPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setUnitBehavior(player, payload.unitId(), payload.flags(), payload.radii());
        }
    }

    public static void onCancelJob(CancelJobPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.cancelUnitJob(player, payload.unitId());
        }
    }

    public static void onSetJobResume(SetJobResumePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setJobResume(player, payload.unitId(), payload.resume());
        }
    }

    public static void onViewUnit(ViewUnitPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof HiveMenu menu
                && menu.containerId == payload.containerId()) {
            menu.viewUnit(payload.unitId(), payload.seq());
        }
    }

    public static void onBuildTower(BuildTowerPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.buildTower(player, payload);
        }
    }

    public static void onDigStaircase(DigStaircasePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.digStaircase(player, payload);
        }
    }

    public static void onSelection(SelectionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && payload.unitIds().size() <= BlockActionPayload.MAX_UNITS) {
            HivemindManager.setSelection(player, payload.unitIds());
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

    public static void onReturnToBase(ReturnToBasePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.returnToBase(player, payload.unitId());
        }
    }

    public static void onToggleTeam(ToggleTeamPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.toggleTeam(player, payload.unitId());
        }
    }

    public static void onSetTeamRadius(com.projecthivemind.network.SetTeamRadiusPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setTeamRadius(player, payload.team(), payload.radius());
        }
    }

    public static void onBuildWall(com.projecthivemind.network.BuildWallPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.setWorkerWall(player, payload.unitId(), payload.item());
        }
    }

    public static void onBuildBridge(com.projecthivemind.network.BuildBridgePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HiveActions.buildBridge(player, payload);
        }
    }

    public static void onOpenHiveMenu(OpenHiveMenuPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            HivemindManager.openMenu(player);
        }
    }
}
