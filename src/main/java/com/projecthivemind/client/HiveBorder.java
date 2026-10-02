package com.projecthivemind.client;


import com.projecthivemind.ProjectHivemind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * Small red specks along the edge of the hive area, so the player can see how far it goes. Client only, in the RTS view,
 * and only around where the camera is: the edge is a square round the Heart, as far out as its level allows (see
 * HiveLevel), and the specks are placed along it, a few every tick, on the ground, drifting up.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class HiveBorder {
    private static final DustParticleOptions SPECK = new DustParticleOptions(new Vector3f(1.0F, 0.32F, 0.2F), 1.4F);
    /** Specks are only made along the stretch of edge this close (in blocks) to the camera. */
    private static final double NEAR = 40.0D;
    private static final int PER_TICK = 12;

    private HiveBorder() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        BlockPos center = ClientState.borderCenter();
        if (level == null || minecraft.player == null || !ClientState.hiveMode() || center == null || minecraft.isPaused()) {
            return;
        }
        int radius = ClientState.borderRadius();
        // The edge of the hive area: from the Heart's block minus the radius to the same plus one (the area covers
        // whole blocks), the same box HiveArea.areaBox gives.
        double minX = center.getX() - radius;
        double maxX = center.getX() + radius + 1;
        double minZ = center.getZ() - radius;
        double maxZ = center.getZ() + radius + 1;
        double cameraX = minecraft.player.getX();
        double cameraZ = minecraft.player.getZ();

        RandomSource random = level.random;
        for (int i = 0; i < PER_TICK; i++) {
            // A random point on the edge: pick a side, then a spot along it.
            double x;
            double z;
            switch (random.nextInt(4)) {
                case 0 -> {
                    x = minX;
                    z = Math.max(minZ, Math.min(maxZ, cameraZ + (random.nextDouble() * 2.0D - 1.0D) * NEAR));
                }
                case 1 -> {
                    x = maxX;
                    z = Math.max(minZ, Math.min(maxZ, cameraZ + (random.nextDouble() * 2.0D - 1.0D) * NEAR));
                }
                case 2 -> {
                    z = minZ;
                    x = Math.max(minX, Math.min(maxX, cameraX + (random.nextDouble() * 2.0D - 1.0D) * NEAR));
                }
                default -> {
                    z = maxZ;
                    x = Math.max(minX, Math.min(maxX, cameraX + (random.nextDouble() * 2.0D - 1.0D) * NEAR));
                }
            }
            if (Math.abs(x - cameraX) > NEAR * 1.5D || Math.abs(z - cameraZ) > NEAR * 1.5D || !level.hasChunkAt(BlockPos.containing(x, 0, z))) {
                continue;
            }
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)) + 0.15D;
            level.addParticle(SPECK, x, y, z, 0.0D, 0.03D, 0.0D);
        }
    }
}
