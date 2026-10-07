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
    COPPER(Items.COPPER_BLOCK, 0, Reward.ABSORPTION),
    IRON(Items.IRON_BLOCK, 1, Reward.GOLEM),
    GOLD(Items.GOLD_BLOCK, 2, Reward.STACK_SIZE),
    DIAMOND(Items.DIAMOND_BLOCK, 3, Reward.STACK_SIZE),
    NETHERITE(Items.NETHERITE_BLOCK, 4, Reward.FIRE_PROOF),
    ENDER_PEARL(Items.ENDER_PEARL, 19, Reward.RESPAWN),
    LAPIS(Items.LAPIS_BLOCK, 17, Reward.STACK_SIZE),
    REDSTONE(Items.REDSTONE_BLOCK, 18, Reward.REDSTONE),
    SHULKER_BOX(Items.SHULKER_BOX, 8, Reward.SHULKER_TURRET),
    CACTUS(Items.CACTUS, 9, Reward.THORNS),
    ENCHANTING_TABLE(Items.ENCHANTING_TABLE, 10, Reward.ENCHANTING),
    ECHO_SHARD(Items.ECHO_SHARD, 12, Reward.SONIC_BOOM),
    POISONOUS_POTATO(Items.POISONOUS_POTATO, 13, Reward.POISON),
    WITHER_SKULL(Items.WITHER_SKELETON_SKULL, 14, Reward.WITHER),
    JUKEBOX(Items.JUKEBOX, 15, Reward.MUSIC),
    CARVED_PUMPKIN(Items.CARVED_PUMPKIN, 16, Reward.ENDER_PEACE),
    TURTLE_SHELL(Items.TURTLE_HELMET, 11, Reward.DEFENCE),
    LEATHER_HORSE_ARMOR(Items.LEATHER_HORSE_ARMOR, 20, Reward.DEFENCE),
    IRON_HORSE_ARMOR(Items.IRON_HORSE_ARMOR, 21, Reward.DEFENCE),
    GOLDEN_HORSE_ARMOR(Items.GOLDEN_HORSE_ARMOR, 22, Reward.DEFENCE),
    DIAMOND_HORSE_ARMOR(Items.DIAMOND_HORSE_ARMOR, 23, Reward.DEFENCE),
    WOLF_ARMOR(Items.WOLF_ARMOR, 24, Reward.DEFENCE),
    CARTOGRAPHY_TABLE(Items.CARTOGRAPHY_TABLE, 25, Reward.CARTOGRAPHY),
    ANVIL(Items.ANVIL, 26, Reward.ANVIL),
    GLOWSTONE(Items.GLOWSTONE, 27, Reward.LIGHT),
    RABBIT_FOOT(Items.RABBIT_FOOT, 28, Reward.SWIFT_SOLDIERS),
    COBWEB(Items.COBWEB, 29, Reward.SLOW_ENEMIES),
    TOTEM(Items.TOTEM_OF_UNDYING, 30, Reward.UNDYING),
    EXPERIENCE_BOTTLE(Items.EXPERIENCE_BOTTLE, 31, Reward.XP_TRICKLE),
    SEA_LANTERN(Items.SEA_LANTERN, 32, Reward.GUARDIAN_BEAM),
    CRAFTER(Items.CRAFTER, 33, Reward.RECIPE_MEMORY),
    CAKE(Items.CAKE, 34, Reward.CHEAP_ACTIONS),
    ENDER_EYE(Items.ENDER_EYE, 35, Reward.REINFORCEMENTS),
    CAULDRON(Items.CAULDRON, 36, Reward.FLUIDS);

    /** What a task gives. */
    public enum Reward {
        CRAFTING, FURNACE, BREWING, STACK_SIZE, SHULKER_TURRET, THORNS, ENCHANTING, DEFENCE, SONIC_BOOM, POISON, WITHER, MUSIC, ENDER_PEACE, RESPAWN, CARTOGRAPHY, ANVIL, LIGHT, SWIFT_SOLDIERS, SLOW_ENEMIES, UNDYING, XP_TRICKLE, GUARDIAN_BEAM, RECIPE_MEMORY, CHEAP_ACTIONS, ABSORPTION, GOLEM, FIRE_PROOF, REDSTONE, REINFORCEMENTS, FLUIDS
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
        if (this == ANVIL) {
            return stack.is(net.minecraft.tags.ItemTags.ANVIL);
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
    public long bit() {
        return 1L << bitIndex;
    }

    public boolean doneIn(long mask) {
        return (mask & bit()) != 0;
    }

    /** The task this item is for, if that task is not done yet; otherwise null (the item is not valid to consume). */
    @Nullable
    public static EvolveTask forItem(ItemStack stack, long doneMask) {
        for (EvolveTask task : values()) {
            if (!task.doneIn(doneMask) && task.accepts(stack)) {
                return task;
            }
        }
        return null;
    }

    /** The base defence the hive and its units get with these tasks done: 2 for each task that gives defence. */
    public static double defenceBonus(long doneMask) {
        double bonus = 0.0D;
        for (EvolveTask task : values()) {
            if (task.reward == Reward.DEFENCE && task.doneIn(doneMask)) {
                bonus += 2.0D;
            }
        }
        return bonus;
    }

    /** Ticks between the Heart's unit spawnings and between the units of a summoning: 10 seconds, or 5 with the ender pearl task done. */
    public static int spawnIntervalTicks(long doneMask) {
        return ENDER_PEARL.doneIn(doneMask) ? 100 : 200;
    }

    /** The last hive level that gives an extra stack: each level from 2 to this one adds one. */
    public static final int LAST_STACK_LEVEL = 6;
    /** What a slot holds of an item that does not stack on its own (tools, armor, potions) when it is exactly the same, before the multiplier. */
    public static final int UNSTACKABLE_BASE = 16;

    /** How many full stacks a storage slot holds, with these tasks done and at this hive level: 1 to begin with, one more for each task that gives stack size, and one for each hive level from 2 to 6. */
    public static int stackMultiplier(long doneMask, int hiveLevel) {
        int multiplier = 1 + Math.max(0, Math.min(hiveLevel, LAST_STACK_LEVEL) - 1);
        for (EvolveTask task : values()) {
            if (task.reward == Reward.STACK_SIZE && task.doneIn(doneMask)) {
                multiplier++;
            }
        }
        return multiplier;
    }

    /** The most of this item one storage slot holds: its usual stack size, or for things that do not stack, a fixed number of identical ones, times the multiplier. */
    public static int stackLimit(ItemStack stack, int multiplier) {
        int base = stack.getMaxStackSize();
        return (base > 1 ? base : UNSTACKABLE_BASE) * Math.max(1, multiplier);
    }
}
