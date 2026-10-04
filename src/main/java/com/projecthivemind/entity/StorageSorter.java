package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps the hive's storage in order. Every stack is merged with others of its kind (up to a full stack), and the stacks are laid out
 * one after another, from the first slot, in alphabetical order of their names. Called now and then from the Heart; when the storage
 * is already in order it changes nothing.
 */
public final class StorageSorter {
    private StorageSorter() {
    }

    /** Sort and merge the storage. Returns true if anything moved. */
    public static boolean sort(SimpleContainer storage) {
        // Merge: each stack goes into an existing stack of the same item (and the same data, so a damaged tool stays apart from a new one).
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack incoming = storage.getItem(i).copy();
            if (incoming.isEmpty()) {
                continue;
            }
            for (ItemStack existing : stacks) {
                if (ItemStack.isSameItemSameComponents(existing, incoming) && existing.getCount() < storage.getMaxStackSize(existing)) {
                    int moved = Math.min(incoming.getCount(), storage.getMaxStackSize(existing) - existing.getCount());
                    existing.grow(moved);
                    incoming.shrink(moved);
                    if (incoming.isEmpty()) {
                        break;
                    }
                }
            }
            if (!incoming.isEmpty()) {
                stacks.add(incoming);
            }
        }

        // Alphabetical by the name shown, then by the item's id so equal names (and different data) keep a steady order, then the fuller stack first.
        stacks.sort(Comparator.<ItemStack, String>comparing(stack -> stack.getHoverName().getString().toLowerCase(Locale.ROOT))
                .thenComparing(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));

        boolean changed = false;
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack wanted = i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(storage.getItem(i), wanted)) {
                storage.setItem(i, wanted);
                changed = true;
            }
        }
        return changed;
    }
}
