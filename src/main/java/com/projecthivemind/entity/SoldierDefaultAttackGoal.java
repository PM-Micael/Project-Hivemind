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
    /** How often a soldier in a team that is out with its scout looks for mobs to fight: twice a quarter second. */
    private static final int TEAM_SCAN_INTERVAL = 5;
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

    /** Idle, and not selected. The Heart is the hive's wherever the soldier is, so a team in the Nether still defends itself. */
    private boolean mayAct(@Nullable HiveHeart heart) {
        return heart != null && soldier.action() == null && !heart.isUnitSelected(soldier.getId());
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = soldier.findHeart();
        if (!mayAct(heart) || soldier.tickCount < nextScanTick) {
            return false;
        }
        // Not tickCount % N: goals are only evaluated on some ticks, so a modulo check can silently never line up.
        // Quicker for a soldier on guard round a scout, where a hostile mob appearing in the ring has to be met at once.
        nextScanTick = soldier.tickCount + (heart.teamLeader(soldier) != null ? TEAM_SCAN_INTERVAL : SCAN_INTERVAL);
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
            return matches(heart, target) && !overcrowded(heart);
        }
        return true;
    }

    @Override
    public void start() {
        soldier.resetCombat();
        soldier.setAggressive(true);
        HiveHeart heart = soldier.findHeart();
        if (heart != null && target != null) {
            heart.assignFight(soldier.getUUID(), target.getUUID(), soldier.level().getGameTime());
        }
        nextRecheckTick = soldier.tickCount + RECHECK_INTERVAL;
    }

    @Override
    public void stop() {
        soldier.setAggressive(false);
        HiveHeart fightHeart = soldier.findHeart();
        if (fightHeart != null) {
            fightHeart.releaseFight(soldier.getUUID());
        }
        target = null;
        if (soldier.action() == null) {
            soldier.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        if (target != null) {
            soldier.pursue(target);
            // Counted on this mob for as long as it keeps at it: renewed every second.
            if (soldier.tickCount % 20 == 0) {
                HiveHeart heart = soldier.findHeart();
                if (heart != null) {
                    heart.assignFight(soldier.getUUID(), target.getUUID(), soldier.level().getGameTime());
                }
            }
        }
    }

    // ---- choosing a target ----

    /** The units of the soldier's team, if it is in one: where mobs that are after the team are looked for. */
    private List<LivingEntity> teamMembers(HiveHeart heart) {
        List<LivingEntity> members = new ArrayList<>();
        int team = heart.teams().teamOf(soldier.getUUID());
        if (team >= 0 && !heart.teamScoutInside(soldier) && soldier.level() instanceof ServerLevel level) {
            for (UUID id : heart.teams().members(team)) {
                if (level.getEntity(id) instanceof LivingEntity member && member.isAlive()) {
                    members.add(member);
                }
            }
        }
        return members;
    }

    /** Every mob that is a target for this soldier right now (each once). */
    private List<Mob> candidates(HiveHeart heart) {
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

        // A soldier following a scout outside the border goes after every hostile mob inside the team's attack area (the yellow ring) at once,
        // not only those that have already turned on the team: the ring is what it guards.
        Mob scout = heart.teamLeader(soldier);
        if (scout != null) {
            double radius = heart.teams().attackRadius(heart.teams().teamOf(soldier.getUUID()));
            candidates.addAll(level.getEntitiesOfClass(Mob.class, scout.getBoundingBox().inflate(radius, MAX_HEIGHT_DIFFERENCE + soldier.getBbHeight(), radius),
                    mob -> isOutsider(mob) && mob instanceof Enemy));
        }

        // The settings: mobs inside the border, if they are on for this soldier.
        if (settingsApply(heart)) {
            AABB area = HiveArea.areaBox(level, heart);
            candidates.addAll(level.getEntitiesOfClass(Mob.class, area, mob -> matchesSettings(heart, mob)));
        }
        return candidates.stream().distinct().filter(this::withinHeight).filter(mob -> withinAttackArea(heart, mob)).toList();
    }

    /**
     * The mob this soldier goes after: the one the fewest other soldiers are on, and of those the nearest. So soldiers spread out over the mobs
     * there are (two mobs and four soldiers: two soldiers on each) instead of all running at the nearest one.
     */
    @Nullable
    private Mob findTarget(HiveHeart heart) {
        long now = soldier.level().getGameTime();
        return candidates(heart).stream()
                .min(Comparator.<Mob>comparingInt(mob -> heart.fightersOn(mob.getUUID(), soldier.getUUID(), now)).thenComparingDouble(soldier::distanceToSqr))
                .orElse(null);
    }

    /**
     * True if this soldier has more company on its mob than there is on another one: at least two more than the least covered. It then
     * lets go of its mob, so that it picks again and the soldiers even out as mobs come and go.
     */
    private boolean overcrowded(HiveHeart heart) {
        if (target == null) {
            return false;
        }
        long now = soldier.level().getGameTime();
        int mine = heart.fightersOn(target.getUUID(), soldier.getUUID(), now);
        if (mine < 2) {
            return false;
        }
        for (Mob other : candidates(heart)) {
            if (other != target && heart.fightersOn(other.getUUID(), soldier.getUUID(), now) + 2 <= mine) {
                return true;
            }
        }
        return false;
    }

    /** A soldier only goes after what is within this many blocks of its own height: not at things far above or below it. */
    private static final double MAX_HEIGHT_DIFFERENCE = 3.0D;

    /**
     * A soldier in a team with a scout only goes after mobs within the team's attack area (the yellow ring) round the scout, and drops one that leaves it.
     * A soldier with no scout to measure from has no such limit.
     */
    private boolean withinAttackArea(HiveHeart heart, Mob mob) {
        Mob leader = heart.teamLeader(soldier);
        if (leader == null) {
            return true;
        }
        double radius = heart.teams().attackRadius(heart.teams().teamOf(soldier.getUUID()));
        double dx = mob.getX() - leader.getX();
        double dz = mob.getZ() - leader.getZ();
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean withinHeight(Mob mob) {
        return Math.abs(mob.getY() - soldier.getY()) <= MAX_HEIGHT_DIFFERENCE;
    }

    private static boolean isOutsider(Mob mob) {
        return mob.isAlive() && !(mob instanceof HiveUnit) && !(mob instanceof HiveHeart);
    }

    /** Whether this mob is still a valid target for this soldier. */
    private boolean matches(HiveHeart heart, Mob mob) {
        if (!isOutsider(mob) || mob == soldier || !withinHeight(mob) || !withinAttackArea(heart, mob)) {
            return false;
        }
        if (settingsApply(heart) && matchesSettings(heart, mob)) {
            return true;
        }
        // A hostile mob inside the attack area counts for a soldier on guard round a scout (see candidates).
        if (mob instanceof Enemy && heart.teamLeader(soldier) != null) {
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
        // The hive area is where the Heart is: in another dimension (the Nether, with a team) the settings have no area to apply to, but the team still defends itself.
        if (heart.level() != soldier.level()) {
            return false;
        }
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
