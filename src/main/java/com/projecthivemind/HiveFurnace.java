package com.projecthivemind;

import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * The furnace built into the Hive Heart, once the hive has consumed a furnace on the Evolve tab. It smelts the way a furnace block does (same recipes, same fuel, same
 * times) but is not a block: it is three slots and some counters that live on the Heart, and it keeps working while
 * the menu is closed. It gives no experience.
 */
public final class HiveFurnace {
    public static final int INPUT = 0;
    public static final int FUEL = 1;
    public static final int OUTPUT = 2;

    private static final String ITEMS_TAG = "FurnaceItems";
    private static final String LIT_TIME_TAG = "FurnaceLitTime";
    private static final String LIT_DURATION_TAG = "FurnaceLitDuration";
    private static final String COOK_TAG = "FurnaceCook";
    private static final String COOK_TOTAL_TAG = "FurnaceCookTotal";

    private final SimpleContainer items = new SimpleContainer(3);
    /** Ticks of fuel left, and how many the fuel that is burning gave in the first place. */
    private int litTime;
    private int litDuration;
    /** Ticks the current item has been cooking, and how long it takes. */
    private int cookingProgress;
    private int cookingTotal = 200;
    /** The fuel the player chose to keep the fuel slot filled with from the hive's storage, or null for none. */
    @javax.annotation.Nullable
    private Item autoFuel;

    public SimpleContainer items() {
        return items;
    }

    public int litTime() {
        return litTime;
    }

    public int litDuration() {
        return litDuration;
    }

    public int cookingProgress() {
        return cookingProgress;
    }

    public int cookingTotal() {
        return cookingTotal;
    }

    @javax.annotation.Nullable
    public Item autoFuel() {
        return autoFuel;
    }

    /** Choose the fuel to keep the fuel slot filled with (null for none); only something that burns is taken. */
    public void setAutoFuel(@javax.annotation.Nullable Item item) {
        autoFuel = item != null && item.getDefaultInstance().getBurnTime(RecipeType.SMELTING) > 0 ? item : null;
    }

    /**
     * Keep the fuel slot filled with the chosen fuel from the hive's storage: what is left of a burnt fuel (an empty bucket, after lava) goes back to the
     * storage, and the slot is topped up. Called now and then from the Heart.
     */
    public void refuel(SimpleContainer storage) {
        if (autoFuel == null) {
            return;
        }
        ItemStack slot = items.getItem(FUEL);
        if (!slot.isEmpty() && !slot.is(autoFuel)) {
            // Something else is in the slot: only what is no use as fuel (the leftover) is put away.
            if (isFuel(slot)) {
                return;
            }
            ItemStack rest = storage.addItem(slot.copy());
            items.setItem(FUEL, rest);
            if (!rest.isEmpty()) {
                return;
            }
            slot = ItemStack.EMPTY;
        }
        int limit = autoFuel.getDefaultMaxStackSize();
        for (int i = 0; i < storage.getContainerSize(); i++) {
            int have = slot.isEmpty() ? 0 : slot.getCount();
            if (have >= limit) {
                break;
            }
            ItemStack stored = storage.getItem(i);
            if (stored.isEmpty() || !stored.is(autoFuel) || (!slot.isEmpty() && !ItemStack.isSameItemSameComponents(slot, stored))) {
                continue;
            }
            int moved = Math.min(limit - have, stored.getCount());
            if (slot.isEmpty()) {
                slot = stored.copyWithCount(moved);
                items.setItem(FUEL, slot);
            } else {
                slot.grow(moved);
            }
            stored.shrink(moved);
            storage.setChanged();
            items.setChanged();
        }
    }

    /** True if this item burns as fuel. */
    public static boolean isFuel(ItemStack stack) {
        return stack.getBurnTime(RecipeType.SMELTING) > 0;
    }

