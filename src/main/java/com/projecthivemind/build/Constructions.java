package com.projecthivemind.build;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.network.SyncConstructionsPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** The constructions of a hive, kept on its Heart and saved with it. See {@link Construction} and HiveConstructions. */
public final class Constructions {
    /** The most constructions a hive can have at once. */
    public static final int MAX = 12;

    private final List<Construction> list = new ArrayList<>();
    /** What the owner's client was last told, so it is only told again when something changed. Not saved. */
    private List<SyncConstructionsPayload.Info> synced = List.of();

    public List<Construction> all() {
        return list;
    }

    @Nullable
    public Construction at(ResourceKey<Level> dimension, BlockPos pos) {
        for (Construction construction : list) {
            if (construction.dimension().equals(dimension) && construction.anchor().equals(pos)) {
                return construction;
            }
        }
        return null;
    }

    /** The construction this worker is on, if any (a worker is on at most one). */
    @Nullable
    public Construction of(UUID worker) {
        for (Construction construction : list) {
            if (construction.hasWorker(worker)) {
                return construction;
            }
        }
        return null;
    }

    public void add(Construction construction) {
        list.add(construction);
    }

    public void remove(Construction construction) {
        list.remove(construction);
    }

    /** Take this worker off whatever construction it is on. */
    public void unassign(UUID worker) {
        for (Construction construction : list) {
            construction.workers().remove(worker);
        }
    }

    public List<SyncConstructionsPayload.Info> synced() {
        return synced;
    }

    public void setSynced(List<SyncConstructionsPayload.Info> synced) {
        this.synced = synced;
    }

    public ListTag save() {
        ListTag tag = new ListTag();
        for (Construction construction : list) {
            tag.add(construction.save());
        }
        return tag;
    }

    public void load(ListTag tag) {
        list.clear();
        for (Tag entry : tag) {
            Construction construction = Construction.load((CompoundTag) entry);
            if (construction != null) {
                list.add(construction);
            }
        }
    }
}
