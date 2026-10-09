package com.projecthivemind.client;

import java.util.List;

import com.projecthivemind.network.SyncLocationsPayload;

/** The hive's locations as the server last told the client: for the Locations tab. */
public final class ClientLocations {
    private static List<SyncLocationsPayload.Location> locations = List.of();

    private ClientLocations() {
    }

    public static void update(List<SyncLocationsPayload.Location> list) {
        locations = List.copyOf(list);
    }

    public static List<SyncLocationsPayload.Location> all() {
        return locations;
    }

    public static void reset() {
        locations = List.of();
    }
}
