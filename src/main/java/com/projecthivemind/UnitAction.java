package com.projecthivemind;

import net.minecraft.core.BlockPos;

/** What a commanded unit is currently doing to a block. Lives on the unit and is not saved. */
public record UnitAction(Kind kind, BlockPos pos) {
    public enum Kind {
        /** Walking to stand on top of the block. */
        WALK,
        /** Breaking the block (workers only). */
        DIG,
        /** Right-clicking the block once. */
        INTERACT
    }
}
