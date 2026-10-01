package com.projecthivemind.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;

import net.minecraft.nbt.CompoundTag;

/** A unit that belongs to a Hivemind player. */
public interface HiveUnit {
    String OWNER_TAG = "HiveOwner";

    UnitKind kind();

    @Nullable
    UUID ownerId();

    void setOwnerId(@Nullable UUID ownerId);

    /** The loaded Hive Heart this unit belongs to, or null. */
    @Nullable
    HiveHeart findHeart();

    /** What this unit is currently doing to a block, or null if idle. Units that cannot be commanded always say null. */
    @Nullable
    UnitAction action();

    void setAction(@Nullable UnitAction action);

    default void saveOwner(CompoundTag tag) {
        UUID owner = ownerId();
        if (owner != null) {
            tag.putUUID(OWNER_TAG, owner);
        }
    }

    default void loadOwner(CompoundTag tag) {
        if (tag.hasUUID(OWNER_TAG)) {
            setOwnerId(tag.getUUID(OWNER_TAG));
        }
    }
}
