package com.projecthivemind.client;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.UnitKind;
import com.projecthivemind.network.SyncUnitsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The area of a team: while its scout is out, a ring of blue flames on the ground all round it, as far out as the team keeps
 * together. Units outside it head straight back in.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class TeamAreaParticles {
    private static final int INTERVAL = 2;

    private TeamAreaParticles() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || !ClientState.hiveMode() || minecraft.isPaused()
                || minecraft.player.tickCount % INTERVAL != 0) {
            return;
        }
        for (SyncUnitsPayload.Entry entry : ClientUnits.all()) {
            if (!entry.team() || entry.kind() != UnitKind.SCOUT.ordinal()) {
                continue;
            }
            Entity scout = level.getEntity(entry.entityId());
            if (scout == null) {
                continue;
            }
            int radius = ClientTeams.radius(0);
            // About one flame for every two blocks of the ring, in random places, so it reads as a ring without being solid.
            int count = Math.max(6, (int) (Math.PI * radius));
            for (int i = 0; i < count; i++) {
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, scout.getX() + Math.cos(angle) * radius, scout.getY() + 0.15D,
                        scout.getZ() + Math.sin(angle) * radius, 0.0D, 0.01D, 0.0D);
            }
        }
    }
}
