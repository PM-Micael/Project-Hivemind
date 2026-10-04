package com.projecthivemind;

import javax.annotation.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The hive's evolution tasks: an item the hive has to consume to gain an upgrade. Each task is done once. The tasks that are done, and
 * their rewards, are shown on the Evolve tab of the hive menu; the item is consumed through the evolve slot on the Hive tab.
 *
 * <p>There are two kinds of reward. Consuming a workstation (a crafting table, a furnace, a brewing stand) lets the hive use that
 * workstation. Consuming a block of metal lets a stack of anything stackable in the hive's storage hold one more full stack, and those
 * add up (a stack of 64 holds 128 with one done, 192 with two, and so on).
 *
 * <p>Each task has its own fixed bit in the mask of tasks done, which is saved: new tasks get new bits, and the order here is only the
 * order they are shown in.
 */
public enum EvolveTask {
    CRAFTING_TABLE(Items.CRAFTING_TABLE, 5, Reward.CRAFTING),
    FURNACE(Items.FURNACE, 6, Reward.FURNACE),
    BREWING_STAND(Items.BREWING_STAND, 7, Reward.BREWING),
    COPPER(Items.COPPER_BLOCK, 0, Reward.STACK_SIZE),
    IRON(Items.IRON_BLOCK, 1, Reward.STACK_SIZE),
    GOLD(Items.GOLD_BLOCK, 2, Reward.STACK_SIZE),
    DIAMOND(Items.DIAMOND_BLOCK, 3, Reward.STACK_SIZE),
    NETHERITE(Items.NETHERITE_BLOCK, 4, Reward.STACK_SIZE),
    ENDER_PEARL(Items.ENDER_PEARL, 19, Reward.PORTAL),
    LAPIS(Items.LAPIS_BLOCK, 17, Reward.STACK_SIZE),
    REDSTONE(Items.REDSTONE_BLOCK, 18, Reward.STACK_SIZE),
    SHULKER_BOX(Items.SHULKER_BOX, 8, Reward.SHULKER_TURRET),
    CACTUS(Items.CACTUS, 9, Reward.THORNS),
    ENCHANTING_TABLE(Items.ENCHANTING_TABLE, 10, Reward.ENCHANTING),
    ECHO_SHARD(Items.ECHO_SHARD, 12, Reward.SONIC_BOOM),
    POISONOUS_POTATO(Items.POISONOUS_POTATO, 13, Reward.POISON),
    WITHER_SKULL(Items.WITHER_SKELETON_SKULL, 14, Reward.WITHER),
    JUKEBOX(Items.JUKEBOX, 15, Reward.MUSIC),
    CARVED_PUMPKIN(Items.CARVED_PUMPKIN, 16, Reward.ENDER_PEACE),
    TURTLE_SHELL(Items.TURTLE_HELMET, 11, Reward.DEFENCE);

    /** What a task gives. */
    public enum Reward {
        CRAFTING, FURNACE, BREWING, STACK_SIZE, SHULKER_TURRET, THORNS, ENCHANTING, DEFENCE, SONIC_BOOM, POISON, WITHER, MUSIC, ENDER_PEACE, PORTAL
    }

    private final Item item;
    private final int bitIndex;
    private final Reward reward;

    EvolveTask(Item item, int bitIndex, Reward reward) {
        this.item = item;
        this.bitIndex = bitIndex;
        this.reward = reward;
    }

    /** True if this item completes the task: the task's item, or for the shulker box any colour of shulker box. */
    public boolean accepts(ItemStack stack) {
        if (this == SHULKER_BOX) {
            return stack.getItem() instanceof net.minecraft.world.item.BlockItem block && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
        }
        return stack.is(item);
    }

    /** The item the hive has to consume. */
    public Item item() {
        return item;
    }

    public Reward reward() {
        return reward;
    }

    /** This task's bit in the mask of tasks done. */
    public int bit() {
        return 1 << bitIndex;
    }

    public boolean doneIn(int mask) {
        return (mask & bit()) != 0;
    }

    /** The task this item is for, if that task is not done yet; otherwise null (the item is not valid to consume). */
    @Nullable
    public static EvolveTask forItem(ItemStack stack, int doneMask) {
        for (EvolveTask task : values()) {
            if (!task.doneIn(doneMask) && task.accepts(stack)) {
                return task;
            }
        }
        return null;
    }

    /** How many full stacks a storage slot holds, with these tasks done: 1 to begin with, and one more for each task that gives stack size. */
    public static int stackMultiplier(int doneMask) {
        int multiplier = 1;
        for (EvolveTask task : values()) {
            if (task.reward == Reward.STACK_SIZE && task.doneIn(doneMask)) {
                multiplier++;
            }
        }
        return multiplier;
    }
}
