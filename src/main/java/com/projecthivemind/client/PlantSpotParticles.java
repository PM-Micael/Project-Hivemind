package com.projecthivemind.client;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.SyncUnitsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * While a collector is selected, every one of its planting spots sparkles: green stars (crops) or white sparks (saplings) rising
 * off the soil block, so the
 * player can see exactly where it plants. Client only.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class PlantSpotParticles {
    /** A burst every this many ticks, per spot. */
    private static final int INTERVAL = 4;

    private PlantSpotParticles() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !ClientState.hiveMode() || minecraft.isPaused()
                || minecraft.player.tickCount % INTERVAL != 0) {
            return;
        }
        for (int id : ClientSelection.selected()) {
            if (!(level.getEntity(id) instanceof HiveUnit unit) || unit.kind() != UnitKind.COLLECTOR) {
                continue;
            }
            SyncUnitsPayload.Entry entry = ClientUnits.entry(id);
            if (entry == null) {
                continue;
            }
            RandomSource random = level.random;
            // Crop spots: green stars. Sapling spots: white sparks. So the two can be told apart.
            sparkle(level, random, entry.task().spots(), ParticleTypes.HAPPY_VILLAGER);
            sparkle(level, random, entry.task().saplingSpots(), ParticleTypes.END_ROD);
        }
    }

    private static void sparkle(ClientLevel level, RandomSource random, java.util.List<BlockPos> spots, net.minecraft.core.particles.SimpleParticleType type) {
        for (BlockPos spot : spots) {
            for (int i = 0; i < 3; i++) {
                level.addParticle(type, spot.getX() + random.nextDouble(), spot.getY() + 1.05D + random.nextDouble() * 0.3D,
                        spot.getZ() + random.nextDouble(), 0.0D, 0.04D, 0.0D);
            }
        }
    }
}
