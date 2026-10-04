package com.projecthivemind.client;

import javax.annotation.Nullable;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The hive's jukebox, as the owner hears it: the disc the server says is in the jukebox plays from the camera, not from the Heart, so it sounds the
 * same wherever the camera is. It is heard at the jukebox/music-disc volume of the sound settings, plays once through, and stops when the disc
 * comes out or the player leaves the hive view.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class ClientMusic {
    /** The disc last told by the server (so the same word again does not restart a song that is over), and the sound that is playing. */
    private static String playing = "";
    @Nullable
    private static SoundInstance instance;

    private ClientMusic() {
    }

    /** The server's word on which disc is in the jukebox (empty for none). */
    public static void set(String disc) {
        if (disc.equals(playing)) {
            return;
        }
        stop();
        playing = disc;
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation id = disc.isEmpty() ? null : ResourceLocation.tryParse(disc);
        if (id == null || minecraft.level == null) {
            return;
        }
        BuiltInRegistries.ITEM.getOptional(id).flatMap(item -> JukeboxSong.fromStack(minecraft.level.registryAccess(), new ItemStack(item))).ifPresent(song -> {
            // No attenuation, and relative to the listener: it is the same wherever the camera is.
            instance = new SimpleSoundInstance(song.value().soundEvent().value().getLocation(), SoundSource.RECORDS, 1.0F, 1.0F,
                    RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true);
            minecraft.getSoundManager().play(instance);
        });
    }

    private static void stop() {
        if (instance != null) {
            Minecraft.getInstance().getSoundManager().stop(instance);
            instance = null;
        }
    }

    /** Outside the hive view (another world, or not a hivemind any more) there is no hive music. */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if ((minecraft.level == null || minecraft.player == null || !ClientState.hiveMode()) && (!playing.isEmpty() || instance != null)) {
            stop();
            playing = "";
        }
    }
}
