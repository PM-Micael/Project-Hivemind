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
    /** Portals set to bring back the units of their own team when they die (see HivePortals#resummonDeath). */
    private final java.util.Set<GlobalPos> resummon = new java.util.HashSet<>();
    private boolean summoning;
    /** Where the units come through: a portal, or null for the Hive Heart itself. */
    @Nullable
    private GlobalPos target;
    /** A unit waiting to come through: its kind, the settings it had and its number (so it comes back as it was, as the same unit). */
    public record Pending(UnitKind kind, @Nullable SlotConfigs.Config config, int slot) {
    }

    private final List<Pending> queue = new ArrayList<>();
    private int ticksToNext;

    public List<GlobalPos> portals() {
        return portals;
    }

    public java.util.Set<GlobalPos> resummon() {
        return resummon;
    }

    /** Add a unit to the summoning that is going on at this portal. */
    public void addToSummoning(Pending unit) {
        queue.add(unit);
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

    /** Ticks between one unit coming through and the next: set from the Heart each time it is used, as the hive's evolutions change it. */
    private int interval = SUMMON_INTERVAL_TICKS;

    public void useInterval(int ticks) {
        this.interval = ticks;
    }

    public int interval() {
        return interval;
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
        ticksToNext = interval;
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
        ListTag resummonList = new ListTag();
        for (GlobalPos portal : resummon) {
            resummonList.add(writePos(portal));
        }
        tag.put("Resummon", resummonList);
        tag.putBoolean("Summoning", summoning);
        if (target != null) {
            tag.put("Target", writePos(target));
        }
        ListTag pending = new ListTag();
        for (Pending unit : queue) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Kind", unit.kind().ordinal());
            entry.putInt("Slot", unit.slot());
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
        resummon.clear();
        for (Tag raw : tag.getList("Resummon", Tag.TAG_COMPOUND)) {
            GlobalPos pos = readPos((CompoundTag) raw);
            if (pos != null) {
                resummon.add(pos);
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
                queue.add(new Pending(UnitKind.values()[ordinal], config, entry.contains("Slot") ? entry.getInt("Slot") : -1));
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
