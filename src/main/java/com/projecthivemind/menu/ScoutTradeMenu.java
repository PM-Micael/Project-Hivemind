package com.projecthivemind.menu;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.projecthivemind.ModMenus;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.network.TradeOffersPayload;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Trading with a villager through a scout. The villager's offers are listed by the screen; the hive's shared storage is
 * the purse: a trade takes what it costs out of the hive and puts what it gives into the hive. Nothing touches the
 * player's own inventory, which a bodyless hivemind does not have.
 *
 * <p>The villager is held in place (it is "trading") while this is open, and the scout has to stay close to it.
 */
public class ScoutTradeMenu extends AbstractContainerMenu implements SpectatorClickable {
    public static final int SLOT_X = 8;
    /** Where the hive storage grid starts; the offers sit above it. */
    public static final int STORAGE_Y = 160;
    public static final int PANEL_HEIGHT = STORAGE_Y + 3 * 18 + 8;
    private static final double MAX_SCOUT_DISTANCE = 10.0D;

    private final SimpleContainer storage;
    @Nullable
    private final AbstractVillager villager;
    @Nullable
    private final HiveScout scout;
    @Nullable
    private final HiveHeart heart;

    /** Client side: the offers as last sent by the server. */
    private MerchantOffers offers = new MerchantOffers();

    /** Client constructor: the real contents and offers arrive from the server. */
    public ScoutTradeMenu(int containerId, Inventory inventory) {
        this(containerId, new SimpleContainer(HiveMenu.STORAGE_SLOTS), null, null, null);
    }

    public ScoutTradeMenu(int containerId, SimpleContainer storage, @Nullable AbstractVillager villager, @Nullable HiveScout scout,
                          @Nullable HiveHeart heart) {
        super(ModMenus.SCOUT_TRADE.get(), containerId);
        this.storage = storage;
        this.villager = villager;
        this.scout = scout;
        this.heart = heart;
        for (int i = 0; i < HiveMenu.STORAGE_SLOTS; i++) {
            this.addSlot(new Slot(storage, i, SLOT_X + (i % 9) * 18, STORAGE_Y + (i / 9) * 18));
        }
    }

    public MerchantOffers offers() {
        return offers;
    }

    public void setOffers(MerchantOffers offers) {
        this.offers = offers;
    }

    /** Server side: send the villager's current offers (and so their uses and prices) to the player. */
    public void sendOffers(ServerPlayer player) {
        if (villager != null) {
            PacketDistributor.sendToPlayer(player, new TradeOffersPayload(this.containerId, villager.getOffers()));
        }
    }

    /** Hold the villager still for the trading, once the menu really is open for the player. */
    public void startTrading(ServerPlayer player) {
        if (villager != null) {
            villager.setTradingPlayer(player);
        }
    }

    // ---- paying ----

    /** How many items matching this are in the container. */
    public static int count(Container container, Predicate<ItemStack> matches) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Take this many matching items out of the container. The caller has already checked there are enough. */
    private static void remove(Container container, Predicate<ItemStack> matches, int amount) {
        for (int i = 0; i < container.getContainerSize() && amount > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                int taken = Math.min(amount, stack.getCount());
                stack.shrink(taken);
                if (stack.isEmpty()) {
                    container.setItem(i, ItemStack.EMPTY);
                }
                amount -= taken;
            }
        }
        container.setChanged();
    }

    /** Client side: can the hive afford this offer right now, going by the storage slots the server has synced. */
    public boolean affordable(MerchantOffer offer) {
        return canAfford(storage, offer);
    }

    /** True if the container holds what the offer costs. */
    public static boolean canAfford(Container container, MerchantOffer offer) {
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        if (count(container, offer.getItemCostA()::test) < costA.getCount()) {
            return false;
        }
        if (!costB.isEmpty()) {
            if (offer.getItemCostB().isEmpty()) {
                return false;
            }
            // Both costs can be the same item; then they draw from the same pool.
            boolean sameItem = ItemStack.isSameItemSameComponents(costA, costB);
            int needed = sameItem ? costA.getCount() + costB.getCount() : costB.getCount();
            if (count(container, offer.getItemCostB().get()::test) < needed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Buy one of the offer at this index, paying from the hive and delivering to the hive. Returns false (and changes
     * nothing) if the offer is gone, sold out, too expensive, or the result would not fit.
     */
    public boolean trade(int index, ServerPlayer player) {
        if (villager == null || !villager.isAlive()) {
            return false;
        }
        MerchantOffers all = villager.getOffers();
        if (index < 0 || index >= all.size()) {
            return false;
        }
        MerchantOffer offer = all.get(index);
        if (offer.isOutOfStock() || !canAfford(storage, offer)) {
            return false;
        }

        // Rehearse on a copy, so a result that does not fit leaves the hive exactly as it was.
        SimpleContainer rehearsal = new SimpleContainer(storage.getContainerSize());
        for (int i = 0; i < storage.getContainerSize(); i++) {
            rehearsal.setItem(i, storage.getItem(i).copy());
        }
        pay(rehearsal, offer);
        ItemStack result = offer.assemble();
        if (!rehearsal.canAddItem(result)) {
            return false;
        }

        pay(storage, offer);
        storage.setChanged();
        ItemStack left = storage.addItem(result);
        if (!left.isEmpty() && scout != null) {
            Containers.dropItemStack(scout.level(), scout.getX(), scout.getY(), scout.getZ(), left);
        }
        offer.increaseUses();
        villager.notifyTrade(offer);
        villager.playSound(villager.getNotifyTradeSound(), 1.0F, 1.0F);
        sendOffers(player);
        return true;
    }

    private static void pay(Container container, MerchantOffer offer) {
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        remove(container, offer.getItemCostA()::test, costA.getCount());
        if (!costB.isEmpty() && offer.getItemCostB().isPresent()) {
            remove(container, offer.getItemCostB().get()::test, costB.getCount());
        }
    }

    // ---- menu plumbing ----

    /** Shift-click does nothing here: there is only the one grid, and no second place for an item to go. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (villager == null || scout == null || heart == null) {
            return true;
        }
        return villager.isAlive() && scout.isAlive() && heart.isAlive()
                && scout.distanceToSqr(villager) <= MAX_SCOUT_DISTANCE * MAX_SCOUT_DISTANCE;
    }

    @Override
    public void removed(Player player) {
        if (villager != null && villager.getTradingPlayer() == player) {
            villager.setTradingPlayer(null);
        }
        ItemStack carried = this.getCarried();
        this.setCarried(ItemStack.EMPTY);
        this.resetQuickCraft();
        if (!carried.isEmpty() && !player.level().isClientSide) {
            ItemStack left = storage.addItem(carried);
            if (!left.isEmpty()) {
                if (scout != null) {
                    Containers.dropItemStack(scout.level(), scout.getX(), scout.getY(), scout.getZ(), left);
                } else {
                    player.drop(left, false);
                }
            }
        }
    }
}
