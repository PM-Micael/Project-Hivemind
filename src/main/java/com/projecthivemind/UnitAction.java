package com.projecthivemind;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * What a commanded unit is currently doing. Lives on the unit and is not saved.
 *
 * <p>Actions on a block have a {@code pos}, and for using an item on a block also the {@code face} of it that was
 * clicked; an attack has a {@code target} mob and no position.
 */
public record UnitAction(Kind kind, @Nullable BlockPos pos, @Nullable UUID target, @Nullable Direction face) {
    public enum Kind {
        /** Walking to stand on top of a block. */
        WALK,
        /** Breaking a block (workers only). */
        DIG,
        /** Right-clicking a block once (workers), or opening its inventory (scouts). */
        INTERACT,
        /** Fighting a mob until it dies or the order is cancelled (soldiers only). */
        ATTACK,
        /** Walking up to a villager to trade with it through a hive screen (scouts only). */
        TRADE,
        /** Building a tower with other workers: {@code pos} is the block it stands on (workers only). */
        BUILD,
        /** Using the item in the scout's hand on a face of a block: placing it, reading it, throwing it (scouts only). */
        USE_ITEM;

        /**
         * Whether this is a job: something a unit keeps at until it is done, like mining a block, fighting a mob or building.
         * Anything else (walking somewhere, one use of a block or item) is an order that only pauses a job.
         */
        public boolean isJob() {
            return this == DIG || this == ATTACK || this == BUILD;
        }
    }

    /** Written for a unit's saved data, so that its job survives the game being closed. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        if (pos != null) {
            tag.put("Pos", NbtUtils.writeBlockPos(pos));
        }
        if (target != null) {
            tag.putUUID("Target", target);
        }
        if (face != null) {
            tag.putString("Face", face.getName());
        }
        return tag;
    }

    /** Read back by {@link #save}; null if it is not something that can be read. */
    @Nullable
    public static UnitAction load(CompoundTag tag) {
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        BlockPos pos = NbtUtils.readBlockPos(tag, "Pos").orElse(null);
        UUID target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
        Direction face = tag.contains("Face") ? Direction.byName(tag.getString("Face")) : null;
        return new UnitAction(kind, pos, target, face);
    }

    public UnitAction(Kind kind, @Nullable BlockPos pos, @Nullable UUID target) {
        this(kind, pos, target, null);
    }

    /** An action on a block. */
    public UnitAction(Kind kind, BlockPos pos) {
        this(kind, pos, null, null);
    }

    public static UnitAction trade(UUID target) {
        return new UnitAction(Kind.TRADE, null, target, null);
    }

    public static UnitAction attack(UUID target) {
        return new UnitAction(Kind.ATTACK, null, target, null);
    }

    /** Use the scout's item on this face of this block. */
    public static UnitAction useItem(BlockPos pos, Direction face) {
        return new UnitAction(Kind.USE_ITEM, pos, null, face);
    }
}
