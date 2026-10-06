package com.projecthivemind.entity;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.ScoutBehavior;
import com.projecthivemind.HiveEquipment;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Scout unit. Built like a player (a player's hitbox, poses and sounds, and under control a player's movement). Like every unit it is passive: no combat AI, never despawns, no experience, no
 * drops. It can be selected and sent places; what a scout is actually for is still to come.
 */
public class HiveScout extends PathfinderMob implements HiveUnit {
    /** Synced so the owner's client knows which units are theirs and should be outlined. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(HiveScout.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final String HEART_TAG = "HiveHeartId";

    @Nullable
    private UUID heartId;
    @Nullable
    private UnitAction action;
    /** This unit's own settings, edited from the hive menu's page for its kind. */
    /** The scout's gear mirror, used while it holds a tool from the hive for an order: wear on the copy is charged to the original. */
    private final GearMirror gearMirror = new GearMirror(EquipmentSlot.MAINHAND);
    /** Keeps the armor on the scout and the originals in the hive in step: wear on a copy is charged to the original. */
    private final GearMirror armorMirror = new GearMirror(HiveEquipment.ARMOR_SLOTS);
    /** True while the hand holds a tool from the hive's pool for a dig or attack order, instead of the hand slot's item. */
    private boolean toolOverride;

    /**
     * Hold a copy of the hive's tool in this tool slot for the order in hand. The hand slot of the hive menu is left alone; its
     * item comes back to the hand when the order is over. {@code slot} -1 (the hive has nothing suitable) changes nothing.
     */
    public void holdHiveTool(HiveHeart heart, int slot) {
        if (slot < 0) {
            return;
        }
        // Tell the durability mirror the swap is deliberate, or it would read it as the old tool breaking.
        gearMirror.reset();
        this.setItemSlot(EquipmentSlot.MAINHAND, HiveEquipment.linkedCopy(heart.getToolGear(), slot));
        toolOverride = true;
        wasHolding = false;
    }

    /** For a dig order: the hive's best tool for the block, as a worker would pick it. */
    public void holdBestToolFor(HiveHeart heart, net.minecraft.world.level.block.state.BlockState state) {
        holdHiveTool(heart, HiveEquipment.bestToolSlot(heart, this.level().registryAccess(), state));
    }

    /** For an attack order: the hive's weapon with the highest damage per second, as a soldier would carry. */
    public void holdBestWeapon(HiveHeart heart) {
        holdHiveTool(heart, HiveEquipment.bestWeaponSlot(heart));
    }

    /** Whether the scout had something in hand on the last tick: an empty hand after that means the tool broke. */
    private boolean wasHolding;
    /** Which of the hive's hand slots the hand was last made a copy of (-1: none yet), so a change of slot is not mistaken for the item breaking. */
    private int handSlot = -1;

    /**
     * What the player who controls this scout is pressing and facing, as of this tick (see ScoutControl). While there is one the scout does
     * what it says and nothing else: its goals are held off and its movement is the player's.
     */
    public record Drive(float forward, float strafe, boolean jump, boolean sneak, boolean sprint, float yaw, float pitch) {
    }
    /**
     * A player's own movement numbers: the speed a player walks and sprints at, and the 0.3 that sneaking scales the keys by. (A mob's own
     * movement sets its forward input to its speed as well, which squares the speed; here the forward input is the key, as a player's is.)
     */
    private static final float CONTROL_WALK_SPEED = 0.1F;
    private static final float CONTROL_SPRINT_SPEED = 0.13F;
    private static final float CONTROL_SNEAK_FACTOR = 0.3F;

    @Nullable
    private Drive drive;
    /** True while the player controlling this scout is breaking a block or hitting a mob, so the hive's tool stays in its hand. */
    private boolean controlWork;
    /** The hand slot changed from outside the scout's own sync: copy it to the hand on the next tick instead of waiting for the next check. */
    private boolean resyncHand;

    public boolean isControlled() {
        return drive != null;
    }

