package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;
import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * A worker told to interact with a block: walk up to it and right-click it once with whatever it is holding.
 *
 * <p>Blocks only know how to be used by a player, so the click is made by a fake player standing where the unit
 * stands, holding a copy of the unit's item.
 */
public class InteractBlockGoal extends Goal {
    private static final double REACH_SQR = 3.0D * 3.0D;
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    private static final int GIVE_UP_TICKS = 200;

    private final Mob mob;
    private final HiveUnit unit;
    private int repathCooldown;
    private int stuckTicks;

    public InteractBlockGoal(Mob mob) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private BlockPos target() {
        UnitAction action = unit.action();
        return action != null && action.kind() == UnitAction.Kind.INTERACT ? action.pos() : null;
    }

    @Override
    public boolean canUse() {
        return target() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
    }

    @Override
    public void stop() {
        // Only halt the unit if it has nothing else to do; a new walk order has already set its own path.
        if (unit.action() == null) {
            mob.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        BlockPos pos = target();
        if (pos == null) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        if (mob.distanceToSqr(center) > REACH_SQR) {
            if (++stuckTicks > GIVE_UP_TICKS) {
                unit.setAction(null);
            } else if (--repathCooldown <= 0) {
                mob.getNavigation().moveTo(center.x, pos.getY(), center.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(center);
        interact((ServerLevel) mob.level(), pos, center);
        unit.setAction(null);
    }

    /** One right click on the block, from the side the unit is standing on. */
    private void interact(ServerLevel level, BlockPos pos, Vec3 center) {
        FakePlayer fake = FakePlayerFactory.get(level, HiveActions.FAKE_PROFILE);
        fake.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
        fake.setItemInHand(InteractionHand.MAIN_HAND, mob.getMainHandItem().copy());

        Direction face = Direction.getNearest(mob.getX() - center.x, mob.getEyeY() - center.y, mob.getZ() - center.z);
        Vec3 hitPoint = center.add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        BlockHitResult hit = new BlockHitResult(hitPoint, face, pos, false);

        fake.gameMode.useItemOn(fake, level, fake.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        // Using a chest or the like opens a menu for the fake player; do not leave it hanging open.
        fake.closeContainer();
        mob.swing(InteractionHand.MAIN_HAND);
    }
}
