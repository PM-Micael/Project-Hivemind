package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * The fluids the hive keeps, once it has consumed a cauldron. Each fluid has a meter and two container slots: the top one takes a full
 * container and empties it into the meter (the empty container stays there to be taken), the bottom one takes an empty container and fills it from
 * the meter (the full container stays). The five vanilla ones are always there; others (a modded fluid) get a meter of their own when a container of
 * them is first emptied into the extra column at the end. Amounts are in units, a third of a millibucket, so that a bottle (a third of a bucket) is
 * a whole number: a bucket is {@link #BUCKET}. Experience is the exception: its unit is a point.
 */
public final class HiveFluids {
    public static final int BUCKET = 3000;
    /** Every fluid holds this much: 1000 buckets. */
    public static final int CAPACITY = 1000 * BUCKET;
    /** Experience holds this many points, and a bottle o' enchanting is worth this many. */
    public static final int XP_CAPACITY = 1000000;
    public static final int XP_PER_BOTTLE = 7;

    public static final ResourceLocation WATER = ResourceLocation.withDefaultNamespace("water");
    public static final ResourceLocation LAVA = ResourceLocation.withDefaultNamespace("lava");
    public static final ResourceLocation MILK = ProjectHivemind.id("milk");
    public static final ResourceLocation HONEY = ProjectHivemind.id("honey");
    public static final ResourceLocation XP = ProjectHivemind.id("xp");
    private static final List<ResourceLocation> VANILLA = List.of(WATER, LAVA, MILK, HONEY, XP);

    private static final String TAG = "HiveFluids";

    /** The two slots of one column, and which fluid it is for (null for the extra column at the end). Changing them does the transfer. */
    public final class Cells extends SimpleContainer {
        @Nullable
        private final ResourceLocation key;

        Cells(@Nullable ResourceLocation key) {
            super(2);
            this.key = key;
        }

        @Nullable
        public ResourceLocation key() {
            return key;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return OUTPUT_LIMIT;
        }

        @Override
        public int getMaxStackSize() {
            return OUTPUT_LIMIT;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            process(this);
        }

        private void put(int slot, ItemStack stack) {
            getItems().set(slot, stack);
        }
    }

    private final Map<ResourceLocation, Integer> amounts = new LinkedHashMap<>();
    private final Map<ResourceLocation, Cells> cells = new HashMap<>();
    private final Cells fresh = new Cells(null);
    /** Where emptied and filled containers come out, up to this many (more than a stack: they are not mixed with anything else). */
    public static final int OUTPUT_LIMIT = 64;
    /** How many containers one input slot takes. */
    public static final int INPUT_LIMIT = 16;
    private final SimpleContainer outputSlot = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize(ItemStack stack) {
            return OUTPUT_LIMIT;
        }

        @Override
        public int getMaxStackSize() {
            return OUTPUT_LIMIT;
        }
    };
    private boolean busy;

    public HiveFluids() {
        for (ResourceLocation id : VANILLA) {
            amounts.put(id, 0);
            cells.put(id, new Cells(id));
        }
    }

    public static int capacity(ResourceLocation fluid) {
        return XP.equals(fluid) ? XP_CAPACITY : CAPACITY;
    }

    /** The fluids that have a meter, in order. */
    public List<ResourceLocation> fluids() {
        return new ArrayList<>(amounts.keySet());
    }

    /** Empty every meter. Returns how many fluids had something in them. */
    public int drainAll() {
        int emptied = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : amounts.entrySet()) {
            if (entry.getValue() > 0) {
                emptied++;
                entry.setValue(0);
            }
        }
        return emptied;
    }

    /** Put this much of a fluid in the hive (it gets a meter if it has none); returns how much went in, which is less than asked when the meter is nearly full. */
    public int add(ResourceLocation fluid, int units) {
        int room = capacity(fluid) - amount(fluid);
        int taken = Math.max(0, Math.min(units, room));
        if (taken <= 0) {
            return 0;
        }
        amounts.merge(fluid, taken, Integer::sum);
        cells.computeIfAbsent(fluid, id -> new Cells(id));
        return taken;
    }

    public int amount(ResourceLocation fluid) {
        return amounts.getOrDefault(fluid, 0);
    }

    /** How many columns there are: one for each fluid, and the extra one for a new fluid. */
    public int columns() {
        return amounts.size() + 1;
    }

    /** The slots of this column (the last one is the extra column), or null if there is none. */
    @Nullable
    public Cells column(int index) {
        if (index < 0 || index >= columns()) {
            return null;
        }
        return index == amounts.size() ? fresh : cells.get(fluids().get(index));
    }

    /**
     * Move every full container in this storage that the hive can empty into the input slot of its fluid's column (a container of a fluid with no meter
     * yet goes to the new-fluid column), as many as the slot takes and only where the slot is empty or holds the same kind. Returns how many it moved.
     */
    public int pull(SimpleContainer storage) {
        int moved = 0;
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            FluidContainers.Drain drain = FluidContainers.drain(stack, null, Integer.MAX_VALUE);
            if (drain == null) {
                continue;
            }
            Cells column = cells.getOrDefault(drain.fluid(), fresh);
            ItemStack held = column.getItem(0);
            int space = held.isEmpty() ? INPUT_LIMIT : ItemStack.isSameItemSameComponents(held, stack) ? INPUT_LIMIT - held.getCount() : 0;
            int count = Math.min(space, stack.getCount());
            if (count <= 0) {
                continue;
            }
            if (held.isEmpty()) {
                column.getItems().set(0, stack.copyWithCount(count));
            } else {
                held.grow(count);
            }
            stack.shrink(count);
            moved += count;
        }
        if (moved > 0) {
            storage.setChanged();
        }
        return moved;
    }

    /**
     * Put as many of this stack as fit into the input slot it belongs in, taking them from the stack: a full container goes to the top slot of its
     * fluid's column (the new-fluid column if the fluid has no meter), an empty one to the bottom slot of the first column it can be filled from.
     * Returns how many went in.
     */
    public int insert(ItemStack stack) {
        Cells column = null;
        int part = 0;
        FluidContainers.Drain drain = FluidContainers.drain(stack, null, Integer.MAX_VALUE);
        if (drain != null) {
            column = cells.getOrDefault(drain.fluid(), fresh);
        } else {
            part = 1;
            for (ResourceLocation id : amounts.keySet()) {
                if (FluidContainers.canHold(stack, id)) {
                    column = cells.get(id);
                    break;
                }
            }
        }
        if (column == null) {
            return 0;
        }
        ItemStack held = column.getItem(part);
        int space = held.isEmpty() ? INPUT_LIMIT : ItemStack.isSameItemSameComponents(held, stack) ? INPUT_LIMIT - held.getCount() : 0;
        int count = Math.min(space, stack.getCount());
        if (count <= 0) {
            return 0;
        }
        if (held.isEmpty()) {
            column.getItems().set(part, stack.copyWithCount(count));
        } else {
            held.grow(count);
        }
        stack.shrink(count);
        return count;
    }

    /** Empty and fill what is in the slots, as far as the meters allow. Called when a slot changes, and every so often while the menu is open. */
    public void processAll() {
        for (Cells column : new ArrayList<>(cells.values())) {
            process(column);
        }
        process(fresh);
    }

    private void process(Cells column) {
        if (busy) {
            return;
        }
        busy = true;
        try {
            ResourceLocation key = column.key();
            // Full containers in the top slot, one at a time, while the meter has room and the output window can take what is left of them.
            for (int guard = 0; guard < OUTPUT_LIMIT && !column.getItem(0).isEmpty(); guard++) {
                FluidContainers.Drain drain = FluidContainers.drain(column.getItem(0), key, key == null ? CAPACITY : capacity(key) - amount(key));
                if (drain == null || drain.units() > capacity(drain.fluid()) - amount(drain.fluid()) || !canOutput(drain.emptied())) {
                    break;
                }
                amounts.merge(drain.fluid(), drain.units(), Integer::sum);
                cells.computeIfAbsent(drain.fluid(), id -> new Cells(id));
                output(drain.emptied());
                column.removeItem(0, 1);
            }
            // Empty containers in the bottom slot, the same way: what they are filled into goes to the output window.
            for (int guard = 0; key != null && guard < OUTPUT_LIMIT && !column.getItem(1).isEmpty(); guard++) {
                FluidContainers.Fill fill = FluidContainers.fill(column.getItem(1), key, amount(key));
                if (fill == null || !canOutput(fill.filled())) {
                    break;
                }
                amounts.merge(key, -fill.units(), Integer::sum);
                output(fill.filled());
                column.removeItem(1, 1);
            }
        } finally {
            busy = false;
        }
    }

    /** The output window: where emptied and filled containers come out. It holds one kind of item at a time. */
    public SimpleContainer outputSlot() {
        return outputSlot;
    }

    /** Whether this can go in the output window now: it is empty, or holds the same item with room for one more. */
    private boolean canOutput(ItemStack result) {
        ItemStack held = outputSlot.getItem(0);
        return held.isEmpty() || (ItemStack.isSameItemSameComponents(held, result) && held.getCount() + result.getCount() <= OUTPUT_LIMIT);
    }

    private void output(ItemStack result) {
        ItemStack held = outputSlot.getItem(0);
        if (held.isEmpty()) {
            outputSlot.getItems().set(0, result.copy());
        } else {
            held.grow(result.getCount());
        }
    }

    /** A short text that changes whenever the amounts or the fluids do, so that the screen is only told when it has to be. */
    public String signature() {
        StringBuilder text = new StringBuilder();
        amounts.forEach((id, amount) -> text.append(id).append('=').append(amount).append(';'));
        return text.toString();
    }

    // ---- saving ----

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        amounts.forEach((id, amount) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", id.toString());
            entry.putInt("Amount", amount);
            CompoundTag items = new CompoundTag();
            ContainerHelper.saveAllItems(items, cells.get(id).getItems(), registries);
            entry.put("Items", items);
            list.add(entry);
        });
        tag.put(TAG, list);
        CompoundTag freshItems = new CompoundTag();
        ContainerHelper.saveAllItems(freshItems, fresh.getItems(), registries);
        tag.put("Fresh", freshItems);
        CompoundTag outputItems = new CompoundTag();
        ContainerHelper.saveAllItems(outputItems, outputSlot.getItems(), registries);
        tag.put("Output", outputItems);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        for (Tag raw : tag.getList(TAG, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("Id"));
            if (id == null) {
                continue;
            }
            amounts.put(id, Math.max(0, Math.min(capacity(id), entry.getInt("Amount"))));
            Cells column = cells.computeIfAbsent(id, key -> new Cells(key));
            ContainerHelper.loadAllItems(entry.getCompound("Items"), column.getItems(), registries);
        }
        ContainerHelper.loadAllItems(tag.getCompound("Fresh"), fresh.getItems(), registries);
        ContainerHelper.loadAllItems(tag.getCompound("Output"), outputSlot.getItems(), registries);
    }
}
