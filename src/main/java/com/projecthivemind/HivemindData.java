package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;

/**
 * Per-player Hivemind state, saved with the player (so it is per world) and kept on the server.
 * Immutable: use the with* methods and store the result back with {@code player.setData(...)}.
 *
 * <p>The hive's own state (level, storage, health) lives on the Hive Heart entity, not here.
 *
 * @param heart           dimension and position of the Hive Heart
 * @param heartId         entity UUID of the Hive Heart
 * @param units           UUIDs of this player's living units, by kind
 * @param previousMode    game mode before becoming bodyless, restored if the Heart is destroyed
 * @param normalInventory creative players only: temporarily using the normal creative inventory instead of the hive
 */
public record HivemindData(HivemindStage stage, Optional<GlobalPos> heart, Optional<UUID> heartId,
                           Map<UnitKind, List<UUID>> units, Optional<GameType> previousMode, boolean normalInventory,
                           Optional<CompoundTag> savedHive) {
    public static final HivemindData UNCHOSEN =
            new HivemindData(HivemindStage.UNCHOSEN, Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), false, Optional.empty());

    public static final Codec<HivemindData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            HivemindStage.CODEC.fieldOf("stage").forGetter(HivemindData::stage),
            GlobalPos.CODEC.optionalFieldOf("heart").forGetter(HivemindData::heart),
            UUIDUtil.CODEC.optionalFieldOf("heart_id").forGetter(HivemindData::heartId),
            Codec.unboundedMap(UnitKind.CODEC, UUIDUtil.CODEC.listOf()).optionalFieldOf("units", Map.of()).forGetter(HivemindData::units),
            GameType.CODEC.optionalFieldOf("previous_mode").forGetter(HivemindData::previousMode),
            Codec.BOOL.optionalFieldOf("normal_inventory", false).forGetter(HivemindData::normalInventory),
            CompoundTag.CODEC.optionalFieldOf("saved_hive").forGetter(HivemindData::savedHive)
    ).apply(instance, HivemindData::new));

    public HivemindData withStage(HivemindStage newStage) {
        return new HivemindData(newStage, heart, heartId, units, previousMode, normalInventory, savedHive);
    }

    public HivemindData withHeart(GlobalPos newHeart, UUID newHeartId) {
        return new HivemindData(stage, Optional.of(newHeart), Optional.of(newHeartId), units, previousMode, normalInventory, savedHive);
    }

    public HivemindData withPreviousMode(GameType mode) {
        return new HivemindData(stage, heart, heartId, units, Optional.of(mode), normalInventory, savedHive);
    }

    public HivemindData withNormalInventory(boolean normal) {
        return new HivemindData(stage, heart, heartId, units, previousMode, normal, savedHive);
    }

    /** Only players who were in creative before becoming the hivemind may swap to the normal inventory. */
    public boolean canSwapInventory() {
        return stage == HivemindStage.HIVE && previousMode.filter(mode -> mode == GameType.CREATIVE).isPresent();
    }

    public int count(UnitKind kind) {
        return units.getOrDefault(kind, List.of()).size();
    }

    public List<UUID> allUnits() {
        return units.values().stream().flatMap(List::stream).toList();
    }

    public HivemindData withUnit(UnitKind kind, UUID id) {
        Map<UnitKind, List<UUID>> copy = new HashMap<>(units);
        List<UUID> list = new ArrayList<>(copy.getOrDefault(kind, List.of()));
        list.add(id);
        copy.put(kind, List.copyOf(list));
        return new HivemindData(stage, heart, heartId, Map.copyOf(copy), previousMode, normalInventory, savedHive);
    }

    public HivemindData withoutUnit(UnitKind kind, UUID id) {
        Map<UnitKind, List<UUID>> copy = new HashMap<>(units);
        List<UUID> list = new ArrayList<>(copy.getOrDefault(kind, List.of()));
        list.remove(id);
        copy.put(kind, List.copyOf(list));
        return new HivemindData(stage, heart, heartId, Map.copyOf(copy), previousMode, normalInventory, savedHive);
    }

    /** The destroyed hive's whole state (storage, gear, level, quests...), kept for the next Heart. */
    public HivemindData withSavedHive(Optional<CompoundTag> saved) {
        return new HivemindData(stage, heart, heartId, units, previousMode, normalInventory, saved);
    }

    /** The hive was destroyed: back to a larva with no units, remembering the old game mode and the saved hive. */
    public HivemindData collapsed() {
        return new HivemindData(HivemindStage.LARVA, Optional.empty(), Optional.empty(), Map.of(), previousMode, false, savedHive);
    }
}
