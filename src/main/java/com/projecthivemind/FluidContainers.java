package com.projecthivemind;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * What can carry a fluid into or out of the hive: the vanilla buckets and bottles (a table, since they are not fluid handlers, and milk, honey and
 * experience are not game fluids at all), and anything else that is a NeoForge fluid handler item (modded buckets and tanks). Amounts are in the
 * hive's units: a bucket is {@link HiveFluids#BUCKET} (a millibucket is 3), so that a bottle, a third of a bucket, is a whole number. A bottle o'
 * enchanting is 7 experience points.
 */
public final class FluidContainers {
    /** A full container that can be emptied into the hive, and what is left of it. */
    public record Drain(ResourceLocation fluid, int units, ItemStack emptied) {
    }

    /** An empty container that was filled from the hive: how much it took, and the full container. */
    public record Fill(int units, ItemStack filled) {
    }

    private record Entry(ResourceLocation fluid, Predicate<ItemStack> full, Supplier<ItemStack> fullItem, Predicate<ItemStack> empty,
                         Supplier<ItemStack> emptyItem, int units) {
    }

    private static final int BOTTLE = HiveFluids.BUCKET / 3;

    private static boolean waterBottle(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return stack.is(Items.POTION) && contents != null && contents.is(Potions.WATER);
    }

    private static final List<Entry> TABLE = List.of(
            new Entry(HiveFluids.WATER, stack -> stack.is(Items.WATER_BUCKET), () -> new ItemStack(Items.WATER_BUCKET), stack -> stack.is(Items.BUCKET),
                    () -> new ItemStack(Items.BUCKET), HiveFluids.BUCKET),
            new Entry(HiveFluids.LAVA, stack -> stack.is(Items.LAVA_BUCKET), () -> new ItemStack(Items.LAVA_BUCKET), stack -> stack.is(Items.BUCKET),
                    () -> new ItemStack(Items.BUCKET), HiveFluids.BUCKET),
            new Entry(HiveFluids.MILK, stack -> stack.is(Items.MILK_BUCKET), () -> new ItemStack(Items.MILK_BUCKET), stack -> stack.is(Items.BUCKET),
                    () -> new ItemStack(Items.BUCKET), HiveFluids.BUCKET),
            new Entry(HiveFluids.WATER, FluidContainers::waterBottle, () -> PotionContents.createItemStack(Items.POTION, Potions.WATER),
                    stack -> stack.is(Items.GLASS_BOTTLE), () -> new ItemStack(Items.GLASS_BOTTLE), BOTTLE),
            new Entry(HiveFluids.HONEY, stack -> stack.is(Items.HONEY_BOTTLE), () -> new ItemStack(Items.HONEY_BOTTLE), stack -> stack.is(Items.GLASS_BOTTLE),
                    () -> new ItemStack(Items.GLASS_BOTTLE), BOTTLE),
            new Entry(HiveFluids.XP, stack -> stack.is(Items.EXPERIENCE_BOTTLE), () -> new ItemStack(Items.EXPERIENCE_BOTTLE), stack -> stack.is(Items.GLASS_BOTTLE),
                    () -> new ItemStack(Items.GLASS_BOTTLE), HiveFluids.XP_PER_BOTTLE));

    private FluidContainers() {
    }

    @Nullable
    private static IFluidHandlerItem handlerOf(ItemStack stack) {
        return stack.isEmpty() ? null : FluidUtil.getFluidHandler(stack.copyWithCount(1)).orElse(null);
    }

    /**
     * What emptying this full container would do, or null if it is not a full container, is not of {@code only} (null for any fluid), or what is
     * in it does not fit in {@code room} units.
     */
    @Nullable
    public static Drain drain(ItemStack stack, @Nullable ResourceLocation only, int room) {
        if (stack.isEmpty()) {
            return null;
        }
        for (Entry entry : TABLE) {
            if (entry.full().test(stack)) {
                return (only == null || only.equals(entry.fluid())) && entry.units() <= room ? new Drain(entry.fluid(), entry.units(), entry.emptyItem().get()) : null;
            }
        }
        IFluidHandlerItem handler = handlerOf(stack);
        if (handler == null) {
            return null;
        }
        FluidStack inside = handler.drain(room / 3, IFluidHandler.FluidAction.SIMULATE);
        if (inside.isEmpty() || inside.getFluid() == Fluids.EMPTY) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(inside.getFluid());
        if (only != null && !only.equals(id)) {
            return null;
        }
        FluidStack taken = handler.drain(inside, IFluidHandler.FluidAction.EXECUTE);
        return taken.isEmpty() ? null : new Drain(id, taken.getAmount() * 3, handler.getContainer());
    }

    /** What filling this empty container from {@code available} units of the fluid would do, or null if it cannot be. */
    @Nullable
    public static Fill fill(ItemStack stack, ResourceLocation fluid, int available) {
        if (stack.isEmpty()) {
            return null;
        }
        for (Entry entry : TABLE) {
            if (entry.empty().test(stack) && entry.fluid().equals(fluid)) {
                return entry.units() <= available ? new Fill(entry.units(), entry.fullItem().get()) : null;
            }
        }
        IFluidHandlerItem handler = handlerOf(stack);
        Fluid real = BuiltInRegistries.FLUID.containsKey(fluid) ? BuiltInRegistries.FLUID.get(fluid) : null;
        if (handler == null || real == null || real == Fluids.EMPTY || available < 3) {
            return null;
        }
        int filled = handler.fill(new FluidStack(real, available / 3), IFluidHandler.FluidAction.EXECUTE);
        return filled <= 0 ? null : new Fill(filled * 3, handler.getContainer());
    }

    /** True if this is an empty container that could be filled with this fluid (whatever the hive has of it now). */
    public static boolean canHold(ItemStack stack, ResourceLocation fluid) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Entry entry : TABLE) {
            if (entry.empty().test(stack)) {
                return entry.fluid().equals(fluid);
            }
        }
        IFluidHandlerItem handler = handlerOf(stack);
        Fluid real = BuiltInRegistries.FLUID.containsKey(fluid) ? BuiltInRegistries.FLUID.get(fluid) : null;
        return handler != null && real != null && real != Fluids.EMPTY
                && handler.fill(new FluidStack(real, Integer.MAX_VALUE), IFluidHandler.FluidAction.SIMULATE) > 0;
    }
}
