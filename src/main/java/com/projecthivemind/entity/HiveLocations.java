package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * The places the hive knows about, kept on the Heart and shown on the Locations tab: a kind of place and where it is. Only the
 * Overworld has any for now. Nothing has to be loaded for a location to be remembered, and a location stays until the player removes it.
 */
public final class HiveLocations {
    /** The most places the hive keeps, which is also what fits in the sync packet. */
    public static final int MAX = 32;

    /** What a location is. Saved by name, so new kinds can be added at the end. */
    public enum Kind {
        STRONGHOLD
    }

    /** One place: its kind, and its spot (the height is not meant: a traveller works that out when it gets there). */
    public record Location(Kind kind, BlockPos pos) {
    }

    private final List<Location> locations = new ArrayList<>();

    public List<Location> list() {
        return locations;
    }

    /** True if the hive already has a location of this kind at this spot (within a chunk, since the height and the exact point vary). */
    public boolean has(Kind kind, BlockPos pos) {
        for (Location location : locations) {
            if (location.kind() == kind && Math.abs(location.pos().getX() - pos.getX()) < 16 && Math.abs(location.pos().getZ() - pos.getZ()) < 16) {
                return true;
            }
        }
        return false;
    }

    /** Add a location; false if the hive is full. */
    public boolean add(Kind kind, BlockPos pos) {
        if (locations.size() >= MAX) {
            return false;
        }
        locations.add(new Location(kind, pos));
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Location location : locations) {
            CompoundTag saved = new CompoundTag();
            saved.putString("Kind", location.kind().name());
            saved.put("Pos", NbtUtils.writeBlockPos(location.pos()));
            list.add(saved);
        }
        tag.put("Locations", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        locations.clear();
        for (Tag entry : tag.getList("Locations", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) entry;
            Kind kind;
            try {
                kind = Kind.valueOf(saved.getString("Kind"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            NbtUtils.readBlockPos(saved, "Pos").ifPresent(pos -> locations.add(new Location(kind, pos)));
        }
    }
}
