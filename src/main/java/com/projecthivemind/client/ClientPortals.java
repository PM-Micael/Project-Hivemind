package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.network.SyncPortalsPayload;

/** The hive's portal network as the server last told the client: for the Portals tab. */
public final class ClientPortals {
    private static List<SyncPortalsPayload.Portal> portals = List.of();
    private static int max;
    private static int summonTarget = SyncPortalsPayload.NONE;
    private static List<Integer> queue = List.of();
    private static int secondsToNext;

    private ClientPortals() {
    }

    public static void update(SyncPortalsPayload payload) {
        portals = payload.portals();
        max = payload.max();
        summonTarget = payload.summonTarget();
        queue = payload.queue();
        secondsToNext = payload.secondsToNext();
    }

    public static List<SyncPortalsPayload.Portal> portals() {
        return portals;
    }

    public static int max() {
        return max;
    }

    public static boolean summoning() {
        return summonTarget != SyncPortalsPayload.NONE;
    }

    /** -1 for the Hive Heart, otherwise the index of the portal. */
    public static int summonTarget() {
        return summonTarget;
    }

    public static List<Integer> queue() {
        return queue;
    }

    public static int secondsToNext() {
        return secondsToNext;
    }

    public static void reset() {
        update(new SyncPortalsPayload(List.of(), 0, SyncPortalsPayload.NONE, List.of(), 0));
    }
}
