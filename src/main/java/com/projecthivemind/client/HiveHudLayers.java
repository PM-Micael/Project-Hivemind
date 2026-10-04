package com.projecthivemind.client;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Lets the game's own health and armor layers draw the hive's hearts and armor, exactly as they do for a player in survival, so that anything that
 * changes how those look (a mod that squeezes extra hearts into one row, for one) applies to the hive too.
 *
 * <p>Those layers only draw for a player in a survival mode, and read the player's health, maximum health and armor. The hivemind is a spectator whose
 * own values mean nothing, so just before the health layer the player is shown, for the length of the health and armor layers, as a survival player
 * with the Hive Heart's health, maximum health and armor; the real values are put back before the next layer. {@link HiveHud} draws whatever
 * these layers did not.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class HiveHudLayers {
    /** The state to put back, while the player is shown as a survival player. */
    private static boolean borrowed;
    private static GameType savedMode;
    private static float savedHealth;
    private static double savedMaxBase;
    private static double savedArmorBase;
    private static float savedAbsorption;
    /** Whether the health and armor layers drew this frame (so {@link HiveHud} need not). */
    private static boolean heartsByLayer;
    private static boolean armorByLayer;

    private HiveHudLayers() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (borrowed && event.getName().equals(VanillaGuiLayers.FOOD_LEVEL)) {
            restore();
            return;
        }
        if (borrowed && event.getName().equals(VanillaGuiLayers.ARMOR_LEVEL)) {
            armorByLayer = true;
            return;
        }
        if (!event.getName().equals(VanillaGuiLayers.PLAYER_HEALTH)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (!ClientState.hiveMode() || ClientState.heartMaxHealth() <= 0.0F || minecraft.options.hideGui || player == null || minecraft.gameMode == null
                || minecraft.getCameraEntity() != player) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (maxHealth == null || armor == null) {
            return;
        }
        borrowed = true;
        heartsByLayer = true;
        savedMode = minecraft.gameMode.localPlayerMode;
        savedHealth = player.getHealth();
        savedMaxBase = maxHealth.getBaseValue();
        savedArmorBase = armor.getBaseValue();
        savedAbsorption = player.getAbsorptionAmount();
        minecraft.gameMode.localPlayerMode = GameType.SURVIVAL;
        // Maximum first: setting the health is held to it.
        maxHealth.setBaseValue(savedMaxBase + (ClientState.heartMaxHealth() - maxHealth.getValue()));
        player.setHealth(ClientState.heartHealth());
        armor.setBaseValue(savedArmorBase + (ClientState.heartArmor() - armor.getValue()));
        player.setAbsorptionAmount(0.0F);
    }

    /** Put the player's own values back (also called if the layers after the armor were never reached). */
    private static void restore() {
        if (!borrowed) {
            return;
        }
        borrowed = false;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (minecraft.gameMode != null) {
            minecraft.gameMode.localPlayerMode = savedMode;
        }
        if (player != null) {
            AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
            AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
            if (maxHealth != null) {
                maxHealth.setBaseValue(savedMaxBase);
            }
            if (armor != null) {
                armor.setBaseValue(savedArmorBase);
            }
            player.setHealth(savedHealth);
            player.setAbsorptionAmount(savedAbsorption);
        }
    }

    /** At the end of the frame, from {@link HiveHud}: make sure everything is back, and say what the layers drew. */
    static void endFrame() {
        restore();
    }

    static boolean takeHeartsByLayer() {
        boolean result = heartsByLayer;
        heartsByLayer = false;
        return result;
    }

    static boolean takeArmorByLayer() {
        boolean result = armorByLayer;
        armorByLayer = false;
        return result;
    }
}
