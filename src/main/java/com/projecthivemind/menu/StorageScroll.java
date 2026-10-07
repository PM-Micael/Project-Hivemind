package com.projecthivemind.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <p>The window can also be filtered by a search: then it shows only the stacks whose name has the search text in it, one after the
 * other, and the scrolling is over just those. Clearing the search shows the whole storage again.
 *
 * <p>Only the server has the real storage and the window over it. The client just has a plain container of the
 * visible size, which the server fills slot by slot; scrolling changes what the server puts in each slot, and the
 * normal slot syncing carries it across. The scrolled position, and how many slots there are to scroll through, travel as synced values,
 * only to draw the scrollbar.
 */
public final class StorageScroll {
    public static final int COLUMNS = 9;
    public static final int MAX_VISIBLE = 54;
    /** The most slots the window shows in the compact inventory: three rows. */
    public static final int COMPACT_VISIBLE = 27;

    private final DataSlot position = DataSlot.standalone();
    /** How many slots there are to scroll through: all of them, or only those the search matches. */
    private final DataSlot matches = DataSlot.standalone();
    private final int total;
    /** The most slots the window shows at once (a window of three rows for the scout page, six for the hive's own storage). */
    private final int maxVisible;
    /** Whether the compact inventory is open: the window is three rows, whatever its usual size. */
    private boolean compact;
    private final Container view;
    @Nullable
    private final Window window;

    /** @param backing the real storage on the server, or null on the client */
    public StorageScroll(@Nullable SimpleContainer backing, int total) {
        this(backing, total, MAX_VISIBLE);
    }

    public StorageScroll(@Nullable SimpleContainer backing, int total, int maxVisible) {
        this.maxVisible = maxVisible;
        this.total = total;
        this.matches.set(total);
        if (backing != null) {
            this.window = new Window(backing, Math.min(total, maxVisible));
            this.view = window;
        } else {
            this.window = null;
            // The client's copy only shows what the server says: it must not cut a stack down to the item's usual size, or a stack the evolution
            // tasks made bigger would show as 64 until it was picked up. (The server's storage decides how much a slot really holds.)
            this.view = new SimpleContainer(Math.min(total, maxVisible)) {
                @Override
                public int getMaxStackSize(net.minecraft.world.item.ItemStack stack) {
                    return 1024;
                }
            };
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

    public DataSlot matchCount() {
        return matches;
    }

    /** How many slots the real storage has. */
    public int total() {
        return total;
    }

    /** How many slots of the window are in use: its usual size, or three rows in the compact inventory. */
    public int shownSlots() {
        return Math.min(total, compact ? Math.min(maxVisible, COMPACT_VISIBLE) : maxVisible);
    }

    /** Switch the window between its usual size and the compact three rows (the server resizes the real window; the client only needs the size). */
    public void setCompact(boolean on) {
        if (compact == on) {
            return;
        }
        compact = on;
        if (window != null) {
            window.size = shownSlots();
            scrollTo(position.get());
        }
    }

    public int visibleRows() {
        return rows(shownSlots());
    }

    /** How many rows there are to scroll through: the whole storage, or what the search matches. */
    public int totalRows() {
        return rows(matches.get());
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

    /** Server side: show only the stacks whose name has this text in it (empty for all of them), from the top. */
    public void setSearch(String text) {
        if (window != null) {
            window.search = text.trim().toLowerCase(Locale.ROOT);
            refresh();
            scrollTo(0);
        }
    }

    /**
     * Server side: work out again which slots of the storage the search shows. Called as the menu sends its changes, since what the
     * storage holds changes all the time.
     */
    public void refresh() {
        if (window == null) {
            return;
        }
        window.refresh();
        matches.set(window.matchCount());
        scrollTo(position.get());
    }

    /** True if this slot of the window is a real slot of the storage (with a search, the slots after the last match are not). */
    public boolean isBacked(int slot) {
        return window == null || window.map(slot) >= 0;
    }

    /** A view of part of the real storage, starting at a movable offset, and only of the slots a search matches. */
    private static final class Window implements Container {
        private final SimpleContainer backing;
        private int size;
        private int offset;
        private String search = "";
        /** The slots of the storage the search matches, in order; null while there is no search (every slot shows). */
        @Nullable
        private int[] shown;

        Window(SimpleContainer backing, int size) {
            this.backing = backing;
            this.size = size;
        }

        void refresh() {
            if (search.isEmpty()) {
                shown = null;
                return;
            }
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < backing.getContainerSize(); i++) {
                ItemStack stack = backing.getItem(i);
                if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(search)) {
                    found.add(i);
                }
            }
            shown = found.stream().mapToInt(Integer::intValue).toArray();
        }

        int matchCount() {
            return shown == null ? backing.getContainerSize() : shown.length;
        }

        /** The slot of the storage this slot of the window stands for, or -1 for none. */
        int map(int slot) {
            if (slot < 0 || slot >= size) {
                return -1;
            }
            int position = offset + slot;
            if (shown == null) {
                return position < backing.getContainerSize() ? position : -1;
            }
            return position < shown.length ? shown[position] : -1;
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
            int index = map(slot);
            return index >= 0 ? backing.getItem(index) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            int index = map(slot);
            return index >= 0 ? backing.removeItem(index, amount) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            int index = map(slot);
            return index >= 0 ? backing.removeItemNoUpdate(index) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            int index = map(slot);
            if (index >= 0) {
                backing.setItem(index, stack);
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
