package com.projecthivemind.menu;

import javax.annotation.Nullable;

import com.projecthivemind.ModMenus;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A container a scout has opened for the hivemind: the container's own slots on top (a chest, a furnace, a hopper...),
 * the hive's shared storage underneath. Like the hive menu it never touches the player's own inventory, which a
 * bodyless hivemind does not have: everything goes to and from the hive.
 */
public class ScoutContainerMenu extends AbstractContainerMenu implements SpectatorClickable, ScrollableStorage {
    public static final int COLUMNS = 9;
    public static final int SLOT_X = 8;
    public static final int TOP_Y = 18;
    /** The most slots the layout has room for (a double chest). */
    public static final int MAX_SLOTS = 54;
    private static final double MAX_SCOUT_DISTANCE = 10.0D;

    private final Container target;
    private final StorageScroll scroll;
    @Nullable
    private final SimpleContainer storage;
    private final int targetSize;
    @Nullable
    private final HiveScout scout;
    @Nullable
    private final HiveHeart heart;
    @Nullable
    private final BlockEntity blockEntity;
    @Nullable
    private final BlockPos pos;

    /** Client constructor: the real contents arrive from the server. */
    public ScoutContainerMenu(int containerId, Inventory inventory, int targetSize, int storageSlots) {
        this(containerId, new SimpleContainer(targetSize), null, new StorageScroll(null, storageSlots), targetSize, null, null, null, null);
    }

    public ScoutContainerMenu(int containerId, Container target, SimpleContainer storage, @Nullable HiveScout scout,
                              @Nullable HiveHeart heart, @Nullable BlockEntity blockEntity, BlockPos pos) {
        this(containerId, target, storage, new StorageScroll(storage, storage.getContainerSize()), target.getContainerSize(), scout, heart, blockEntity, pos);
    }

    private ScoutContainerMenu(int containerId, Container target, @Nullable SimpleContainer storage, StorageScroll scroll, int targetSize, @Nullable HiveScout scout,
                               @Nullable HiveHeart heart, @Nullable BlockEntity blockEntity, @Nullable BlockPos pos) {
        super(ModMenus.SCOUT_CONTAINER.get(), containerId);
        this.target = target;
        this.storage = storage;
        this.scroll = scroll;
        this.targetSize = Math.min(targetSize, MAX_SLOTS);
        this.scout = scout;
        this.heart = heart;
        this.blockEntity = blockEntity;
        this.pos = pos;

        for (int i = 0; i < this.targetSize; i++) {
            this.addSlot(new TargetSlot(target, i, SLOT_X + (i % COLUMNS) * 18, TOP_Y + (i / COLUMNS) * 18));
        }
        int storageY = storageY(this.targetSize);
        for (int i = 0; i < scroll.visibleSlots(scroll.total()); i++) {
            this.addSlot(new Slot(scroll.view(), i, SLOT_X + (i % COLUMNS) * 18, storageY + (i / COLUMNS) * 18));
        }
        this.addDataSlot(scroll.position());
    }

    @Override
    public StorageScroll storageScroll() {
        return scroll;
    }

    public int storageSlots() {
        return scroll.total();
    }

    public int targetSize() {
        return targetSize;
    }

    public static int rows(int slots) {
        return (Math.min(slots, MAX_SLOTS) + COLUMNS - 1) / COLUMNS;
    }

    /** Where the hive storage grid starts, below the container's own grid and a label's worth of room. */
    public static int storageY(int slots) {
        return TOP_Y + rows(slots) * 18 + 14;
    }

    public static int panelHeight(int slots, int storageSlots) {
        return storageY(slots) + StorageScroll.rows(StorageScroll.visibleSlots(storageSlots)) * 18 + 8;
    }

    /** A slot of the opened container: only takes what the container itself would accept there (no filling a furnace's output). */
    private static class TargetSlot extends Slot {
        TargetSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return this.container.canPlaceItem(this.getContainerSlot(), stack);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved;
        if (index < targetSize) {
            moved = this.moveItemStackTo(stack, targetSize, this.slots.size(), true);
        } else {
            moved = this.moveItemStackTo(stack, 0, targetSize, false);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /**
     * Open only while the scout that opened it is alive and close to the container, and the container is still there.
     * The player is the camera and could be anywhere, so its distance does not matter.
     */
    @Override
    public boolean stillValid(Player player) {
        if (scout == null || pos == null || heart == null) {
            return true;
        }
        return scout.isAlive() && heart.isAlive() && (blockEntity == null || !blockEntity.isRemoved())
                && scout.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_SCOUT_DISTANCE * MAX_SCOUT_DISTANCE;
    }

    /**
     * The item on the cursor goes into the hive, not into the camera's own inventory (which the hivemind does not use).
     * If the hive is full it is dropped next to the scout.
     */
    @Override
    public void removed(Player player) {
        ItemStack carried = this.getCarried();
        this.setCarried(ItemStack.EMPTY);
        this.resetQuickCraft();
        if (!carried.isEmpty() && !player.level().isClientSide && storage != null) {
            ItemStack left = storage.addItem(carried);
            if (!left.isEmpty()) {
                if (scout != null) {
                    Containers.dropItemStack(scout.level(), scout.getX(), scout.getY(), scout.getZ(), left);
                } else {
                    player.drop(left, false);
                }
            }
        }
        target.setChanged();
    }
}
