package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.FeederBehavior;
import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;
import com.projecthivemind.client.ClientSelection;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;

/**
 * Feeder unit. Looks and flies like a bee. It is a passive unit, like the collector: it cannot be commanded and spends its time
 * around the hive border. What it does there is set from its page of the hive menu (see {@link FeederBehavior}): channelling on
 * crops and saplings to make them grow, and feeding the composters.
 */
public class HiveFeeder extends Bee implements HiveUnit {
    private static final String HEART_TAG = "HiveHeartId";

    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveFeeder.class, EntityDataSerializers.OPTIONAL_UUID);

    @Nullable
    private UUID heartId;
    private FeederBehavior behavior = FeederBehavior.DEFAULT;
    /** What it puts in composters, when set to use them: an item that can be composted. Chosen in the hive menu; saved. */
    @Nullable
    private net.minecraft.world.item.Item compostItem;

    public HiveFeeder(EntityType<? extends HiveFeeder> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
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

    public static AttributeSupplier.Builder createFeederAttributes() {
        return Bee.createAttributes();
    }

    @Override
    protected void registerGoals() {
        // The bee's own goals are made (the bee's code expects them to exist) and then all taken away: they pollinate, sting and look
        // for a hive. A feeder's goals are its own.
        super.registerGoals();
        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);

        this.goalSelector.addGoal(1, new LeavePortalGoal(this));
        // Above everything else: a feeder always stays inside the hive border.
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> true));
        // Above its work: a block it was told to till comes first.
        this.goalSelector.addGoal(1, new FeederTillGoal(this));
        this.goalSelector.addGoal(2, new FeederChannelGoal(this));
        this.goalSelector.addGoal(2, new FeederCompostGoal(this));
        // After those: once the hive has a bee nest, gather pollen for honey.
        this.goalSelector.addGoal(3, new FeederPollenGoal(this));
        // The last thing it does, when idle: hover in the Heart.
        this.goalSelector.addGoal(6, new GatherAtHeartGoal(this, () -> true));
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    /** Units give no experience when they die. */
    @Override
    protected void dropExperience(@Nullable Entity killer) {
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    /** The block this feeder was told to till and is flying to, or null. Not saved. */
    @Nullable
    private BlockPos tillTarget;

    @Nullable
    public BlockPos tillTarget() {
        return tillTarget;
    }

    public void orderTill(BlockPos pos) {
        this.tillTarget = pos.immutable();
    }

    public void clearTill() {
        this.tillTarget = null;
    }

    /** Feeders take no orders: they never have an action. */
    @Nullable
    @Override
    public UnitAction action() {
        return null;
    }

    @Override
    public void setAction(@Nullable UnitAction action) {
    }

    /** The pathfinder does not know the Heart's body is solid, so a unit would press against it and never get past: units walk through it. */
    @Override
    public boolean canCollideWith(Entity other) {
        return !(other instanceof HiveHeart) && super.canCollideWith(other);
    }

    /** Short, so a unit can use a portal again soon after coming through one. */
    @Override
    public int getDimensionChangingDelay() {
        return 40;
    }

    @Nullable
    @Override
    public HiveHeart findHeart() {
        if (heartId != null && this.level() instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(heartId);
            if (entity instanceof HiveHeart heart && heart.isAlive()) {
                return heart;
            }
        }
        return null;
    }

    @Override
    public UnitKind kind() {
        return UnitKind.FEEDER;
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

    // ---- settings ----

    public FeederBehavior behavior() {
        return behavior;
    }

    @Override
    public int behaviorFlags() {
        return behavior.flags();
    }

    @Override
    public int[] behaviorRadii() {
        return behavior.radii();
    }

    @Override
    public void setBehavior(int flags, int[] radii) {
        this.behavior = FeederBehavior.from(flags, radii);
    }

    @Nullable
    public net.minecraft.world.item.Item compostItem() {
        return compostItem;
    }

    /** Choose the item for the composters: one that cannot be composted clears the choice instead. */
    public void setCompostItem(@Nullable net.minecraft.world.item.Item item) {
        this.compostItem = item != null && HiveWorker.compostable(item) ? item : null;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // A feeder flies about close to the ground and the crops, and ends up in blocks: it does not suffocate.
        return source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // A bee that is hurt turns angry and stings; a feeder never does.
        boolean hurt = super.hurt(source, amount);
        this.setRemainingPersistentAngerTime(0);
        this.setPersistentAngerTarget(null);
        this.setTarget(null);
        return hurt;
    }

    // ---- saving ----

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
        tag.putInt("Flags", behavior.flags());
        if (compostItem != null) {
            tag.putString("CompostItem", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(compostItem).toString());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        behavior = FeederBehavior.from(tag.getInt("Flags"), new int[4]);
        net.minecraft.resources.ResourceLocation compostId = tag.contains("CompostItem") ? net.minecraft.resources.ResourceLocation.tryParse(tag.getString("CompostItem")) : null;
        setCompostItem(compostId == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(compostId).orElse(null));
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

    /** A feeder is silent altogether: that also stops the bee's buzzing, which the game plays by the bee being quiet or not. */
    @Override
    public boolean isSilent() {
        return true;
    }
}
