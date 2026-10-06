package com.projecthivemind;

import javax.annotation.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The rules of the hive's enchanting station, which both sides use (the screen to show what an enchantment would cost and whether it can go on the
 * item, the server to do it). An enchantment that is available goes on at its highest level, and costs lapis lazuli and levels of the hivemind's
 * experience that grow with that level.
 */
public final class HiveEnchanting {
    /** Levels of experience for each level of the enchantment, and the most it ever costs (the game's own ceiling for an enchanting table). */
    private static final int LEVELS_PER_ENCHANT_LEVEL = 5;
    private static final int MAX_LEVEL_COST = 30;

    private HiveEnchanting() {
    }

    /** The level the enchantment goes on at: its highest. */
    public static int levelOf(Holder<Enchantment> enchantment) {
        return Math.max(1, enchantment.value().getMaxLevel());
    }

    /** Lapis lazuli: one for each level of the enchantment. */
    public static int lapisCost(Holder<Enchantment> enchantment) {
        return levelOf(enchantment);
    }

    /** Levels of the hivemind's experience: 5 for each level of the enchantment, at most 30. */
    public static int levelCost(Holder<Enchantment> enchantment) {
        return Math.min(MAX_LEVEL_COST, LEVELS_PER_ENCHANT_LEVEL * levelOf(enchantment));
    }

    /**
     * Why this enchantment cannot go on this item, as the end of a text key ({@code screen.projecthivemind.enchant.problem.<this>}), or null if it
     * can: no item in the slot, an item it does not go on, one that has it at its highest level already, or one with an enchantment it does not mix with.
     * An ordinary book takes any enchantment and becomes an enchanted book.
     */
    @Nullable
    public static String problem(ItemStack stack, Holder<Enchantment> enchantment) {
        if (stack.isEmpty()) {
            return "no_item";
        }
        boolean book = stack.is(Items.BOOK);
        if (!book && !enchantment.value().isSupportedItem(stack)) {
            return "unsupported";
        }
        ItemEnchantments existing = book ? ItemEnchantments.EMPTY : EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (existing.getLevel(enchantment) >= levelOf(enchantment)) {
            return "already";
        }
        java.util.List<Holder<Enchantment>> others = new java.util.ArrayList<>(existing.keySet());
        others.remove(enchantment);
        if (!EnchantmentHelper.isEnchantmentCompatible(others, enchantment)) {
            return "incompatible";
        }
        return null;
    }
}
