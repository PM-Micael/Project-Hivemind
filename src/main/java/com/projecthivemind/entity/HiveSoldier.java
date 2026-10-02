package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveFood;
import com.projecthivemind.SoldierBehavior;
import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;
import com.projecthivemind.client.ClientSelection;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Soldier unit. Looks like a zombie but is a passive unit that only does what its owner commands:
 * no sunburn, no drowned conversion, no reinforcements, no targeting on its own, never despawns.
 */
public class HiveSoldier extends Zombie implements HiveUnit {
    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveSoldier.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final String HEART_TAG = "HiveHeartId";

    @Nullable
    private UUID heartId;
    private static final String GEAR_VERSION_TAG = "GearVersion";

    @Nullable
    private UnitAction action;
    /** The job this unit is on or has set aside (see HiveUnit#job). Not saved. */
    @Nullable
    private UnitAction job;
    private boolean resumeJob = true;
    /** This unit's own settings, edited from the hive menu's page for its kind. */
    private SoldierBehavior behavior = SoldierBehavior.DEFAULT;
    private int gearVersion;
    private final GearMirror gearMirror = new GearMirror();

    public HiveSoldier(EntityType<? extends HiveSoldier> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createHiveAttributes() {
        return Zombie.createAttributes().add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: zombie goals hunt players, villagers and turtle eggs.
        // Soldiers walk where told and fight what they are told to attack. Digging and interacting is worker-only.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Above everything else: a unit told to stay inside the hive border does.
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> behavior.stayInside()));
        this.goalSelector.addGoal(1, new SoldierAttackGoal(this));
        // With no orders and not selected, the hive's behaviour settings decide what a soldier goes after.
        this.goalSelector.addGoal(2, new SoldierDefaultAttackGoal(this));
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    // ---- fighting, shared by ordered attacks and the hive's default behaviour ----

    private static final double CHASE_SPEED = 1.15D;
    private static final int REPATH_INTERVAL = 6;
    /** Never swing faster than this many ticks apart, whatever the weapon says. */
    private static final int MIN_ATTACK_INTERVAL = 5;

    private int repathCooldown;
    private int attackCooldown;

    /** Start a fresh fight: no leftover cooldowns from the last one. */
    public void resetCombat() {
        repathCooldown = 0;
        attackCooldown = 0;
    }

    /** Run down a target and hit it whenever it is in reach. Call every tick while fighting. */
    public void pursue(LivingEntity target) {
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (--repathCooldown <= 0) {
            this.getNavigation().moveTo(target, CHASE_SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
        if (--attackCooldown <= 0 && this.isWithinMeleeAttackRange(target) && this.hasLineOfSight(target)) {
            strike(target);
        }
    }

    private void strike(LivingEntity target) {
        ItemStack weapon = this.getMainHandItem();
        this.swing(InteractionHand.MAIN_HAND);
        boolean hit = this.doHurtTarget(target);
        HiveHeart hive = findHeart();
        if (hive != null) {
            hive.food().exhaust(HiveFood.ATTACK);
        }
        if (hit && !weapon.isEmpty()) {
            // Mobs do not wear their weapons in vanilla, but a player does: a hit costs the weapon durability. The gear
            // mirror then charges the same to the original in the hive.
            weapon.getItem().postHurtEnemy(weapon, target, this);
        }
        // Swing as often as the weapon allows: attacks per second is its attack speed.
        attackCooldown = Math.max(MIN_ATTACK_INTERVAL, (int) Math.round(20.0D / HiveEquipment.attackSpeed(weapon)));
    }

    @Nullable
    @Override
    public HiveHeart findHeart() {
        return HiveHeart.find(this.level(), heartId);
    }
    /** Take a set-aside job up again once the unit has nothing to do and the player has let go of it. */
    private void resumeJobIfFree(@Nullable HiveHeart heart) {
        if (action == null && job != null && resumeJob && heart != null && !heart.isUnitSelected(this.getId())) {
            setAction(job);
        }
    }


    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            HiveHeart heart = findHeart();
            if (heart != null) {
                // The gear on this soldier is a copy of pieces in the hive: wear on it is charged to the original.
                gearMirror.tick(this, heart);
            }
            speedProbe.tick(this);
            resumeJobIfFree(heart);
            if (action != null && action.kind() == UnitAction.Kind.WALK && this.getNavigation().isDone()) {
                action = null;
            }
        }
    }

