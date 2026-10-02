package com.projecthivemind.entity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;
import com.projecthivemind.SoldierBehavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * What a soldier does on its own: with no orders, and not selected by the player, it goes after mobs according to
 * the hive's behaviour settings. See {@link SoldierBehavior} for what the options mean.
 *
 * <p>Every option except the global threat one only triggers for a mob inside the soldier's own range, and the
 * hive-area options additionally need the mob inside the hive area. It fights until the target dies, but gives up on
 * a target that stops matching an enabled option, for example one that gets out of range, so a soldier is never led
 * away across the map.
 */
public class SoldierDefaultAttackGoal extends Goal {
    private static final int SCAN_INTERVAL = 10;
    private static final int RECHECK_INTERVAL = 10;

    private final HiveSoldier soldier;
    @Nullable
    private Mob target;
    private int nextScanTick;
    private int nextRecheckTick;

    public SoldierDefaultAttackGoal(HiveSoldier soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Idle, not selected, and the hive has something enabled to look for. */
    private boolean mayAct(@Nullable HiveHeart heart) {
        return heart != null && soldier.action() == null && !heart.isUnitSelected(soldier.getId())
                && soldier.behavior().any();
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = soldier.findHeart();
        if (!mayAct(heart) || soldier.tickCount < nextScanTick) {
            return false;
        }
        // Not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        nextScanTick = soldier.tickCount + SCAN_INTERVAL;
        target = findTarget(heart);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = soldier.findHeart();
        if (!mayAct(heart) || target == null || !target.isAlive()) {
            return false;
        }
        if (soldier.tickCount >= nextRecheckTick) {
            nextRecheckTick = soldier.tickCount + RECHECK_INTERVAL;
            return matches(heart, target);
        }
        return true;
    }

    @Override
    public void start() {
        soldier.resetCombat();
        soldier.setAggressive(true);
        nextRecheckTick = soldier.tickCount + RECHECK_INTERVAL;
    }

    @Override
    public void stop() {
        soldier.setAggressive(false);
        target = null;
        if (soldier.action() == null) {
            soldier.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        if (target != null) {
            soldier.pursue(target);
        }
    }

    // ---- choosing a target ----

    /** The nearest mob that matches an enabled option. Anything that threatens the hive comes first. */
    @Nullable
    private Mob findTarget(HiveHeart heart) {
        ServerLevel level = (ServerLevel) soldier.level();
        SoldierBehavior behavior = soldier.behavior();

        if (behavior.threats()) {
            Mob nearestThreat = null;
            for (UUID id : heart.threats()) {
                if (level.getEntity(id) instanceof Mob threat && matches(heart, threat)
                        && (nearestThreat == null || soldier.distanceToSqr(threat) < soldier.distanceToSqr(nearestThreat))) {
                    nearestThreat = threat;
                }
            }
            if (nearestThreat != null) {
                return nearestThreat;
            }
        }

        // Every other trigger needs the mob inside the soldier's own range, so that is all there is to search.
        AABB search = soldier.getBoundingBox().inflate(behavior.maxRadius());
        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, search, mob -> matches(heart, mob));
        return candidates.stream().min(Comparator.comparingDouble(soldier::distanceToSqr)).orElse(null);
    }

    /** Whether this mob is a valid target for this soldier under the hive's current settings. */
    private static double square(int radius) {
        return (double) radius * radius;
    }

    private boolean matches(HiveHeart heart, Mob mob) {
        // Never hive members, and never itself.
        if (!mob.isAlive() || mob == soldier || mob instanceof HiveUnit || mob instanceof HiveHeart) {
            return false;
        }
        SoldierBehavior behavior = soldier.behavior();

        // The one global trigger: a mob that is hostile to the hive is a target wherever it is.
        boolean outsideBorder = behavior.stayInside() && !HiveArea.containsXZ(heart, mob.getX(), mob.getZ());
        if (outsideBorder) {
            // A soldier set to stay inside the hive area does not go after anything outside it, threats included.
            return false;
        }
        if (behavior.threats() && heart.isThreat(mob.getUUID())) {
            return true;
        }

        // Every other option only counts inside its own radius around this soldier.
        double distance = soldier.distanceToSqr(mob);
        boolean hostile = mob instanceof Enemy;

        // The hive-area options also need the mob to be inside the hive area itself.
        boolean inHiveArea = HiveArea.areaBox((ServerLevel) soldier.level(), heart).contains(mob.position());
        if (inHiveArea) {
            if (behavior.allInHiveArea() && distance <= square(behavior.allInHiveRadius())) {
                return true;
            }
            if (behavior.hostileInHiveArea() && hostile && distance <= square(behavior.hostileInHiveRadius())) {
                return true;
            }
        }
        return (behavior.allInUnitArea() && distance <= square(behavior.allInUnitRadius()))
                || (behavior.hostileInUnitArea() && hostile && distance <= square(behavior.hostileInUnitRadius()));
    }
}
