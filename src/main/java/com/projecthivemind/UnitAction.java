package com.projecthivemind;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
        USE_ITEM
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
