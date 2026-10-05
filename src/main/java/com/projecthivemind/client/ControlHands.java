package com.projecthivemind.client;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.entity.HiveScout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The hands a player sees while controlling a scout. The game draws first-person hands from the player's own entity, so while a scout is
 * controlled the player's main hand and offhand (on this client only; nothing is sent to the server) are made to hold what the scout holds,
 * and its swing is the scout's swing. What was there is put back afterwards.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class ControlHands {
    private static boolean saved;
    private static int slot;
    private static ItemStack savedMain = ItemStack.EMPTY;
    private static ItemStack savedOff = ItemStack.EMPTY;

    private ControlHands() {
    }

    @SubscribeEvent
    static void onClientTickPost(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            saved = false;
            return;
        }
        HiveScout scout = ClientControl.scout();
        if (scout == null) {
            restore(player);
            return;
        }
        Inventory inventory = player.getInventory();
        if (saved && slot != inventory.selected) {
            restore(player);
        }
        if (!saved) {
            slot = inventory.selected;
            savedMain = inventory.items.get(slot);
            savedOff = inventory.offhand.get(0);
            saved = true;
            player.xBob = player.getXRot();
            player.yBob = player.getYRot();
            player.xBobO = player.xBob;
            player.yBobO = player.yBob;
        }
        inventory.items.set(slot, scout.getMainHandItem());
        inventory.offhand.set(0, scout.getOffhandItem());
        // The hands sway a little behind where the view turns: the game moves that lag along only for the camera entity, which is the scout here.
        player.yBobO = player.yBob;
        player.xBobO = player.xBob;
        player.xBob += (player.getXRot() - player.xBob) * 0.5F;
        player.yBob += (player.getYRot() - player.yBob) * 0.5F;
        // The swing, as far through as the scout's is (the player's own swing ticks along and is overwritten each tick).
        player.swingingArm = scout.swingingArm;
        player.oAttackAnim = scout.oAttackAnim;
        player.attackAnim = scout.attackAnim;
        // Drawing a bow, eating, blocking: the player's hand does what the scout's does.
        if (scout.isUsingItem()) {
            // (The player's own "using" state is what the first-person animation reads; the game also ends it itself whenever the use key is
            // up, so it is started again here whenever it has stopped while the scout is still using.)
            if (!player.isUsingItem() || player.getUsedItemHand() != scout.getUsedItemHand()) {
                player.stopUsingItem();
                player.startUsingItem(scout.getUsedItemHand());
            }
            player.useItem = scout.getUseItem();
            player.useItemRemaining = scout.getUseItemRemainingTicks();
        } else {
            stopUsing(player);
        }
    }

    private static void restore(LocalPlayer player) {
        if (!saved) {
            return;
        }
        saved = false;
        Inventory inventory = player.getInventory();
        inventory.items.set(slot, savedMain);
        inventory.offhand.set(0, savedOff);
        savedMain = ItemStack.EMPTY;
        savedOff = ItemStack.EMPTY;
        player.swingingArm = null;
        stopUsing(player);
        player.attackAnim = 0.0F;
        player.oAttackAnim = 0.0F;
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        saved = false;
    }

    private static void stopUsing(LocalPlayer player) {
        if (player.isUsingItem()) {
            player.stopUsingItem();
        }
    }
}
