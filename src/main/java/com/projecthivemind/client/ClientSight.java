package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.ProjectHivemind;
import com.projecthivemind.network.SyncEyesPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * The eyes the hive sees with, as last told by the server: where each is and how far it sees. The terrain fog is drawn from them.
 * Plain data only.
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID, value = Dist.CLIENT)
public final class ClientSight {
    private static List<SyncEyesPayload.EyePoint> eyes = List.of();
    private static int eyesVersion;

    private ClientSight() {
    }

    public static void updateEyes(List<SyncEyesPayload.EyePoint> newEyes) {
        eyes = List.copyOf(newEyes);
        eyesVersion++;
    }

    public static List<SyncEyesPayload.EyePoint> eyes() {
        return eyes;
    }

    /** Goes up every time the server sends new eyes. */
    public static int eyesVersion() {
        return eyesVersion;
    }

    public static void reset() {
        eyes = List.of();
        eyesVersion++;
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }
}