    /**
     * Start, change or (null) end the player's control. Starting holds off every goal: they all move or look, and a goal cannot be told
     * to wait, only kept from starting.
     */
    public void setDrive(@Nullable Drive drive) {
        boolean was = this.drive != null;
        this.drive = drive;
        if (drive != null && !was) {
            for (net.minecraft.world.entity.ai.goal.Goal.Flag flag : net.minecraft.world.entity.ai.goal.Goal.Flag.values()) {
                this.goalSelector.disableControlFlag(flag);
            }
            this.getNavigation().stop();
            // One block of step assist, not the two a scout steps on its own: a ledge a block high is walked up, a higher one is jumped.
            this.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(PLAYER_STEP_HEIGHT);
        } else if (drive == null && was) {
            this.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(STEP_HEIGHT);
            this.setPose(net.minecraft.world.entity.Pose.STANDING);
            sprintBlocked = false;
            this.stopUsingItem();
            for (net.minecraft.world.entity.ai.goal.Goal.Flag flag : net.minecraft.world.entity.ai.goal.Goal.Flag.values()) {
                this.goalSelector.enableControlFlag(flag);
            }
            this.jumping = false;
            this.xxa = 0.0F;
            this.zza = 0.0F;
            this.controlWork = false;
            this.resyncHand = true;
            super.setSpeed(0.0F);
            this.setSprinting(false);
            applySneak();
        }
    }

    /** True while a tool or weapon taken from the hive is in the hand for the work in hand. */
    public boolean holdsHiveTool() {
        return toolOverride;
    }

    public void setControlWork(boolean working) {
        this.controlWork = working;
    }

    /** The hand slot in the hive changed: show it in the hand at once. */
    public void refreshHand() {
        this.resyncHand = true;
    }

    // While controlled, the movement controls and jump control of the mob (which still tick) must not undo what the player presses.
    @Override
    public void setSpeed(float speed) {
        if (drive == null) {
            super.setSpeed(speed);
        }
    }

    @Override
    public void setZza(float zza) {
        if (drive == null) {
            super.setZza(zza);
        }
    }

    @Override
    public void setXxa(float xxa) {
        if (drive == null) {
            super.setXxa(xxa);
        }
    }

    @Override
    public void setYya(float yya) {
        if (drive == null) {
            super.setYya(yya);
        }
    }

    @Override
    public void setJumping(boolean jumping) {
        if (drive == null) {
            super.setJumping(jumping);
        }
    }

    /** The step height a controlled scout has: one block of step assist, so a ledge a block high is walked up without jumping. */
    private static final double PLAYER_STEP_HEIGHT = 1.0D;
    private boolean sprintBlocked;