    private Optional<RecipeHolder<SmeltingRecipe>> recipeFor(ServerLevel level, ItemStack input) {
        if (input.isEmpty()) {
            return Optional.empty();
        }
        return level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), level);
    }

    /** True if the recipe's result fits in the output slot right now. */
    private boolean canSmelt(ServerLevel level, RecipeHolder<SmeltingRecipe> recipe) {
        ItemStack result = recipe.value().assemble(new SingleRecipeInput(items.getItem(INPUT)), level.registryAccess());
        if (result.isEmpty()) {
            return false;
        }
        ItemStack output = items.getItem(OUTPUT);
        if (output.isEmpty()) {
            return true;
        }
        return ItemStack.isSameItemSameComponents(output, result)
                && output.getCount() + result.getCount() <= Math.min(items.getMaxStackSize(), output.getMaxStackSize());
    }

    /** One tick of smelting, the way a furnace block does it. Call every server tick while the furnace exists. */
    public void tick(ServerLevel level) {
        ItemStack input = items.getItem(INPUT);
        ItemStack fuel = items.getItem(FUEL);
        // Nothing to do, and nothing burning: skip the recipe lookup entirely.
        if (litTime <= 0 && (input.isEmpty() || fuel.isEmpty()) && cookingProgress <= 0) {
            return;
        }

        if (litTime > 0) {
            litTime--;
        }
        RecipeHolder<SmeltingRecipe> recipe = recipeFor(level, input).orElse(null);
        boolean smeltable = recipe != null && canSmelt(level, recipe);

        if (litTime <= 0 && smeltable && !fuel.isEmpty()) {
            int burn = fuel.getBurnTime(RecipeType.SMELTING);
            if (burn > 0) {
                litTime = burn;
                litDuration = burn;
                if (fuel.hasCraftingRemainingItem()) {
                    items.setItem(FUEL, fuel.getCraftingRemainingItem());
                } else {
                    fuel.shrink(1);
                    if (fuel.isEmpty()) {
                        items.setItem(FUEL, ItemStack.EMPTY);
                    }
                }
            }
        }

        if (litTime > 0 && smeltable) {
            cookingTotal = recipe.value().getCookingTime();
            if (++cookingProgress >= cookingTotal) {
                cookingProgress = 0;
                ItemStack result = recipe.value().assemble(new SingleRecipeInput(input), level.registryAccess());
                ItemStack output = items.getItem(OUTPUT);
                if (output.isEmpty()) {
                    items.setItem(OUTPUT, result.copy());
                } else {
                    output.grow(result.getCount());
                }
                input.shrink(1);
                if (input.isEmpty()) {
                    items.setItem(INPUT, ItemStack.EMPTY);
                }
                items.setChanged();
            }
        } else if (litTime <= 0 && cookingProgress > 0) {
            // Fire out: the half-cooked item cools down faster than it heated.
            cookingProgress = Mth.clamp(cookingProgress - 2, 0, cookingTotal);
        } else if (!smeltable) {
            cookingProgress = 0;
        }
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put(ITEMS_TAG, ContainerHelper.saveAllItems(new CompoundTag(), items.getItems(), registries));
        tag.putInt(LIT_TIME_TAG, litTime);
        tag.putInt(LIT_DURATION_TAG, litDuration);
        tag.putInt(COOK_TAG, cookingProgress);
        tag.putInt(COOK_TOTAL_TAG, cookingTotal);
        if (autoFuel != null) {
            tag.putString("AutoFuel", BuiltInRegistries.ITEM.getKey(autoFuel).toString());
        }
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        items.clearContent();
        if (tag.contains(ITEMS_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(ITEMS_TAG), items.getItems(), registries);
        }
        litTime = tag.getInt(LIT_TIME_TAG);
        litDuration = tag.getInt(LIT_DURATION_TAG);
        cookingProgress = tag.getInt(COOK_TAG);
        cookingTotal = tag.contains(COOK_TOTAL_TAG) ? tag.getInt(COOK_TOTAL_TAG) : 200;
        ResourceLocation fuelId = tag.contains("AutoFuel") ? ResourceLocation.tryParse(tag.getString("AutoFuel")) : null;
        autoFuel = fuelId == null ? null : BuiltInRegistries.ITEM.getOptional(fuelId).orElse(null);
    }
}
