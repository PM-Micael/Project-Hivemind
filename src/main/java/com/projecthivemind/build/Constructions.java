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

    // ---- ground that flattening leaves alone ----

    /**
     * The sites of constructions that were finished (or whose block was broken): their footprints stay protected from flattening, so that a
     * shaft that is done is not filled in afterwards. Per dimension, as column keys. Saved.
     */
    private final java.util.Map<ResourceKey<Level>, java.util.Set<Long>> kept = new java.util.HashMap<>();

    /** Keep this construction's footprint protected from now on, whatever becomes of the construction. */
    public void keep(Construction construction) {
        kept.computeIfAbsent(construction.dimension(), dimension -> new java.util.HashSet<>()).addAll(construction.footprint());
    }

    /** Every ground column in this dimension that flattening must leave alone: those of constructions under way, and of the sites kept. */
    public java.util.Set<Long> protectedColumns(ResourceKey<Level> dimension) {
        java.util.Set<Long> columns = new java.util.HashSet<>(kept.getOrDefault(dimension, java.util.Set.of()));
        for (Construction construction : list) {
            if (construction.dimension().equals(dimension)) {
                columns.addAll(construction.footprint());
            }
        }
        return columns;
    }

    public ListTag saveKept() {
        ListTag tag = new ListTag();
        for (java.util.Map.Entry<ResourceKey<Level>, java.util.Set<Long>> entry : kept.entrySet()) {
            CompoundTag site = new CompoundTag();
            site.putString("Dimension", entry.getKey().location().toString());
            site.putLongArray("Columns", entry.getValue().stream().mapToLong(Long::longValue).toArray());
            tag.add(site);
        }
        return tag;
    }

    public void loadKept(ListTag tag) {
        kept.clear();
        for (Tag entry : tag) {
            CompoundTag site = (CompoundTag) entry;
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(site.getString("Dimension"));
            if (id != null) {
                java.util.Set<Long> columns = kept.computeIfAbsent(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id), dimension -> new java.util.HashSet<>());
                for (long column : site.getLongArray("Columns")) {
                    columns.add(column);
                }
            }
        }
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