    /** A player's crouching and swimming hitboxes and eye heights; the standing one is the entity type's (see ModEntities). */
    private static final net.minecraft.world.entity.EntityDimensions CROUCHING_DIMENSIONS =
            net.minecraft.world.entity.EntityDimensions.scalable(0.6F, 1.5F).withEyeHeight(1.27F);
    private static final net.minecraft.world.entity.EntityDimensions SWIMMING_DIMENSIONS =
            net.minecraft.world.entity.EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F);

    @Override
    public net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        return switch (pose) {
            case CROUCHING -> CROUCHING_DIMENSIONS;
            case SWIMMING, FALL_FLYING, SPIN_ATTACK -> SWIMMING_DIMENSIONS;
            default -> super.getDefaultDimensions(net.minecraft.world.entity.Pose.STANDING);
        };
    }

    private boolean fitsWhen(net.minecraft.world.entity.Pose pose) {
        return this.level().noCollision(this, this.getDimensions(pose).makeBoundingBox(this.position()).deflate(1.0E-7D));
    }

    /**
     * The pose a player would be in (the same choice as the player's own): swimming while swimming, crouching while the sneak key is down,
     * and where there is no room for that pose, crouching or at the lowest swimming, so it stands up again only where there is room.
     */
    private void updateScoutPose() {
        if (!fitsWhen(net.minecraft.world.entity.Pose.SWIMMING)) {
            return;
        }
        net.minecraft.world.entity.Pose wanted = this.isSwimming() ? net.minecraft.world.entity.Pose.SWIMMING
                : this.isShiftKeyDown() ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING;
        net.minecraft.world.entity.Pose pose = this.isPassenger() || fitsWhen(wanted) ? wanted
                : fitsWhen(net.minecraft.world.entity.Pose.CROUCHING) ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.SWIMMING;
        this.setPose(pose);
    }

    /** A swimming player steers up and down with where they look; the vertical swimming is the player's own code. */
    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (drive != null && this.isSwimming() && !this.isPassenger()) {
            double look = this.getLookAngle().y;
            double rate = look < -0.2D ? 0.085D : 0.06D;
            if (look <= 0.0D || this.jumping || !this.level().getBlockState(net.minecraft.core.BlockPos.containing(this.getX(), this.getY() + 1.0D - 0.1D, this.getZ())).getFluidState().isEmpty()) {
                net.minecraft.world.phys.Vec3 motion = this.getDeltaMovement();
                this.setDeltaMovement(motion.add(0.0D, (look - motion.y) * rate, 0.0D));
            }
        }
        super.travel(travelVector);
    }

    /** In the air a player steers a little, a little more when sprinting. */
    @Override
    protected float getFlyingSpeed() {
        return drive != null ? (this.isSprinting() ? 0.025999999F : 0.02F) : super.getFlyingSpeed();
    }

    /** Sneaking on an edge keeps the scout from walking off it, exactly as it does a player (the same method as a player's). */
    @Override
    protected net.minecraft.world.phys.Vec3 maybeBackOffFromEdge(net.minecraft.world.phys.Vec3 vec, net.minecraft.world.entity.MoverType mover) {
        float step = this.maxUpStep();
        if (drive == null || !this.isShiftKeyDown() || vec.y > 0.0D || mover != net.minecraft.world.entity.MoverType.SELF
                || !(this.onGround() || this.fallDistance < step && !canFallAtLeast(0.0D, 0.0D, step - this.fallDistance))) {
            return vec;
        }
        double x = vec.x;
        double z = vec.z;
        double stepX = Math.signum(x) * 0.05D;
        double stepZ = Math.signum(z) * 0.05D;
        while (x != 0.0D && canFallAtLeast(x, 0.0D, step)) {
            if (Math.abs(x) <= 0.05D) {
                x = 0.0D;
                break;
            }
            x -= stepX;
        }
        while (z != 0.0D && canFallAtLeast(0.0D, z, step)) {
            if (Math.abs(z) <= 0.05D) {
                z = 0.0D;
                break;
            }
            z -= stepZ;
        }
        while (x != 0.0D && z != 0.0D && canFallAtLeast(x, z, step)) {
            x = Math.abs(x) <= 0.05D ? 0.0D : x - stepX;
            z = Math.abs(z) <= 0.05D ? 0.0D : z - stepZ;
        }
        return new net.minecraft.world.phys.Vec3(x, vec.y, z);
    }

    private boolean canFallAtLeast(double x, double z, float distance) {
        net.minecraft.world.phys.AABB box = this.getBoundingBox();
        return this.level().noCollision(this, new net.minecraft.world.phys.AABB(box.minX + x, box.minY - distance - 1.0E-5F, box.minZ + z,
                box.maxX + x, box.minY, box.maxZ + z));
    }

    /** Make the scout do what the player presses: face where they face, and walk, sneak, sprint and jump as they do. */
    private void applyDrive(Drive d) {
        this.setYRot(d.yaw());
        this.yBodyRot = d.yaw();
        this.setYHeadRot(d.yaw());
        this.setXRot(d.pitch());
        this.setShiftKeyDown(d.sneak());
        // Running into a wall ends a sprint, as for a player; it takes a fresh press of forward to start another.
        if (this.horizontalCollision) {
            sprintBlocked = true;
        }
        if (!d.sprint() || d.forward() <= 0.0F) {
            sprintBlocked = false;
        }
        // Drawing a bow (or eating) slows a player to a fifth and ends a sprint.
        boolean busyHands = this.isUsingItem() && !this.isPassenger();
        boolean sprinting = !busyHands && ((d.sprint() && d.forward() > 0.0F && !d.sneak() && !sprintBlocked) || (this.isInWater() && !d.sneak()));
        this.setSprinting(sprinting);
        // A mob's setSpeed also sets its forward input, so the speed goes in first and the keys after it.
        super.setSpeed(sprinting ? CONTROL_SPRINT_SPEED : CONTROL_WALK_SPEED);
        float keys = (d.sneak() ? CONTROL_SNEAK_FACTOR : 1.0F) * (busyHands ? 0.2F : 1.0F);
        this.zza = d.forward() * keys;
        this.xxa = d.strafe() * keys;
        this.jumping = d.jump();
    }
    private ScoutBehavior behavior = ScoutBehavior.DEFAULT;
    private final SpeedProbe speedProbe = new SpeedProbe("scout");

    public HiveScout(EntityType<? extends HiveScout> type, Level level) {
        super(type, level);
        // What it holds is the hive's, and stays in the hive when the scout dies.
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        for (EquipmentSlot slot : HiveEquipment.ARMOR_SLOTS) {
            this.setDropChance(slot, 0.0F);
        }
    }

    /**
     * The scout's movement speed attribute, chosen to be halfway between a player's walking and sprinting speed:
     * about 5.0 blocks per second, against 4.3 walking and 5.6 sprinting. A mob's forward input is its own speed, so
     * its ground speed grows with the square of the attribute: blocks per second is roughly 43.2 times the attribute
     * squared. That is why this is 0.339, and why the vanilla 0.23 of a husk or zombie is only about 2.3 blocks per
     * second.
     */
    public static final double MOVEMENT_SPEED = 0.339D;
    /** A walking zombie's movement speed attribute, which is the scout's speed while sneaking. */
    public static final double SNEAK_SPEED = 0.23D;
    /** How high the scout steps up in one go, in blocks: a little over two, so a two-block rise is a step. (A mob's own is 0.6.) */
    public static final double STEP_HEIGHT = 2.1D;

    public static AttributeSupplier.Builder createScoutAttributes() {
        return Mob.createMobAttributes()
                // A player's health and bare-handed damage, a zombie's pathfinding range; no armor of its own, as a player has none.
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 35.0D)
                .add(Attributes.MOVEMENT_SPEED, MOVEMENT_SPEED)
                // Steps up 2 blocks without jumping, and the pathfinder plans routes that way: more mobility than the usual one-block hop.
                .add(Attributes.STEP_HEIGHT, STEP_HEIGHT);
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
        this.goalSelector.addGoal(1, new LeavePortalGoal(this));

        // Above everything else: a unit told to stay inside the hive border does.
        this.goalSelector.addGoal(0, new StayInsideGoal(this, () -> behavior.stayInside() && !inTeam()));
        // The last thing a unit does: when idle and set to, walk about inside the border.
        this.goalSelector.addGoal(5, new WanderInsideGoal(this, () -> behavior.wander()));
        // After that, when idle inside the border and not wandering: stand in the Heart.
        this.goalSelector.addGoal(6, new GatherAtHeartGoal(this, () -> !behavior.wander()));
        // Running away comes before collecting items, so it can interrupt a trip to an item.
        this.goalSelector.addGoal(1, new ScoutFleeGoal(this));
        this.goalSelector.addGoal(1, new ScoutInteractGoal(this));
        this.goalSelector.addGoal(1, new ScoutUseItemGoal(this));
        this.goalSelector.addGoal(1, new ScoutPortalGoal(this));
        this.goalSelector.addGoal(1, new WorkerDigGoal(this));
        this.goalSelector.addGoal(1, new ScoutAttackGoal(this));
        this.goalSelector.addGoal(2, new ScoutCollectGoal(this));
    }

    /** True while this unit is in one of the hive's teams: a team member never has to stay inside the border. */
    private boolean inTeam() {
        HiveHeart heart = findHeart();
        return heart != null && heart.teams().isMember(this.getUUID());
    }

    /** The hive's tools changed: a tool held for an order is put away, and the order picks again from what the hive has now. */
    @Override
    public void onGearChanged(HiveHeart heart, int changed) {
        if ((changed & 2) != 0 && toolOverride) {
            toolOverride = false;
            gearMirror.reset();
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            wasHolding = false;
        }
    }

    /**
     * Every scout wears a copy of the armor in the hive's scout armor slots, the way soldiers wear the soldiers'. A piece that is not what the slot holds
     * (changed, taken out, or broken and replaced) is put right at once, and what the copy loses is charged to the original in the Heart.
     */
    private void wearScoutArmor(HiveHeart heart) {
        armorMirror.tick(this, heart);
        for (int i = 0; i < HiveEquipment.ARMOR_SLOTS.length; i++) {
            EquipmentSlot slot = HiveEquipment.ARMOR_SLOTS[i];
            ItemStack stored = heart.getScoutArmor().getItem(i);
            ItemStack worn = this.getItemBySlot(slot);
            if (stored.isEmpty()) {
                if (!worn.isEmpty()) {
                    armorMirror.reset();
                    this.setItemSlot(slot, ItemStack.EMPTY);
                }
                continue;
            }
            java.util.UUID link = HiveEquipment.link(stored);
            if (worn.isEmpty() || link == null || !link.equals(HiveEquipment.link(worn))) {
                armorMirror.reset();
                this.setItemSlot(slot, HiveEquipment.linkedCopy(heart.getScoutArmor(), i));
            }
        }
    }

    /** Mobs never wear their armor down in the game, only players do. A scout does: its armor is a copy, and the wear is charged to the original. */
    @Override
    protected void hurtArmor(DamageSource source, float damage) {
        this.doHurtEquipment(source, damage, HiveEquipment.ARMOR_SLOTS);
    }

    /** Carries a walk order a long way, past what one path can reach. */
    private final WalkProgress walkProgress = new WalkProgress();

    public void setHeartId(@Nullable UUID heartId) {
        this.heartId = heartId;
    }

    @Nullable
    @Override
    public HiveHeart findHeart() {
        return HiveHeart.find(this.level(), heartId);
    }

    /** The pathfinder does not know the Heart's body is solid, so a unit would press against it and never get past: units walk through it. */
    @Override
    public boolean canCollideWith(net.minecraft.world.entity.Entity other) {
        return !(other instanceof HiveHeart) && super.canCollideWith(other);
    }

    /** Short, so a unit can use a portal again soon after coming through one (an order into it works at once). */
    @Override
    public int getDimensionChangingDelay() {
        return 40;
    }

    @Override
    public void tick() {
        Drive driven = this.level().isClientSide ? null : drive;
        if (driven != null) {
            applyDrive(driven);
        }
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        updateScoutPose();
        if (driven != null) {
            // Nothing the mob does in its tick (looking at things, say) turns it away from where the player faces.
            this.setYRot(driven.yaw());
            this.yBodyRot = driven.yaw();
            this.setYHeadRot(driven.yaw());
            this.setXRot(driven.pitch());
        }
        speedProbe.tick(this);
        HiveHeart armorHeart = findHeart();
        if (armorHeart != null) {
            wearScoutArmor(armorHeart);
        }
        if (driven == null) {
            applySneak();
            // In water the scout swims the way a swimming player does: sprinting in water is what makes a player swim, and it is
            // what cuts the water's drag (0.9 instead of 0.8), which is where the extra speed comes from. Not while sneaking.
            boolean swimming = this.isInWater() && !behavior.sneak();
            if (this.isSprinting() != swimming) {
                this.setSprinting(swimming);
            }
        }
        if (action != null && action.kind() == UnitAction.Kind.WALK && this.getNavigation().isDone() && !walkProgress.keepWalking(this, action)) {
            action = null;
        }
        // Picking up items works whether or not the scout is selected: a selected one takes what it walks over, and
        // one that is not selected walks to items and takes them on arrival.
        HiveHeart heart = findHeart();
        boolean working = controlWork || (action != null && (action.kind() == UnitAction.Kind.DIG || action.kind() == UnitAction.Kind.ATTACK));
        if (toolOverride && (!working || heart == null)) {
            // The last swing of the order may have worn the tool this very tick: charge it to the original before letting go.
            if (heart != null) {
                gearMirror.tick(this, heart);
            }
            // The order is over: the hand slot's item comes back (the sync below puts it in the hand).
            toolOverride = false;
            gearMirror.reset();
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            wasHolding = false;
            resyncHand = true;
        }
        if (toolOverride) {
            // A tool from the hive pool is in hand: its wear is charged to the original tool in the hive.
            gearMirror.tick(this, heart);
        } else if (heart != null) {
            // Wear on the tool in hand is charged to the one in the hive's hand slot, which the hand is only a copy of; and a tool
            // that broke in the hand is gone from the slot. Without this the copy is simply replaced by the unworn original.
            ItemStack held = this.getMainHandItem();
            ItemStack slot = heart.scoutHeld();
            // Only while the hand is the copy of the slot that is selected now: right after the hotbar slot is changed the hand still holds the
            // old slot's item (or nothing) until the sync below, and comparing it with the new slot would break or wear the wrong item.
            boolean linked = handSlot == heart.scoutSelected();
            if (!linked) {
                wasHolding = false;
            } else if (held.isEmpty() && wasHolding && slot.isDamageableItem()) {
                heart.setScoutHeld(ItemStack.EMPTY);
            } else if (!held.isEmpty() && ItemStack.isSameItem(held, slot) && held.getDamageValue() > slot.getDamageValue()) {
                slot.setDamageValue(held.getDamageValue());
                heart.scoutHand().setChanged();
            }
            // A crossbow's loaded arrow lives on the stack in the hand: the hive's slot keeps it, or the next sync would unload it.
            if (linked && held.getItem() instanceof net.minecraft.world.item.CrossbowItem && ItemStack.isSameItem(held, slot)
                    && !java.util.Objects.equals(held.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES),
                            slot.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES))) {
                slot.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES, held.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES));
                heart.scoutHand().setChanged();
            }
            wasHolding = linked && !held.isEmpty();
        }
        if (heart != null && !toolOverride && (this.tickCount % 10 == 0 || resyncHand)) {
            // The scout holds a copy of what is in its hand slot in the hive menu.
            resyncHand = false;
            ItemStack wanted = heart.scoutHeld();
            if (!ItemStack.matches(this.getMainHandItem(), wanted)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, wanted.copy());
            }
            if (handSlot != heart.scoutSelected()) {
                handSlot = heart.scoutSelected();
                wasHolding = !this.getMainHandItem().isEmpty();
            }
        }
        if (heart != null && this.tickCount % 2 == 0) {
            pickUpNearbyExperience();
        }
        // A scout picks up what it touches, as a player does (it does not walk to items: it does nothing on its own).
        if (heart != null) {
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
            com.projecthivemind.HivemindManager.giveHiveExperience(owner, this.findHeart(), orb.getValue());
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
    protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
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

    /** Scouts have no settings: they pick up what they touch and do nothing on their own, so there is nothing to change. */
    @Override
    public void setBehavior(int flags, int[] radii) {
    }

    /** The Sneak setting: crouching makes the scout as hard to notice as a sneaking player, and it slows to a walking zombie's speed. */
    private void applySneak() {
        if (drive != null) {
            return; // the player's own sneak key decides while they control the scout
        }
        this.setShiftKeyDown(behavior.sneak());
        var speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
        double wanted = behavior.sneak() ? SNEAK_SPEED : MOVEMENT_SPEED;
        if (speed != null && speed.getBaseValue() != wanted) {
            speed.setBaseValue(wanted);
        }
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
        if (tag.hasUUID(HEART_TAG)) {
            heartId = tag.getUUID(HEART_TAG);
        }
        // A mob's attribute values are saved with it, so a scout saved before the speed was changed would come back
        // with its old one. Always put the current speed back after loading.
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(MOVEMENT_SPEED);
        this.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(STEP_HEIGHT);
    }
}