    // TEMPORARY: logs real walking speed, to compare against the scout. Remove once speeds are settled.
    private final SpeedProbe speedProbe = new SpeedProbe("soldier");

    /**
     * Mobs never wear their armor down in the game, only players do. Soldiers do: their armor is a copy of the hive's, and
     * what the copy loses is charged to the original in the Heart (see GearMirror), whichever unit took the hit.
     */
    @Override
    protected void hurtArmor(DamageSource source, float damage) {
        this.doHurtEquipment(source, damage, HiveEquipment.ARMOR_SLOTS);
    }

    /** Soldiers never drop their gear: it is a copy, and the original stays in the hive. */
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    /** Units give no experience when they die. */
    @Override
    protected void dropExperience(@Nullable Entity killer) {
    }

    // ---- outline: white, yellow when selected, visible through walls, for the owner only ----

    @Override
    public boolean isCurrentlyGlowing() {
        return this.level().isClientSide ? ClientSelection.shouldGlow(ownerId()) : super.isCurrentlyGlowing();
    }

    @Override
    public int getTeamColor() {
        return this.level().isClientSide ? ClientSelection.outlineColor(this.getId()) : super.getTeamColor();
    }

    @Override
    public UnitKind kind() {
        return UnitKind.SOLDIER;
    }

    @Nullable
    @Override
    public UUID ownerId() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    @Override
    public void setOwnerId(@Nullable UUID ownerId) {
        this.entityData.set(DATA_OWNER, Optional.ofNullable(ownerId));
    }

    public SoldierBehavior behavior() {
        return behavior;
    }

    @Override
    public int behaviorFlags() {
        return behavior.flags();
    }

    @Override
    public int[] behaviorRadii() {
        int[] radii = new int[4];
        System.arraycopy(behavior.radii(), 0, radii, 0, behavior.radii().length);
        return radii;
    }

    @Override
    public void setBehavior(int flags, int[] radii) {
        this.behavior = SoldierBehavior.from(flags, radii);
    }

    @Nullable
    @Override
    public UnitAction action() {
        return action;
    }

    @Override
    public void setAction(@Nullable UnitAction next) {
        // A job ending, or being cancelled, ends the job. Giving the unit another order does not: it is set aside, and
        // comes back when the unit is released. Giving it a new job replaces the old one.
        if (next == null && action != null && action.equals(job)) {
            job = null;
        }
        if (next != null && next.kind().isJob()) {
            job = next;
        }
        this.action = next;
    }

    @Nullable
    @Override
    public UnitAction job() {
        return job;
    }

    @Override
    public boolean resumeJob() {
        return resumeJob;
    }

    @Override
    public void cancelJob() {
        UnitAction ended = job;
        job = null;
        if (ended != null && ended.equals(action)) {
            action = null;
            this.getNavigation().stop();
        }
    }

    @Override
    public void setResumeJob(boolean resume) {
        this.resumeJob = resume;
    }

    @Override
    public int gearVersion() {
        return gearVersion;
    }

    @Override
    public void setGearVersion(int version) {
        this.gearVersion = version;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        tag.put("Behavior", behavior.save());
        // The job, and whether the unit was on it, so that it carries on after the game has been closed.
        if (job != null) {
            tag.put("Job", job.save());
            tag.putBoolean("JobActive", job.equals(action));
        }
        tag.putBoolean("ResumeJob", resumeJob);
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
        tag.putInt(GEAR_VERSION_TAG, gearVersion);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        job = tag.contains("Job") ? UnitAction.load(tag.getCompound("Job")) : null;
        resumeJob = !tag.contains("ResumeJob") || tag.getBoolean("ResumeJob");
        if (job != null && tag.getBoolean("JobActive")) {
            action = job;
        }
        if (tag.contains("Behavior")) {
            behavior = SoldierBehavior.load(tag.getCompound("Behavior"));
        }
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        gearVersion = tag.getInt(GEAR_VERSION_TAG);
    }
}
