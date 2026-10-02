package com.projecthivemind;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

/**
 * What a commanded unit is currently doing. Lives on the unit and is not saved.
 *
 * <p>Actions on a block have a {@code pos}; an attack has a {@code target} mob and no position.
 */
public record UnitAction(Kind kind, @Nullable BlockPos pos, @Nullable UUID target) {
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
        BUILD
    }

    /** An action on a block. */
    public UnitAction(Kind kind, BlockPos pos) {
        this(kind, pos, null);
    }

    public static UnitAction trade(UUID target) {
        return new UnitAction(Kind.TRADE, null, target);
    }

    public static UnitAction attack(UUID target) {
        return new UnitAction(Kind.ATTACK, null, target);
    }
}
