package com.projecthivemind.entity;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * lives, and nobody moves up when another dies. When a unit dies its settings are kept under its number, and the unit that takes its place is
 * made with the number and the settings of the one it replaces. Saved with the Heart.
 * has, and the unit that replaces the lost one is made with the settings of the number it takes. Saved with the Heart.
 */
public final class SlotConfigs {
    /** One unit's settings: its behaviour options as the menu sends them, and for a worker the two items it was given. */
    public record Config(int flags, int[] radii, String fill, String compost) {
    }

    private final Map<UnitKind, Map<Integer, Config>> byKind = new EnumMap<>(UnitKind.class);
    /** The number (from 0) of each living unit among its kind. */
    private final Map<UUID, Integer> numbers = new HashMap<>();

    /** The unit's number, or -1 if it has none yet. */
    public int numberOf(UUID unit) {
        return numbers.getOrDefault(unit, -1);
    }

    public void setNumber(UUID unit, int number) {
        numbers.put(unit, number);
    }

    public void forget(UUID unit) {
        numbers.remove(unit);
    }

    /** Forget the numbers of units that are not among these any more. */
    public void keepOnly(java.util.Collection<UUID> living) {
        numbers.keySet().retainAll(living);
    }

    /** The lowest number that none of these units (other than {@code except}) has. */
    public int lowestFree(List<UUID> kindUnits, UUID except) {
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (UUID other : kindUnits) {
            if (!other.equals(except) && numbers.containsKey(other)) {
                taken.add(numbers.get(other));
            }
        }
        int number = 0;
        while (taken.contains(number)) {
            number++;
        }
        return number;
    }

    /** True if one of these units (other than {@code except}) has this number. */
    public boolean isTaken(List<UUID> kindUnits, UUID except, int number) {
        for (UUID other : kindUnits) {
            if (!other.equals(except) && numbers.getOrDefault(other, -1) == number) {
                return true;
            }
        }
        return false;
    }

    public ListTag saveNumbers() {
        ListTag list = new ListTag();
        numbers.forEach((id, number) -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putInt("Number", number);
            list.add(tag);
        });
        return list;
    }

    public void loadNumbers(ListTag list) {
        numbers.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (tag.hasUUID("Id")) {
                numbers.put(tag.getUUID("Id"), tag.getInt("Number"));
            }
        }
    }

    public void put(UnitKind kind, int index, Config config) {
        byKind.computeIfAbsent(kind, ignored -> new HashMap<>()).put(index, config);
    }

    @Nullable
    public Config get(UnitKind kind, int index) {
        Map<Integer, Config> slots = byKind.get(kind);
        return slots == null ? null : slots.get(index);
    }

    public boolean has(UnitKind kind, int index) {
        return get(kind, index) != null;
    }

    public ListTag save() {
        ListTag list = new ListTag();
        byKind.forEach((kind, slots) -> {
            ListTag saved = new ListTag();
            slots.forEach((index, config) -> {
                CompoundTag tag = new CompoundTag();
                tag.putInt("Index", index);
                tag.putInt("Flags", config.flags());
                tag.putIntArray("Radii", config.radii());
                tag.putString("Fill", config.fill());
                tag.putString("Compost", config.compost());
                saved.add(tag);
            });
            CompoundTag kindTag = new CompoundTag();
            kindTag.putString("Kind", kind.name());
            kindTag.put("Slots", saved);
            list.add(kindTag);
        });
        return list;
    }

    public void load(ListTag list) {
        byKind.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag kindTag = list.getCompound(i);
            UnitKind kind;
            try {
                kind = UnitKind.valueOf(kindTag.getString("Kind"));
            } catch (IllegalArgumentException exception) {
                continue;
            }
            ListTag slots = kindTag.getList("Slots", Tag.TAG_COMPOUND);
            for (int j = 0; j < slots.size(); j++) {
                CompoundTag tag = slots.getCompound(j);
                put(kind, tag.getInt("Index"), new Config(tag.getInt("Flags"), tag.getIntArray("Radii"), tag.getString("Fill"), tag.getString("Compost")));
            }
        }
    }
}
