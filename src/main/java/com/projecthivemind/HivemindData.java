package com.projecthivemind;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;

/**
 * Per-player Hivemind state, saved with the player (so it is per world) and kept on the server.
 * Immutable: use the with* methods and store the result back with {@code player.setData(...)}.
 */
public record HivemindData(HivemindStage stage, Optional<GlobalPos> heart, Optional<UUID> worker, Optional<UUID> soldier) {
    public static final HivemindData UNCHOSEN = new HivemindData(HivemindStage.UNCHOSEN, Optional.empty(), Optional.empty(), Optional.empty());

    public static final Codec<HivemindData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            HivemindStage.CODEC.fieldOf("stage").forGetter(HivemindData::stage),
            GlobalPos.CODEC.optionalFieldOf("heart").forGetter(HivemindData::heart),
            UUIDUtil.CODEC.optionalFieldOf("worker").forGetter(HivemindData::worker),
            UUIDUtil.CODEC.optionalFieldOf("soldier").forGetter(HivemindData::soldier)
    ).apply(instance, HivemindData::new));

    public HivemindData withStage(HivemindStage newStage) {
        return new HivemindData(newStage, heart, worker, soldier);
    }

    public HivemindData withHeart(GlobalPos newHeart) {
        return new HivemindData(stage, Optional.of(newHeart), worker, soldier);
    }

    public Optional<UUID> unit(UnitKind kind) {
        return kind == UnitKind.WORKER ? worker : soldier;
    }

    public boolean hasUnit(UnitKind kind) {
        return unit(kind).isPresent();
    }

    public HivemindData withUnit(UnitKind kind, Optional<UUID> id) {
        return kind == UnitKind.WORKER
                ? new HivemindData(stage, heart, id, soldier)
                : new HivemindData(stage, heart, worker, id);
    }
}
