package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.HiveFluids;
import com.projecthivemind.network.SyncFluidsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/** What the server last said about the hive's fluids, for the Fluids tab: the meters, in order, and how full each is. */
public final class ClientFluids {
    private static List<SyncFluidsPayload.Entry> fluids = List.of();

    private ClientFluids() {
    }

    public static void update(List<SyncFluidsPayload.Entry> entries) {
        fluids = List.copyOf(entries);
    }

    public static void reset() {
        fluids = List.of();
    }

    public static List<SyncFluidsPayload.Entry> all() {
        return fluids;
    }

    /** The columns there are: one for each fluid, and the extra one for a new fluid. */
    public static int columns() {
        return fluids.size() + 1;
    }

    /** The colour of a meter's fluid (ARGB). */
    public static int color(ResourceLocation id) {
        if (id.equals(HiveFluids.WATER)) {
            return 0xFF3F76E4;
        }
        if (id.equals(HiveFluids.LAVA)) {
            return 0xFFFF6A00;
        }
        if (id.equals(HiveFluids.MILK)) {
            return 0xFFF2F2F2;
        }
        if (id.equals(HiveFluids.HONEY)) {
            return 0xFFE8A02D;
        }
        if (id.equals(HiveFluids.XP)) {
            return 0xFF7CFC00;
        }
        Fluid fluid = BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.get(id) : Fluids.EMPTY;
        int tint = IClientFluidTypeExtensions.of(fluid).getTintColor();
        return 0xFF000000 | tint;
    }

    /** The name of a fluid: ours by their own key, the game's by what its type is called. */
    public static Component name(ResourceLocation id) {
        if (id.getNamespace().equals("projecthivemind")) {
            return Component.translatable("fluid.projecthivemind." + id.getPath());
        }
        Fluid fluid = BuiltInRegistries.FLUID.containsKey(id) ? BuiltInRegistries.FLUID.get(id) : Fluids.EMPTY;
        return fluid == Fluids.EMPTY || Minecraft.getInstance().level == null ? Component.literal(id.toString()) : fluid.getFluidType().getDescription();
    }
}
