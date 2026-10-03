package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * The hive's portal network, kept on the Heart: the portals that are standing, and the summoning that is going on (at most one at a
 * time): which portal it is at, the units still to come through, in the order they come, and how long until the next one.
 */
public final class PortalNetwork {
    /** The order units come through in: scouts first, then soldiers, then workers. */
    public static final UnitKind[] SUMMON_ORDER = {UnitKind.SCOUT, UnitKind.SOLDIER, UnitKind.WORKER};
    /** Ticks between one unit coming through and the next: 10 seconds. */
    public static final int SUMMON_INTERVAL_TICKS = 200;

    private final List<GlobalPos> portals = new ArrayList<>();
    private boolean summoning;
    /** Where the units come through: a portal, or null for the Hive Heart itself. */
    @Nullable
    private GlobalPos target;
    /** A unit waiting to come through: its kind, and the settings it had (so it comes back as it was). */
    public record Pending(UnitKind kind, @Nullable SlotConfigs.Config config) {
    }

    private final List<Pending> queue = new ArrayList<>();
    private int ticksToNext;

    public List<GlobalPos> portals() {
        return portals;
    }

    public boolean summoning() {
        return summoning;
    }

    @Nullable
    public GlobalPos target() {
        return target;
    }

    public List<Pending> queue() {
        return queue;
    }

    public int ticksToNext() {
        return ticksToNext;
    }

    public void setTicksToNext(int ticks) {
        this.ticksToNext = ticks;
    }

    /** Start a summoning: these kinds, in the order they come through, at this portal (null for the Heart). */
    public void startSummoning(@Nullable GlobalPos where, List<Pending> units) {
        queue.clear();
        for (UnitKind kind : SUMMON_ORDER) {
            for (Pending wanted : units) {
                if (wanted.kind() == kind) {
                    queue.add(wanted);
                }
            }
        }
        target = where;
        summoning = !queue.isEmpty();
        ticksToNext = SUMMON_INTERVAL_TICKS;
    }

    public void stopSummoning() {
        summoning = false;
        target = null;
        queue.clear();
    }

    /** How many units of this kind are waiting to come through: the hive counts them as its own when it makes the units it is owed. */
    public int queued(UnitKind kind) {
        return (int) queue.stream().filter(wanted -> wanted.kind() == kind).count();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (GlobalPos portal : portals) {
            list.add(writePos(portal));
        }
        tag.put("Portals", list);
        tag.putBoolean("Summoning", summoning);
        if (target != null) {
            tag.put("Target", writePos(target));
        }
        ListTag pending = new ListTag();
        for (Pending unit : queue) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Kind", unit.kind().ordinal());
            if (unit.config() != null) {
                entry.putInt("Flags", unit.config().flags());
                entry.putIntArray("Radii", unit.config().radii());
                entry.putString("Fill", unit.config().fill());
                entry.putString("Compost", unit.config().compost());
            }
            pending.add(entry);
        }
        tag.put("Pending", pending);
        tag.putInt("Next", ticksToNext);
        return tag;
    }

    public void load(CompoundTag tag) {
        portals.clear();
        for (Tag raw : tag.getList("Portals", Tag.TAG_COMPOUND)) {
            GlobalPos pos = readPos((CompoundTag) raw);
            if (pos != null) {
                portals.add(pos);
            }
        }
        summoning = tag.getBoolean("Summoning");
        target = tag.contains("Target") ? readPos(tag.getCompound("Target")) : null;
        queue.clear();
        for (Tag raw : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            int ordinal = entry.getInt("Kind");
            if (ordinal >= 0 && ordinal < UnitKind.values().length) {
                SlotConfigs.Config config = entry.contains("Flags")
                        ? new SlotConfigs.Config(entry.getInt("Flags"), entry.getIntArray("Radii"), entry.getString("Fill"), entry.getString("Compost")) : null;
                queue.add(new Pending(UnitKind.values()[ordinal], config));
            }
        }
        ticksToNext = tag.getInt("Next");
        if (queue.isEmpty()) {
            summoning = false;
        }
    }

    private static CompoundTag writePos(GlobalPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", pos.dimension().location().toString());
        tag.put("Pos", NbtUtils.writeBlockPos(pos.pos()));
        return tag;
    }

    @Nullable
    private static GlobalPos readPos(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        BlockPos pos = NbtUtils.readBlockPos(tag, "Pos").orElse(null);
        if (dimension == null || pos == null) {
            return null;
        }
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension), pos);
    }

}
