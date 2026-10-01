package com.projecthivemind;

import com.projecthivemind.menu.HiveMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, ProjectHivemind.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<HiveMenu>> HIVE = MENU_TYPES.register("hive",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> new HiveMenu(windowId, inventory)));

    private ModMenus() {
    }
}
