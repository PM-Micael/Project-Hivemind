package com.projecthivemind.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's settings for one player's own game: how it looks and feels, nothing that changes the world. They live in
 * {@code config/projecthivemind-client.toml} and are edited from the mod list (Mods, Project Hivemind, Config).
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Fog of war: the dark over what the hive cannot see, and the mobs hidden in it. Off shows everything. */
    public static final ModConfigSpec.BooleanValue FOG_OF_WAR = BUILDER
            .comment("Fog of war: darken what your units cannot see, and hide the mobs in it. Turn off to see everything.")
            .translation("projecthivemind.configuration.fogOfWar")
            .define("fogOfWar", true);

    /** How the hive's health is drawn: as the game's hearts, or as the hive's own bar. */
    public enum HealthStyle {
        HEARTS, BAR
    }

    public static final ModConfigSpec.EnumValue<HealthStyle> HEALTH_STYLE = BUILDER
            .comment("How the hive's health is shown: HEARTS (rows of hearts, as in the game) or BAR (one red bar, cut by a line for every ten hearts).")
            .translation("projecthivemind.configuration.healthStyle")
            .defineEnum("healthStyle", HealthStyle.HEARTS);

    /** Whether the reward of an evolution task is shown before the task is done. */
    public static final ModConfigSpec.BooleanValue SHOW_EVOLUTION_REWARDS = BUILDER
            .comment("Show what an evolution task will give before it has been researched. Off keeps the rewards a surprise.")
            .translation("projecthivemind.configuration.showEvolutionRewards")
            .define("showEvolutionRewards", false);

    /** Whether the hive inventory is shown small: the storage in three rows and the crafting grid, for playing with a recipe viewer like JEI. */
    public static final ModConfigSpec.BooleanValue COMPACT_INVENTORY = BUILDER
            .comment("Show the hive inventory small, like the vanilla one: the storage in three rows and the crafting grid, with no tabs. Good with a recipe viewer such as JEI.")
            .translation("projecthivemind.configuration.compactInventory")
            .define("compactInventory", false);

    private static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    /** Register the settings, and the screen that edits them. Only called on a client. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    public static HealthStyle healthStyle() {
        return HEALTH_STYLE.get();
    }

    /** True while the rewards of tasks not yet done are shown. */
    public static boolean showEvolutionRewards() {
        return SHOW_EVOLUTION_REWARDS.get();
    }

    /** Switch the compact inventory on or off, and keep the choice for the next time. */
    public static void setCompactInventory(boolean on) {
        COMPACT_INVENTORY.set(on);
        SPEC.save();
    }

    /** True while the fog of war is on. */
    public static boolean fogOfWar() {
        return FOG_OF_WAR.get();
    }
}
