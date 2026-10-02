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

    /**
     * Which version of the hive's gear this unit was made with (see {@link HiveHeart#gearVersionFor}). A unit whose
     * version is behind the hive's is out of date and gets replaced. Units that do not use gear stay at 0.
     */
    default int gearVersion() {
        return 0;
    }

    /**
     * This one unit's behaviour settings, packed as the hive menu edits them: the checkboxes as flags, and the radii (or
     * range) beside them. Each kind of unit has its own set (see SoldierBehavior and the others); a kind with none has 0.
     */
    default int behaviorFlags() {
        return 0;
    }

    /** The radii (or the range) beside the settings, in the order of the options that have one. Four slots, padded with 0. */
    default int[] behaviorRadii() {
        return new int[4];
    }

    default void setBehavior(int flags, int[] radii) {
    }

    /**
     * The job this unit is on, or null: its current activity if that is a job (see {@link UnitAction.Kind#isJob}), or the one
     * that was set aside when the player gave it another order. A paused job comes back when the unit is free and no
     * longer selected, if {@link #resumeJob} allows.
     */
    @Nullable
    default UnitAction job() {
        return null;
    }

    /** Whether a paused job is taken up again when the unit is deselected. The player can switch it off per unit. */
    default boolean resumeJob() {
        return true;
    }

    /** End the job for good: the job, set aside or not, is dropped, and if the unit is on it, it stops. */
    default void cancelJob() {
    }

    default void setResumeJob(boolean resume) {
    }

    default void setGearVersion(int version) {
    }

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

    /**
     * The hive's armor or tool slots changed (bit 1 armor, bit 2 tools): bring what this unit carries up to date at once. By default
     * a unit carries nothing from them.
     */
    default void onGearChanged(HiveHeart heart, int changed) {
    }
}
