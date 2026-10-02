package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.ScoutBehavior;
import com.projecthivemind.UnitAction;
import com.projecthivemind.UnitKind;
import com.projecthivemind.client.ClientSelection;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Scout unit. Looks like a husk and, like every unit, is passive: no combat AI, never despawns, no experience, no
 * drops. It can be selected and sent places; what a scout is actually for is still to come.
 */
public class HiveScout extends Husk implements HiveUnit {
    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveScout.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final String HEART_TAG = "HiveHeartId";

    @Nullable
    private UUID heartId;
    @Nullable
    private UnitAction action;
    /** This unit's own settings, edited from the hive menu's page for its kind. */
    /** Whether the scout had something in hand on the last tick: an empty hand after that means the tool broke. */
    private boolean wasHolding;
    private ScoutBehavior behavior = ScoutBehavior.DEFAULT;
    private final SpeedProbe speedProbe = new SpeedProbe("scout");

    public HiveScout(EntityType<? extends HiveScout> type, Level level) {
        super(type, level);
        // What it holds is the hive's, and stays in the hive when the scout dies.
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    /**
     * The scout's movement speed attribute, chosen to be halfway between a player's walking and sprinting speed:
     * about 5.0 blocks per second, against 4.3 walking and 5.6 sprinting. A mob's forward input is its own speed, so
     * its ground speed grows with the square of the attribute: blocks per second is roughly 43.2 times the attribute
     * squared. That is why this is 0.339, and why the vanilla 0.23 of a husk or zombie is only about 2.3 blocks per
     * second.
     */
    public static final double MOVEMENT_SPEED = 0.339D;

    public static AttributeSupplier.Builder createScoutAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0D)
                .add(Attributes.MOVEMENT_SPEED, MOVEMENT_SPEED);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        // Deliberately not calling super: husk goals hunt players, villagers and turtle eggs.
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Above everything else: a unit told to stay inside the hive border does.
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> behavior.stayInside()));
        // The last thing a unit does: when idle and set to, walk about inside the border.
        this.goalSelector.addGoal(5, new WanderInsideGoal(this, () -> behavior.wander()));
        // Running away comes before collecting items, so it can interrupt a trip to an item.
        this.goalSelector.addGoal(1, new ScoutFleeGoal(this));
        this.goalSelector.addGoal(1, new ScoutInteractGoal(this));
        this.goalSelector.addGoal(1, new ScoutUseItemGoal(this));
        this.goalSelector.addGoal(1, new WorkerDigGoal(this));
        this.goalSelector.addGoal(1, new ScoutAttackGoal(this));
        this.goalSelector.addGoal(2, new ScoutCollectGoal(this));
    }

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    @Nullable
    @Override
    public HiveHeart findHeart() {
        return HiveHeart.find(this.level(), heartId);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        speedProbe.tick(this);
        if (action != null && action.kind() == UnitAction.Kind.WALK && this.getNavigation().isDone()) {
            action = null;
        }
        // Picking up items works whether or not the scout is selected: a selected one takes what it walks over, and
        // one that is not selected walks to items and takes them on arrival.
        HiveHeart heart = findHeart();
        if (heart != null) {
            // Wear on the tool in hand is charged to the one in the hive's hand slot, which the hand is only a copy of; and a tool
            // that broke in the hand is gone from the slot. Without this the copy is simply replaced by the unworn original.
            ItemStack held = this.getMainHandItem();
            ItemStack slot = heart.scoutHand().getItem(0);
            if (held.isEmpty() && wasHolding && slot.isDamageableItem()) {
                heart.scoutHand().setItem(0, ItemStack.EMPTY);
            } else if (!held.isEmpty() && ItemStack.isSameItem(held, slot) && held.getDamageValue() > slot.getDamageValue()) {
                slot.setDamageValue(held.getDamageValue());
                heart.scoutHand().setChanged();
            }
            wasHolding = !held.isEmpty();
        }
        if (heart != null && this.tickCount % 10 == 0) {
            // The scout holds a copy of what is in its hand slot in the hive menu.
            ItemStack wanted = heart.scoutHand().getItem(0);
            if (!ItemStack.matches(this.getMainHandItem(), wanted)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, wanted.copy());
            }
        }
        if (heart != null && this.tickCount % 2 == 0) {
            pickUpNearbyExperience();
        }
        if (heart != null && behavior.collectItems()) {
            pickUpNearbyItems(heart);
        }
    }

    /**
     * Take any dropped items within a player's pickup reach into the hive's inventory, the way a player picks things
     * up: whatever fits goes in, and what does not stays on the ground. Items that have only just been dropped are
     * left alone until their pickup delay ends.
     */
    /**
     * Experience orbs the scout touches go to the hivemind, as they would to a player: onto its experience bar. The
     * hivemind is far from the scout, so the orb is added directly rather than flying to it.
     */
    private void pickUpNearbyExperience() {
        ServerPlayer owner = this.ownerId() == null || this.level().getServer() == null ? null
                : this.level().getServer().getPlayerList().getPlayer(this.ownerId());
        if (owner == null) {
            return;
        }
        for (ExperienceOrb orb : this.level().getEntitiesOfClass(ExperienceOrb.class, this.getBoundingBox().inflate(1.0D, 0.5D, 1.0D), ExperienceOrb::isAlive)) {
            owner.giveExperiencePoints(orb.getValue());
            this.take(orb, 1);
            this.level().playSound(null, this.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL,
                    0.1F, 0.5F * ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.8F));
            orb.discard();
        }
    }

    private void pickUpNearbyItems(HiveHeart heart) {
        for (ItemEntity item : this.level().getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(1.0D, 0.5D, 1.0D),
                candidate -> candidate.isAlive() && !candidate.hasPickUpDelay())) {
            ItemStack stack = item.getItem();
            int before = stack.getCount();
            ItemStack leftover = heart.getStorage().addItem(stack.copy());
            int taken = before - leftover.getCount();
            if (taken > 0) {
                this.take(item, taken);
                this.level().playSound(null, this.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL,
                        0.2F, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 1.4F);
                if (leftover.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(leftover);
                }
            }
        }
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

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
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
        return UnitKind.SCOUT;
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

    public ScoutBehavior behavior() {
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
        this.behavior = ScoutBehavior.from(flags, radii);
    }

    @Nullable
    @Override
    public UnitAction action() {
        return action;
    }

    @Override
    public void setAction(@Nullable UnitAction action) {
        this.action = action;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        saveOwner(tag);
        tag.put("Behavior", behavior.save());
        if (heartId != null) {
            tag.putUUID(HEART_TAG, heartId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadOwner(tag);
        if (tag.contains("Behavior")) {
            behavior = ScoutBehavior.load(tag.getCompound("Behavior"));
        }
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        // A mob's attribute values are saved with it, so a scout saved before the speed was changed would come back
        // with its old one. Always put the current speed back after loading.
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(MOVEMENT_SPEED);
    }
}
