package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.Level;

/**
 * The iron golem the Heart makes once the hive has consumed an iron block (a juggernaut). It stays close to the Heart, in a 15x15 square
 * round it, fights the hostile mobs that come into that square, and is never hostile to anything of the hive's. It is the Heart's own: when
 * the Heart is destroyed it goes too, and the Heart makes a new one after a while if it dies.
 */
public class HiveGolem extends IronGolem {
    /** How far from the Heart's block it may be, sideways: a 15x15 square. */
    public static final int REACH = 7;
    /** How far past that square a hostile mob can be and still be gone after (so one just outside is not left to hit the Heart). */
    private static final int TARGET_MARGIN = 3;

    @Nullable
    private UUID heartId;

    public HiveGolem(EntityType<? extends IronGolem> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    @Nullable
    public UUID heartIdOrNull() {
        return heartId;
    }

    /** Its Heart is told it died, so that a new golem comes after a while. */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        HiveHeart heart = findHeart();
        if (heart != null) {
            heart.golemDied();
        }
        super.die(source);
    }

    @Nullable
    public HiveHeart findHeart() {
        if (heartId != null && this.level() instanceof ServerLevel level && level.getEntity(heartId) instanceof HiveHeart heart && heart.isAlive()) {
            return heart;
        }
        return null;
    }

    /** The golem's own goals only: the village ones of an iron golem (going home to a village, offering flowers, being angry at players) are not wanted. */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Back to within the square, before anything else but floating.
        this.goalSelector.addGoal(0, new StayNearHeartGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, net.minecraft.world.entity.player.Player.class, 6.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Mob.class, 5, false, false,
                target -> target instanceof Enemy && !HiveAttacks.spares(target) && inReachOfHeart(target)));
    }

    /** Whether a mob is in the golem's square (with the margin) round the Heart. */
    private boolean inReachOfHeart(net.minecraft.world.entity.LivingEntity target) {
        HiveHeart heart = findHeart();
        return heart != null && Math.abs(target.getX() - heart.getX()) <= REACH + TARGET_MARGIN && Math.abs(target.getZ() - heart.getZ()) <= REACH + TARGET_MARGIN;
    }

    /** A hive golem is not a player's: no flower, no anger at players. */
    @Override
    public boolean isPlayerCreated() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (heartId != null) {
            tag.putUUID("HiveHeartId", heartId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        heartId = tag.hasUUID("HiveHeartId") ? tag.getUUID("HiveHeartId") : null;
    }

    /** Walks back to the Heart when it is outside its square (even in the middle of a fight). */
    private static final class StayNearHeartGoal extends Goal {
        private final HiveGolem golem;

        StayNearHeartGoal(HiveGolem golem) {
            this.golem = golem;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        private boolean outside(HiveHeart heart) {
            return Math.abs(golem.getX() - heart.getX()) > REACH || Math.abs(golem.getZ() - heart.getZ()) > REACH;
        }

        @Override
        public boolean canUse() {
            HiveHeart heart = golem.findHeart();
            return heart != null && heart.level() == golem.level() && outside(heart);
        }

        @Override
        public boolean canContinueToUse() {
            HiveHeart heart = golem.findHeart();
            // Back in from the edge a little, so it does not stand on the line.
            return heart != null && (Math.abs(golem.getX() - heart.getX()) > REACH - 2 || Math.abs(golem.getZ() - heart.getZ()) > REACH - 2) && !golem.getNavigation().isDone();
        }

        @Override
        public void start() {
            HiveHeart heart = golem.findHeart();
            if (heart != null) {
                golem.setTarget(null);
                golem.getNavigation().moveTo(heart.getX(), heart.getY(), heart.getZ(), 1.1D);
            }
        }

        @Override
        public void stop() {
            golem.getNavigation().stop();
        }
    }

    /** The hive's units make none of the noises of the mob they are built on: no groaning, no hurt or death sounds. */
    @javax.annotation.Nullable
    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return null;
    }

    @javax.annotation.Nullable
    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        return null;
    }

    @javax.annotation.Nullable
    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return null;
    }
}
