package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveArea;
import com.projecthivemind.SoldierBehavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * What a soldier does on its own, with no orders: it goes after mobs. Two things make a mob a target.
 *
 * <ul>
 * <li>The soldier's settings (see {@link SoldierBehavior}): every mob, or hostile mobs, inside the hive border. A soldier in a team
 * only acts on these while the team's scout is inside the border too.</li>
 * <li>Being in a team: a mob that is hostile to any unit of the team (it has one of them as its target) is a target for every soldier
 * in the team, wherever it is, with or without the settings.</li>
 * </ul>
 *
 * It fights until the target dies, but gives up on a target that stops being one, so a soldier is never led away across the map.
 */
public class SoldierDefaultAttackGoal extends Goal {
    private static final int SCAN_INTERVAL = 10;
    private static final int RECHECK_INTERVAL = 10;
    /** How far around a team member to look for mobs that are after it. */
    private static final double TEAM_SCAN_RADIUS = 24.0D;

    private final HiveSoldier soldier;
    @Nullable
    private Mob target;
    private int nextScanTick;
    private int nextRecheckTick;

    public SoldierDefaultAttackGoal(HiveSoldier soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Idle, and not selected. */
    private boolean mayAct(@Nullable HiveHeart heart) {
        return heart != null && soldier.action() == null && !heart.isUnitSelected(soldier.getId());
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = soldier.findLocalHeart();
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
        HiveHeart heart = soldier.findLocalHeart();
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

    /** The units of the soldier's team, if it is in one: where mobs that are after the team are looked for. */
    private List<LivingEntity> teamMembers(HiveHeart heart) {
        List<LivingEntity> members = new ArrayList<>();
        int team = heart.teams().teamOf(soldier.getUUID());
        if (team >= 0 && soldier.level() instanceof ServerLevel level) {
            for (UUID id : heart.teams().members(team)) {
                if (level.getEntity(id) instanceof LivingEntity member && member.isAlive()) {
                    members.add(member);
                }
            }
        }
        return members;
    }

    /** The nearest mob that is a target for this soldier, or null. */
    @Nullable
    private Mob findTarget(HiveHeart heart) {
        ServerLevel level = (ServerLevel) soldier.level();
        List<Mob> candidates = new ArrayList<>();

        // Whatever is after a unit of the team, near that unit.
        List<LivingEntity> team = teamMembers(heart);
        Set<UUID> teamIds = new java.util.HashSet<>();
        team.forEach(member -> teamIds.add(member.getUUID()));
        for (LivingEntity member : team) {
            candidates.addAll(level.getEntitiesOfClass(Mob.class, member.getBoundingBox().inflate(TEAM_SCAN_RADIUS),
                    mob -> isOutsider(mob) && mob.getTarget() != null && teamIds.contains(mob.getTarget().getUUID())));
        }

        // The settings: mobs inside the border, if they are on for this soldier.
        if (settingsApply(heart)) {
            AABB area = HiveArea.areaBox(level, heart);
            candidates.addAll(level.getEntitiesOfClass(Mob.class, area, mob -> matchesSettings(heart, mob)));
        }
        return candidates.stream().min(Comparator.comparingDouble(soldier::distanceToSqr)).orElse(null);
    }

    private static boolean isOutsider(Mob mob) {
        return mob.isAlive() && !(mob instanceof HiveUnit) && !(mob instanceof HiveHeart);
    }

    /** Whether this mob is still a valid target for this soldier. */
    private boolean matches(HiveHeart heart, Mob mob) {
        if (!isOutsider(mob) || mob == soldier) {
            return false;
        }
        if (settingsApply(heart) && matchesSettings(heart, mob)) {
            return true;
        }
        // Still after a unit of the team?
        Set<UUID> teamIds = new java.util.HashSet<>();
        teamMembers(heart).forEach(member -> teamIds.add(member.getUUID()));
        return mob.getTarget() != null && teamIds.contains(mob.getTarget().getUUID());
    }

    /**
     * Whether the soldier's fighting settings count right now: one is ticked and, for a soldier in a team that has a scout, the
     * scout is inside the hive border.
     */
    private boolean settingsApply(HiveHeart heart) {
        if (!soldier.behavior().any()) {
            return false;
        }
        Mob leader = heart.teamLeader(soldier);
        return leader == null || HiveArea.containsCube(heart, leader.getX(), leader.getY(), leader.getZ());
    }

    private boolean matchesSettings(HiveHeart heart, Mob mob) {
        if (!isOutsider(mob) || mob == soldier || !HiveArea.containsCube(heart, mob.getX(), mob.getY(), mob.getZ())) {
            return false;
        }
        SoldierBehavior behavior = soldier.behavior();
        return behavior.allInHiveArea() || (behavior.hostileInHiveArea() && mob instanceof Enemy);
    }
}
