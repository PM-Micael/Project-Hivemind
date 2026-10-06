package com.projecthivemind;

import java.util.Collection;

import javax.annotation.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The rules of the hive's enchanting station, which both sides use (the screen to show what an enchantment would cost and whether it can go on the
 * item, the server to do it). Each level of each enchantment is its own thing to make available (Sharpness I, Sharpness II...), by consuming an
 * enchanted book with exactly that level. An available enchantment goes on at the level it was made available at, and costs lapis lazuli and levels of
 * the hivemind's experience that grow with that level.
 */
public final class HiveEnchanting {
    /** Levels of experience for each level of the enchantment, and the most it ever costs (the game's own ceiling for an enchanting table). */
    private static final int LEVELS_PER_ENCHANT_LEVEL = 5;
    private static final int MAX_LEVEL_COST = 30;

    private HiveEnchanting() {
    }

    /** The highest level of the enchantment. */
    public static int maxLevel(Holder<Enchantment> enchantment) {
        return Math.max(1, enchantment.value().getMaxLevel());
    }

    /** How a level of an enchantment is written down where the hive keeps what it has made available: {@code minecraft:sharpness@3}. */
    public static String key(String id, int level) {
        return id + "@" + level;
    }

    /**
     * True if this level of the enchantment is available among these keys. (A key with no level, from before levels were told apart, was for the
     * highest level.)
     */
    public static boolean isAvailable(Collection<String> available, String id, Holder<Enchantment> enchantment, int level) {
        return available.contains(key(id, level)) || (level == maxLevel(enchantment) && available.contains(id));
    }

    /**
     * The levels of enchantments the hive could learn right now: those that an enchanted book in this storage has (a book with several enchantments
     * counts for each) and that are not available yet. As keys (see {@link #key}), in a stable order.
     */
    public static java.util.List<String> readyKeys(net.minecraft.world.Container storage, Collection<String> available, net.minecraft.core.RegistryAccess registries) {
        java.util.Set<String> ready = new java.util.TreeSet<>();
        net.minecraft.core.Registry<Enchantment> registry = registries.registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            if (!stack.is(Items.ENCHANTED_BOOK)) {
                continue;
            }
            ItemEnchantments stored = stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            for (Holder<Enchantment> holder : stored.keySet()) {
                int level = stored.getLevel(holder);
                String id = registry.getKey(holder.value()) == null ? null : registry.getKey(holder.value()).toString();
                if (id != null && level >= 1 && level <= maxLevel(holder) && !isAvailable(available, id, holder, level)) {
                    ready.add(key(id, level));
                }
            }
        }
        return java.util.List.copyOf(ready);
    }

    /** Lapis lazuli: one for each level of the enchantment. */
    public static int lapisCost(int level) {
        return level;
    }

    /** Levels of the hivemind's experience: 5 for each level of the enchantment, at most 30. */
    public static int levelCost(int level) {
        return Math.min(MAX_LEVEL_COST, LEVELS_PER_ENCHANT_LEVEL * level);
    }

    /**
     * Why this level of the enchantment cannot go on this item, as the end of a text key ({@code screen.projecthivemind.enchant.problem.<this>}), or
     * null if it can: no item in the slot, an item it does not go on, one that has it at this level or higher already, or one with an enchantment it
     * does not mix with. An ordinary book takes any enchantment and becomes an enchanted book.
     */
    @Nullable
    public static String problem(ItemStack stack, Holder<Enchantment> enchantment, int level) {
        if (stack.isEmpty()) {
            return "no_item";
        }
        boolean book = stack.is(Items.BOOK);
        if (!book && !enchantment.value().isSupportedItem(stack)) {
            return "unsupported";
        }
        ItemEnchantments existing = book ? ItemEnchantments.EMPTY : EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (existing.getLevel(enchantment) >= level) {
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
