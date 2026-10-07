package com.projecthivemind.entity;

import java.util.function.IntSupplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * The hive's shared storage. It is an ordinary container except that a stack of anything stackable can be larger than the item's usual size,
 * by the multiplier the evolution tasks give (see EvolveTask): a stack of 64 holds 128 at twice the size. Things that do not stack (tools,
 * armor) stay at one. Because the game's own saving only keeps counts up to 99, this saves the counts itself.
 */
public class HiveStorage extends SimpleContainer {
    private final IntSupplier multiplier;

    public HiveStorage(int size, IntSupplier multiplier) {
        super(size);
        this.multiplier = multiplier;
    }

    /** The most of this item a slot holds. */
    public int limitFor(ItemStack stack) {
        return com.projecthivemind.EvolveTask.stackLimit(stack, multiplier.getAsInt());
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return limitFor(stack);
    }

    /** Add the stack, first to stacks of the same kind with room, then to empty slots. What did not fit is returned. */
    @Override
    public ItemStack addItem(ItemStack stack) {
        ItemStack rest = stack.copy();
        int limit = limitFor(rest);
        for (int i = 0; i < getContainerSize() && !rest.isEmpty(); i++) {
            ItemStack there = getItem(i);
            if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, rest) && there.getCount() < limit) {
                int moved = Math.min(limit - there.getCount(), rest.getCount());
                there.grow(moved);
                rest.shrink(moved);
            }
        }
        for (int i = 0; i < getContainerSize() && !rest.isEmpty(); i++) {
            if (getItem(i).isEmpty()) {
                setItem(i, rest.split(Math.min(limit, rest.getCount())));
            }
        }
        setChanged();
        return rest.isEmpty() ? ItemStack.EMPTY : rest;
    }

    @Override
    public boolean canAddItem(ItemStack stack) {
        int limit = limitFor(stack);
        int room = 0;
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack there = getItem(i);
            if (there.isEmpty()) {
                room += limit;
            } else if (ItemStack.isSameItemSameComponents(there, stack)) {
                room += Math.max(0, limit - there.getCount());
            }
            if (room >= stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    // ---- saving ----

    private static final String SLOTS = "Slots";

    /** Each stack is saved as one of the item (which the game's saving keeps) and the real count beside it. */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag saved = new CompoundTag();
                saved.putInt("Slot", i);
                saved.put("Stack", stack.copyWithCount(1).save(registries));
                saved.putInt("Count", stack.getCount());
                list.add(saved);
            }
        }
        tag.put(SLOTS, list);
        return tag;
    }

    /** Load what {@link #save} wrote, or what an older version saved in the game's own way. */
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(SLOTS)) {
            ContainerHelper.loadAllItems(tag, getItems(), registries);
            return;
        }
        for (Tag entry : tag.getList(SLOTS, Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) entry;
            int slot = saved.getInt("Slot");
            if (slot >= 0 && slot < getContainerSize()) {
                ItemStack stack = ItemStack.parse(registries, saved.get("Stack")).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    stack.setCount(Math.max(1, saved.getInt("Count")));
                    getItems().set(slot, stack);
                }
            }
        }
    }
}
