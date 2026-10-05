package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * A feeder told to till a block flies up to hover above it and then hoes it, as a player standing there would. If it cannot get there
 * it gives up after a while, and if the hoe does nothing the player is told why.
 */
public class FeederTillGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    private static final int GIVE_UP_TICKS = 300;
    /** Every this many ticks of not getting there it is carried the last of the way (3 seconds). */
    private static final int CARRY_TICKS = 60;
    /** How high above the block the feeder hovers, and how far sideways of its middle it may be and still work, squared. */
    private static final double HOVER_HEIGHT = 1.4D;
    private static final double REACH_SQR = 1.3D * 1.3D;
    /** A short moment hovering over the block before it is hoed. */
    private static final int WORK_DELAY = 8;

    private final HiveFeeder feeder;
    private int repathCooldown;
    private int stuckTicks;
    private int hovered;

    public FeederTillGoal(HiveFeeder feeder) {
        this.feeder = feeder;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return feeder.tillTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return feeder.tillTarget() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
        hovered = 0;
    }

    @Override
    public void stop() {
        feeder.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos target = feeder.tillTarget();
        if (target == null || !(feeder.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
        Vec3 hover = new Vec3(center.x, target.getY() + HOVER_HEIGHT, center.z);
        double dx = feeder.getX() - center.x;
        double dz = feeder.getZ() - center.z;
        double above = feeder.getY() - (target.getY() + 1.0D);
        boolean inReach = dx * dx + dz * dz <= REACH_SQR && above >= -0.3D && above <= 2.5D;
        if (!inReach) {
            hovered = 0;
            if (++stuckTicks > GIVE_UP_TICKS) {
                feeder.clearTill();
            } else if (stuckTicks % CARRY_TICKS == 0) {
                feeder.getNavigation().stop();
                feeder.moveTo(hover.x, hover.y, hover.z, feeder.getYRot(), feeder.getXRot());
            } else if (--repathCooldown <= 0) {
                feeder.getNavigation().moveTo(hover.x, hover.y, hover.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        stuckTicks = 0;
        feeder.getNavigation().stop();
        feeder.getLookControl().setLookAt(center);
        if (++hovered < WORK_DELAY) {
            return;
        }
        feeder.clearTill();
        Component problem = HiveActions.tillBlock(level, target);
        if (problem != null) {
            tell(level, problem);
        }
    }

    private void tell(ServerLevel level, Component message) {
        @Nullable
        ServerPlayer owner = feeder.ownerId() == null || level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(feeder.ownerId());
        if (owner != null) {
            owner.displayClientMessage(message, true);
        }
    }
}
