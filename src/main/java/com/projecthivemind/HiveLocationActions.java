package com.projecthivemind;

import java.util.ArrayList;
import java.util.List;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveLocations;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.network.SyncLocationsPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the Locations tab asks of the server: find the nearest stronghold and save it, forget a location, and send a scout to one as a
 * {@link UnitAction.Kind#TRAVEL} job. The list itself is kept on the Heart ({@link HiveLocations}).
 */
public final class HiveLocationActions {
    /** How far the stronghold search looks, in chunks. */
    private static final int SEARCH_RADIUS = 200;

    private HiveLocationActions() {
    }

    /** Tell the owner's client the hive's locations. */
    public static void sync(ServerPlayer owner, HiveHeart heart) {
        List<SyncLocationsPayload.Location> locations = new ArrayList<>();
        for (HiveLocations.Location location : heart.locations().list()) {
            if (locations.size() < SyncLocationsPayload.MAX_ENTRIES) {
                locations.add(new SyncLocationsPayload.Location(location.kind().ordinal(), location.pos()));
            }
        }
        PacketDistributor.sendToPlayer(owner, new SyncLocationsPayload(locations));
    }

    /** The player pressed the Eye of Ender button: the stronghold nearest the Heart is saved as a location. Needs the hive stage and the task done. */
    public static void locateStronghold(ServerPlayer player) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE
                || !EvolveTask.ENDER_EYE.doneIn(heart.evolveMask()) || !(heart.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.locations.overworld_only"), true);
            return;
        }
        BlockPos found = level.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, heart.blockPosition(), SEARCH_RADIUS, false);
        if (found == null) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.locations.none_found"), true);
            return;
        }
        if (heart.locations().has(HiveLocations.Kind.STRONGHOLD, found)) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.locations.already_known"), true);
            return;
        }
        if (!heart.locations().add(HiveLocations.Kind.STRONGHOLD, found)) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.locations.full"), true);
            return;
        }
        player.displayClientMessage(Component.translatable("message.projecthivemind.locations.added", found.getX(), found.getZ()), true);
        sync(player, heart);
    }

    /** The player removed a location (an index of the list). Scouts already on their way there carry on to where they were sent. */
    public static void delete(ServerPlayer player, int index) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE
                || index < 0 || index >= heart.locations().list().size()) {
            return;
        }
        heart.locations().list().remove(index);
        sync(player, heart);
    }

    /** The player chose a scout for a location: it is put on the travel job, replacing any job it had. */
    public static void sendScout(ServerPlayer player, int index, int scoutId) {
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null || HivemindManager.get(player).stage() != HivemindStage.HIVE
                || index < 0 || index >= heart.locations().list().size()) {
            return;
        }
        if (!(HivemindManager.findById(player, scoutId) instanceof HiveScout scout) || !scout.isAlive()
                || !player.getUUID().equals(scout.ownerId())) {
            return;
        }
        BlockPos target = heart.locations().list().get(index).pos();
        if (scout.level().dimension() != Level.OVERWORLD || scout.isControlled()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.locations.scout_unavailable"), true);
            return;
        }
        scout.setAction(UnitAction.travel(target));
        // A scout the player has selected obeys the player, so the job waits until it is let go (like any job set aside); say so.
        player.displayClientMessage(Component.translatable("message.projecthivemind.locations.sent", target.getX(), target.getZ()), true);
        HivemindManager.sendUnits(player);
    }
}
