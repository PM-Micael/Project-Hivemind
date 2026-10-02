package com.projecthivemind.entity;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The settings of the hive's units, kept by place in the list of their kind (Soldier 1, Soldier 2...) and not by the individual unit. When a
 * unit dies the ones after it move up a number, and the settings stay with the numbers: each unit takes on the settings of the number it now
 * has, and the unit that replaces the lost one is made with the settings of the number it takes. Saved with the Heart.
 */
public final class SlotConfigs {
    /** One unit's settings: its behaviour options as the menu sends them, and for a worker the two items it was given. */
    public record Config(int flags, int[] radii, String fill, String compost) {
    }

    private final Map<UnitKind, Map<Integer, Config>> byKind = new EnumMap<>(UnitKind.class);

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
