package com.projecthivemind;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;

/**
 * The brewing stand built into the Hive Heart, once the hive has consumed a brewing stand on the Evolve tab. It brews the way a brewing stand block does (same recipes, same
 * blaze powder fuel, same 20 seconds) but is not a block: it is five slots and two counters that live on the Heart, and it
 * keeps working while the menu is closed.
 */
public final class HiveBrewing {
    /** Slots 0 to 2 are the bottles, 3 the ingredient, 4 the blaze powder. */
    public static final int BOTTLES = 3;
    public static final int INGREDIENT = 3;
    public static final int FUEL = 4;
    public static final int BREW_TICKS = 400;
    /** How many brews one blaze powder pays for. */
    public static final int FUEL_CHARGES = 20;

    private static final String ITEMS_TAG = "BrewingItems";
    private static final String TIME_TAG = "BrewingTime";
    private static final String FUEL_TAG = "BrewingFuel";

    private final SimpleContainer items = new SimpleContainer(5);
    private int brewTime;
    private int fuel;
    /** What was being brewed with when this brew started: a different ingredient in the slot stops the brew. */
    private Item ingredient = Items.AIR;

    public SimpleContainer items() {
        return items;
    }

    public int brewTime() {
        return brewTime;
    }

    public int fuel() {
        return fuel;
    }

    private boolean brewable(PotionBrewing brewing) {
        ItemStack ingredientStack = items.getItem(INGREDIENT);
        if (ingredientStack.isEmpty() || !brewing.isIngredient(ingredientStack)) {
            return false;
        }
        for (int i = 0; i < BOTTLES; i++) {
            ItemStack bottle = items.getItem(i);
            if (!bottle.isEmpty() && brewing.hasMix(bottle, ingredientStack)) {
                return true;
            }
        }
        return false;
    }

    /** One tick of brewing, the way a brewing stand does it. Call every server tick while the brewing stand exists. */
    public void tick(ServerLevel level) {
        ItemStack fuelStack = items.getItem(FUEL);
        if (fuel <= 0 && fuelStack.is(Items.BLAZE_POWDER)) {
            fuel = FUEL_CHARGES;
            fuelStack.shrink(1);
            items.setChanged();
        }
        boolean brewable = brewable(level.potionBrewing());
        ItemStack ingredientStack = items.getItem(INGREDIENT);
        if (brewTime > 0) {
            brewTime--;
            if (brewTime == 0 && brewable) {
                brew(level);
            } else if (!brewable || !ingredientStack.is(ingredient)) {
                brewTime = 0;
            }
            items.setChanged();
        } else if (brewable && fuel > 0) {
            fuel--;
            brewTime = BREW_TICKS;
            ingredient = ingredientStack.getItem();
            items.setChanged();
        }
    }

    private void brew(ServerLevel level) {
        ItemStack ingredientStack = items.getItem(INGREDIENT);
        PotionBrewing brewing = level.potionBrewing();
        for (int i = 0; i < BOTTLES; i++) {
            items.setItem(i, brewing.mix(ingredientStack, items.getItem(i)));
        }
        if (ingredientStack.hasCraftingRemainingItem()) {
            ItemStack remainder = ingredientStack.getCraftingRemainingItem();
            ingredientStack.shrink(1);
            items.setItem(INGREDIENT, ingredientStack.isEmpty() ? remainder : ingredientStack);
        } else {
            ingredientStack.shrink(1);
        }
        items.setChanged();
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(ITEMS_TAG, ContainerHelper.saveAllItems(new CompoundTag(), items.getItems(), registries));
        tag.putInt(TIME_TAG, brewTime);
        tag.putInt(FUEL_TAG, fuel);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        items.clearContent();
        if (tag.contains(ITEMS_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(ITEMS_TAG), items.getItems(), registries);
        }
        brewTime = tag.getInt(TIME_TAG);
        fuel = tag.getInt(FUEL_TAG);
        ingredient = items.getItem(INGREDIENT).getItem();
    }
}
