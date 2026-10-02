package com.projecthivemind.client;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;
import com.projecthivemind.network.SyncUnitsPayload;

/** The player's units as last told by the server, for the unit pages of the hive menu. Plain data only. */
public final class ClientUnits {
    private static List<SyncUnitsPayload.Entry> units = List.of();

    private ClientUnits() {
    }

    public static void update(List<SyncUnitsPayload.Entry> entries) {
        units = List.copyOf(entries);
    }

    /** The entity ids of the units of one kind, in the order the server listed them. */
    public static List<Integer> ofKind(UnitKind kind) {
        List<Integer> ids = new ArrayList<>();
        for (SyncUnitsPayload.Entry entry : units) {
            if (entry.kind() == kind.ordinal()) {
                ids.add(entry.entityId());
            }
        }
        return ids;
    }

    /** What the server last said about this unit, or null. */
    @Nullable
    public static SyncUnitsPayload.Entry entry(int entityId) {
        for (SyncUnitsPayload.Entry entry : units) {
            if (entry.entityId() == entityId) {
                return entry;
            }
        }
        return null;
    }

    public static void reset() {
        units = List.of();
    }
}
