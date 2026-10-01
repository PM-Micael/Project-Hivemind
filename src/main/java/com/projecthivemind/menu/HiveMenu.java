package com.projecthivemind.menu;

import java.util.Optional;

import javax.annotation.Nullable;

import com.projecthivemind.HiveLevels;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModMenus;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveHeart;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * The bodyless hivemind's inventory screen: the hive's shared storage plus a crafting grid. The hivemind has no
 * body, so nothing here touches the player's own inventory; everything goes to and from the hive.
 */
public class HiveMenu extends AbstractContainerMenu {
    public static final int STORAGE_SLOTS = 27;
    public static final int GRID_SIZE = 3;

    // Slot indices.
    private static final int STORAGE_START = 0;
    private static final int RESULT_INDEX = STORAGE_START + STORAGE_SLOTS;
    private static final int GRID_START = RESULT_INDEX + 1;
    private static final int GRID_END = GRID_START + GRID_SIZE * GRID_SIZE;

    // Slot positions inside the panel, shared with the screen.
    public static final int STORAGE_X = 8;
    public static final int STORAGE_Y = 54;
    public static final int GRID_X = 190;
    public static final int GRID_Y = 54;
    public static final int RESULT_X = 208;
    public static final int RESULT_Y = 118;

    // Synced values: level, health, max health, then (count, cap) for each unit kind.
    private static final int DATA_LEVEL = 0;
    private static final int DATA_HEALTH = 1;
    private static final int DATA_MAX_HEALTH = 2;
    private static final int DATA_UNITS = 3;
    public static final int DATA_COUNT = DATA_UNITS + UnitKind.values().length * 2;

    private final SimpleContainer storage;
    private final ContainerData data;
    private final Player player;
    @Nullable
    private final HiveHeart heart;
    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, GRID_SIZE, GRID_SIZE);
    private final ResultContainer resultSlots = new ResultContainer();

    /** Client-side only: the screen shows the Quests tab, so the slots are hidden. */
    public boolean questsOpen;

    /** Client constructor: the real contents arrive from the server. */
    public HiveMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, new SimpleContainer(STORAGE_SLOTS), new SimpleContainerData(DATA_COUNT), null);
    }

    private HiveMenu(int containerId, Inventory inventory, SimpleContainer storage, ContainerData data, @Nullable HiveHeart heart) {
        super(ModMenus.HIVE.get(), containerId);
        checkContainerSize(storage, STORAGE_SLOTS);
        this.storage = storage;
        this.data = data;
        this.player = inventory.player;
        this.heart = heart;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new HiveSlot(storage, col + row * 9, STORAGE_X + col * 18, STORAGE_Y + row * 18));
            }
        }
        this.addSlot(new HiveResultSlot(player, craftSlots, resultSlots, 0, RESULT_X, RESULT_Y));
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                this.addSlot(new HiveSlot(craftSlots, col + row * GRID_SIZE, GRID_X + col * 18, GRID_Y + row * 18));
            }
        }
        this.addDataSlots(data);
    }

    /** Server constructor: backed by the Heart's real storage, with live stats for the screen. */
    public static HiveMenu create(int containerId, Inventory inventory, HiveHeart heart, ServerPlayer player) {
        ContainerData data = new ContainerData() {
            @Override
            public int get(int index) {
                if (index == DATA_LEVEL) {
                    return heart.hiveLevel();
                }
                if (index == DATA_HEALTH) {
                    return (int) Math.ceil(heart.getHealth());
                }
                if (index == DATA_MAX_HEALTH) {
                    return (int) heart.getMaxHealth();
                }
                UnitKind kind = UnitKind.values()[(index - DATA_UNITS) / 2];
                boolean isCount = (index - DATA_UNITS) % 2 == 0;
                return isCount ? HivemindManager.get(player).count(kind) : HiveLevels.get(heart.hiveLevel()).cap(kind);
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        return new HiveMenu(containerId, inventory, heart.getStorage(), data, heart);
    }

    // ---- values for the screen ----

    public int level() {
        return data.get(DATA_LEVEL);
    }

    public int health() {
        return data.get(DATA_HEALTH);
    }

    public int maxHealth() {
        return data.get(DATA_MAX_HEALTH);
    }

    public int unitCount(UnitKind kind) {
        return data.get(DATA_UNITS + kind.ordinal() * 2);
    }

    public int unitCap(UnitKind kind) {
        return data.get(DATA_UNITS + kind.ordinal() * 2 + 1);
    }

    // ---- crafting ----

    @Override
    public void slotsChanged(Container container) {
        updateResult(player.level());
    }

    /** Same as the vanilla crafting table: find a matching recipe and show its result. */
    private void updateResult(Level level) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        CraftingInput input = craftSlots.asCraftInput();
        ItemStack result = ItemStack.EMPTY;
        Optional<RecipeHolder<CraftingRecipe>> match = level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        if (match.isPresent()) {
            RecipeHolder<CraftingRecipe> holder = match.get();
            if (resultSlots.setRecipeUsed(level, serverPlayer, holder)) {
                ItemStack assembled = holder.value().assemble(input, level.registryAccess());
                if (assembled.isItemEnabled(level.enabledFeatures())) {
                    result = assembled;
                }
            }
        }
        resultSlots.setItem(0, result);
        setRemoteSlot(RESULT_INDEX, result);
        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(containerId, incrementStateId(), RESULT_INDEX, result));
    }

    // ---- moving items ----

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == RESULT_INDEX) {
            stack.getItem().onCraftedBy(stack, player.level(), player);
            if (!this.moveItemStackTo(stack, STORAGE_START, RESULT_INDEX, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else if (index >= GRID_START) {
            if (!this.moveItemStackTo(stack, STORAGE_START, RESULT_INDEX, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, GRID_START, GRID_END, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return heart == null || heart.isAlive();
    }

    /** Closing the menu: everything on the cursor or in the crafting grid goes back to the hive, never to the player. */
    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide) {
            ItemStack carried = getCarried();
            if (!carried.isEmpty()) {
                setCarried(ItemStack.EMPTY);
                giveToHive(carried);
            }
            for (int i = 0; i < craftSlots.getContainerSize(); i++) {
                giveToHive(craftSlots.removeItemNoUpdate(i));
            }
            resultSlots.clearContent();
        }
        super.removed(player);
    }

    private void giveToHive(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack leftover = storage.addItem(stack);
        if (!leftover.isEmpty() && heart != null) {
            heart.spawnAtLocation(leftover);
        }
    }

    // ---- slots that disappear on the Quests tab ----

    private class HiveSlot extends Slot {
        HiveSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return !questsOpen;
        }
    }

    private class HiveResultSlot extends ResultSlot {
        HiveResultSlot(Player player, CraftingContainer craftSlots, Container container, int index, int x, int y) {
            super(player, craftSlots, container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return !questsOpen;
        }
    }
}
