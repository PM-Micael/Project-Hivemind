package com.projecthivemind;

import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.menu.ScoutContainerMenu;
import com.projecthivemind.menu.ScoutTradeMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, ProjectHivemind.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<HiveMenu>> HIVE = MENU_TYPES.register("hive",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> new HiveMenu(windowId, inventory, extraData.readVarInt())));

    /** A container opened through a scout. The opening data is how many slots the container and the hive storage have. */
    public static final DeferredHolder<MenuType<?>, MenuType<ScoutContainerMenu>> SCOUT_CONTAINER = MENU_TYPES.register("scout_container",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> new ScoutContainerMenu(windowId, inventory, extraData.readVarInt(), extraData.readVarInt())));

    /** Trading with a villager through a scout. The offers follow in their own packet. */
    public static final DeferredHolder<MenuType<?>, MenuType<ScoutTradeMenu>> SCOUT_TRADE = MENU_TYPES.register("scout_trade",
            () -> IMenuTypeExtension.create((windowId, inventory, extraData) -> new ScoutTradeMenu(windowId, inventory, extraData.readVarInt())));

    private ModMenus() {
    }
}
