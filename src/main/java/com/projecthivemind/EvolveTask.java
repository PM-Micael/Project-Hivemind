package com.projecthivemind;

import javax.annotation.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The hive's evolution tasks: an item the hive has to consume to gain an upgrade. Each task is done once. The tasks that are done, and
 * their rewards, are shown on the Evolve tab of the hive menu; the item is consumed through the evolve slot on the Hive tab.
 *
 * <p>For now both rewards are the same, and they add up: each task done lets a stack of anything stackable in the hive's storage hold
 * one more full stack (a stack of 64 holds 128 with one task done, and 192 with both).
 */
public enum EvolveTask {
    COPPER(Items.COPPER_BLOCK),
    IRON(Items.IRON_BLOCK);

    private final Item item;

    EvolveTask(Item item) {
        this.item = item;
    }

    /** The item the hive has to consume. */
    public Item item() {
        return item;
    }

    /** This task's bit in the mask of tasks done. */
    public int bit() {
        return 1 << ordinal();
    }

    public boolean doneIn(int mask) {
        return (mask & bit()) != 0;
    }

    /** The task this item is for, if that task is not done yet; otherwise null (the item is not valid to consume). */
    @Nullable
    public static EvolveTask forItem(ItemStack stack, int doneMask) {
        for (EvolveTask task : values()) {
            if (!task.doneIn(doneMask) && stack.is(task.item)) {
                return task;
            }
        }
        return null;
    }

    /** How many full stacks a storage slot holds, with these tasks done: 1 to begin with, and one more for each. */
    public static int stackMultiplier(int doneMask) {
        return 1 + Integer.bitCount(doneMask);
    }
}
