package com.projecthivemind.entity;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.network.SyncMusicPayload;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The hive's jukebox: once the hive has consumed a jukebox (an evolution task), a music disc put in the jukebox slot of the hive menu plays for its
 * owner, from the camera: the client plays it so that it is heard the same wherever the camera is. This tells the owner's client which disc is
 * in, when it changes and every few seconds, so a client that has just joined catches up.
 */
public final class HiveMusic {
    private static final int CHECK_INTERVAL = 10;
    private static final int RESEND_INTERVAL = 100;

    private HiveMusic() {
    }

    /** The disc that should be playing: the one in the jukebox slot, if the hive has a jukebox and it is a music disc; otherwise empty. */
    public static String currentDisc(HiveHeart heart) {
        ItemStack stack = heart.jukeboxSlot().getItem(0);
        if (!EvolveTask.JUKEBOX.doneIn(heart.evolveMask()) || stack.isEmpty() || !stack.has(DataComponents.JUKEBOX_PLAYABLE)) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Called every tick from the Heart. */
    public static void tick(HiveHeart heart) {
        if (heart.tickCount % CHECK_INTERVAL != 0 || heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        String disc = currentDisc(heart);
        boolean changed = !disc.equals(heart.lastMusic());
        if (!changed && heart.tickCount % RESEND_INTERVAL != 0) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner != null) {
            PacketDistributor.sendToPlayer(owner, new SyncMusicPayload(disc));
            heart.setLastMusic(disc);
        }
    }
}
