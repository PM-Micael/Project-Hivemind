package com.projecthivemind.client;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Hive Sight on the client: in the RTS view, a mob the hive cannot see is not drawn. Your own units and Hive Hearts
 * are always drawn. Only mobs are hidden for now; terrain and dropped items are not.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class SightEvents {
    private SightEvents() {
    }

    /** Whether a mob should be hidden from the player right now. */
    public static boolean isHidden(Mob mob) {
        if (!ClientState.hiveMode() || mob instanceof HiveHeart) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (mob instanceof HiveUnit unit && minecraft.player != null && minecraft.player.getUUID().equals(unit.ownerId())) {
            return false;
        }
        return !ClientSight.isVisible(mob.getId());
    }

    @SubscribeEvent
    static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Mob mob && isHidden(mob)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSight.reset();
    }
}
