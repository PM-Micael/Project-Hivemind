package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

import com.projecthivemind.HiveArea;
import com.projecthivemind.UnitAction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * The "stay inside the hive border" option. A unit with it on, and not selected, always tries to be inside the hive
 * area, and this wins over everything else it might be doing: it has the highest priority, so whatever would take the
 * unit out (a job outside, a walk, a dropped item, a mob to chase) cannot run.
 *
 * <p>Outside the area the unit walks back to the nearest point inside it. Inside, if what it is meant to be doing is
 * outside, it waits where it is rather than walking up to the edge and out again and back, over and over.
 */
public class StayInsideGoal extends Goal {
    private static final double SPEED = 1.1D;
    private static final int REPATH_INTERVAL = 10;

    private final Mob mob;
    private final HiveUnit unit;
    private final BooleanSupplier enabled;
    private int repathCooldown;

    public StayInsideGoal(Mob mob, BooleanSupplier enabled) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.enabled = enabled;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = unit.findHeart();
        if (heart == null || !enabled.getAsBoolean() || heart.isUnitSelected(mob.getId())) {
            return false;
        }
        if (!HiveArea.containsXZ(heart, mob.getX(), mob.getZ())) {
            return true;
        }
        // Inside, but about to be sent out: hold still instead.
        return targetIsOutside(heart);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        HiveHeart heart = unit.findHeart();
        if (heart == null) {
            return;
        }
        if (HiveArea.containsXZ(heart, mob.getX(), mob.getZ())) {
            mob.getNavigation().stop();
            return;
        }
        if (--repathCooldown <= 0) {
            Vec3 inside = HiveArea.nearestInside(heart, mob.getX(), mob.getZ());
            mob.getNavigation().moveTo(inside.x, inside.y, inside.z, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    /** True if the order or job the unit is on has it going somewhere outside the area. */
    private boolean targetIsOutside(HiveHeart heart) {
        UnitAction action = unit.action();
        if (action == null) {
            return false;
        }
        if (action.pos() != null) {
            return !HiveArea.containsXZ(heart, action.pos().getX() + 0.5D, action.pos().getZ() + 0.5D);
        }
        if (action.target() != null && mob.level() instanceof ServerLevel level) {
            Entity target = level.getEntity(action.target());
            return target != null && !HiveArea.containsXZ(heart, target.getX(), target.getZ());
        }
        return false;
    }
}
