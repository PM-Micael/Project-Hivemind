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

    private static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    /** Register the settings, and the screen that edits them. Only called on a client. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    /** True while the fog of war is on. */
    public static boolean fogOfWar() {
        return FOG_OF_WAR.get();
    }
}
