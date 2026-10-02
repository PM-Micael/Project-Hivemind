package com.projecthivemind.menu;

import javax.annotation.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

/**
 * The hive's storage as a menu shows it: a window of at most {@value #MAX_VISIBLE} slots (six rows) onto the real
 * storage, which can be bigger. The mouse wheel moves the window down the storage a row at a time.
 *
 * <p>Only the server has the real storage and the window over it. The client just has a plain container of the
 * visible size, which the server fills slot by slot; scrolling changes what the server puts in each slot, and the
 * normal slot syncing carries it across. The scrolled position travels as a synced value, only to draw the scrollbar.
 */
public final class StorageScroll {
    public static final int COLUMNS = 9;
    public static final int MAX_VISIBLE = 54;

    private final DataSlot position = DataSlot.standalone();
    private final int total;
    private final Container view;
    @Nullable
    private final Window window;

    /** @param backing the real storage on the server, or null on the client */
    public StorageScroll(@Nullable SimpleContainer backing, int total) {
        this.total = total;
        if (backing != null) {
            this.window = new Window(backing, visibleSlots(total));
            this.view = window;
        } else {
            this.window = null;
            this.view = new SimpleContainer(visibleSlots(total));
        }
    }

    public static int visibleSlots(int total) {
        return Math.min(total, MAX_VISIBLE);
    }

    public static int rows(int slots) {
        return (slots + COLUMNS - 1) / COLUMNS;
    }

    /** What the slots of the menu are built on. */
    public Container view() {
        return view;
    }

    public DataSlot position() {
        return position;
    }

    public int total() {
        return total;
    }

    public int visibleRows() {
        return rows(visibleSlots(total));
    }

    public int totalRows() {
        return rows(total);
    }

    /** How many rows down the window can go. Zero when everything fits. */
    public int maxRow() {
        return Math.max(0, totalRows() - visibleRows());
    }

    /** The row the window starts at. */
    public int row() {
        return position.get();
    }

    /** Server side: move the window to this row (kept in range). */
    public void scrollTo(int row) {
        int clamped = Mth.clamp(row, 0, maxRow());
        if (window != null) {
            window.offset = clamped * COLUMNS;
        }
        position.set(clamped);
    }

    /** A view of part of the real storage, starting at a movable offset. */
    private static final class Window implements Container {
        private final SimpleContainer backing;
        private final int size;
        private int offset;

        Window(SimpleContainer backing, int size) {
            this.backing = backing;
            this.size = size;
        }

        private boolean inRange(int slot) {
            int index = offset + slot;
            return slot >= 0 && slot < size && index < backing.getContainerSize();
        }

        @Override
        public int getContainerSize() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < size; i++) {
                if (!getItem(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return inRange(slot) ? backing.getItem(offset + slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return inRange(slot) ? backing.removeItem(offset + slot, amount) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return inRange(slot) ? backing.removeItemNoUpdate(offset + slot) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (inRange(slot)) {
                backing.setItem(offset + slot, stack);
            }
        }

        @Override
        public void setChanged() {
            backing.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        /** Never wipe the real storage through a window onto part of it. */
        @Override
        public void clearContent() {
        }
    }
}
