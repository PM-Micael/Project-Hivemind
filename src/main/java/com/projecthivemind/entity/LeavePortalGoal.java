package com.projecthivemind.entity;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;

/**
 * A unit that has come through a portal stands inside the one on this side. While it stays there the portal's cooldown keeps being
 * renewed and it can never use a portal again, so a unit with nothing to do steps out of it. A walk order into the portal then
 * works, in both directions.
 */
public class LeavePortalGoal extends Goal {
    private final Mob mob;
    private final HiveUnit unit;

    public LeavePortalGoal(Mob mob) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return unit.action() == null && mob.getNavigation().isDone() && mob.level().getBlockState(mob.blockPosition()).is(Blocks.NETHER_PORTAL);
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone() && mob.level().getBlockState(mob.blockPosition()).is(Blocks.NETHER_PORTAL);
    }

    @Override
    public void start() {
        BlockPos origin = mob.blockPosition();
        for (int radius = 2; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos spot = origin.offset(dx, 0, dz);
                    if (!mob.level().getBlockState(spot).is(Blocks.NETHER_PORTAL) && !mob.level().getBlockState(spot.below()).isAir()
                            && mob.level().getBlockState(spot).getCollisionShape(mob.level(), spot).isEmpty()
                            && mob.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 1.0D)) {
                        return;
                    }
                }
            }
        }
    }
}
