package com.projecthivemind.entity;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.UnitKind;

import net.minecraft.nbt.CompoundTag;

/** A unit that belongs to a Hivemind player. */
public interface HiveUnit {
    String OWNER_TAG = "HiveOwner";

    UnitKind kind();

    @Nullable
    UUID ownerId();

    void setOwnerId(@Nullable UUID ownerId);

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
