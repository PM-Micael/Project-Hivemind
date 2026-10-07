package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

/**
 * The hive's hunger. It works like a player's: a food level (20 is full), a saturation that is used up first, and an
 * exhaustion that builds up with everything the hive does and turns into lost saturation, then lost food. A hive that
 * does nothing gets no hungrier, exactly as a player standing still does not; what costs food is what the hive
 * does, and all of that is charged to this one hunger: everything its units do (sprinting, swimming, jumping,
 * fighting, taking hits, breaking blocks) costs what it would cost a player, and the Heart's own healing costs
 * what a player's does.
 *
 * <p>With the food level at 0 the Heart, and every unit, slowly take damage, by the game's own rules: it can kill on
 * Hard difficulty (so in Hardcore), takes a player down to half a heart on Normal and to five hearts on Easy.
 *
 * <p>The hive feeds itself from its food slot in the hive menu (see {@link #eatFromSlot}).
 */
public final class HiveFood {
    // What each thing costs, in exhaustion: the numbers a player pays. A player pays nothing to walk.
    public static final float SPRINT_PER_METER = 0.1F;
    public static final float SWIM_PER_METER = 0.01F;
    public static final float WALK_PER_METER = 0.0F;
    public static final float JUMP = 0.05F;
    public static final float SPRINT_JUMP = 0.2F;
    public static final float ATTACK = 0.1F;
    public static final float BREAK_BLOCK = 0.005F;

    /** Faster than this (blocks per second) a unit counts as sprinting: a player's sprint is 5.6, and walk 4.3. */
    private static final double SPRINT_SPEED = 5.3D;
    private static final int EAT_INTERVAL = 10;
    /** The hive eats once it is missing this much food: 2 points, one whole drumstick of the bar. */
    private static final int FOOD_MISSING_TO_EAT = 2;

    private static final String FOOD_TAG = "HiveFoodLevel";
    private static final String SATURATION_TAG = "HiveSaturation";
    private static final String EXHAUSTION_TAG = "HiveExhaustion";
    private static final String TIMER_TAG = "HiveFoodTimer";

    private int foodLevel = 20;
    private float saturation = 5.0F;
    private float exhaustion;
    private int tickTimer;

    /** Where each unit was last tick, to work out how far it moved. Not saved. */
    private record UnitState(double x, double z, boolean onGround) {
    }

    private final Map<UUID, UnitState> unitStates = new HashMap<>();

    public int foodLevel() {
        return foodLevel;
    }

    public float saturation() {
        return saturation;
    }

    /** What summoning a unit costs: this much saturation (spent before food, as everything the hive does is). */
    public static final float SUMMON_SATURATION = 2.0F;

    /** The hive pays for a new unit. */
    public void payForUnit() {
        addExhaustion(SUMMON_SATURATION * 4.0F);
    }

    /** What the units' actions cost as a share of the usual: halved once the hive has done the cake task (set every tick from the Heart). */
    private float costScale = 1.0F;

    /** Charge an action: its cost, scaled by what the hive has evolved. */
    public void exhaust(float amount) {
        addExhaustion(amount * costScale);
    }

    /** Charge exactly this much: for what is not a unit's action (healing, and paying for a new unit). */
    private void addExhaustion(float amount) {
        exhaustion = Math.min(exhaustion + amount, 40.0F);
    }

    /** Take in food: the same sums as a player eating. */
    public void eat(FoodProperties food) {
        foodLevel = Mth.clamp(foodLevel + food.nutrition(), 0, 20);
        saturation = Mth.clamp(saturation + food.saturation(), 0.0F, (float) foodLevel);
    }

    /** One tick, from the Heart. */
    public void tick(HiveHeart heart, ServerLevel level) {
        Difficulty difficulty = level.getDifficulty();
        costScale = com.projecthivemind.EvolveTask.CAKE.doneIn(heart.evolveMask()) ? 0.5F : 1.0F;
        List<LivingEntity> hurtMembers = new ArrayList<>();
        ServerPlayer owner = heart.ownerId() == null || heart.getServer() == null ? null
                : heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner != null) {
            hurtMembers.addAll(chargeUnits(heart, level, owner));
        }

        // Exhaustion turns into lost saturation, and when there is none, into lost food.
        if (exhaustion > 4.0F) {
            exhaustion -= 4.0F;
            if (saturation > 0.0F) {
                saturation = Math.max(saturation - 1.0F, 0.0F);
            } else if (difficulty != Difficulty.PEACEFUL) {
                foodLevel = Math.max(foodLevel - 1, 0);
            }
        }

        boolean regenerates = level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        // The Heart and every unit heal from the one hunger, each costing what a player's healing costs.
        if (heart.getHealth() < heart.getMaxHealth()) {
            hurtMembers.add(heart);
        }
        boolean hurt = !hurtMembers.isEmpty();
        if (regenerates && saturation > 0.0F && hurt && foodLevel >= 20) {
            // Full and saturated: fast healing, which costs what it heals.
            if (++tickTimer >= 10) {
                float used = Math.min(saturation, 6.0F);
                for (LivingEntity member : hurtMembers) {
                    member.heal(used / 6.0F);
                    addExhaustion(used);
                }
                tickTimer = 0;
            }
        } else if (regenerates && foodLevel >= 18 && hurt) {
            if (++tickTimer >= 80) {
                for (LivingEntity member : hurtMembers) {
                    member.heal(1.0F);
                    addExhaustion(6.0F);
                }
                tickTimer = 0;
            }
        } else if (foodLevel <= 0) {
            if (++tickTimer >= 80) {
                starve(heart, level, owner);
                tickTimer = 0;
            }
        } else {
            tickTimer = 0;
        }

        // On Peaceful the game heals a player and refills their food on its own.
        if (difficulty == Difficulty.PEACEFUL && regenerates) {
            if (hurt && heart.tickCount % 20 == 0) {
                hurtMembers.forEach(member -> member.heal(1.0F));
            }
            if (foodLevel < 20 && heart.tickCount % 10 == 0) {
                foodLevel++;
            }
        }

        if (heart.tickCount % EAT_INTERVAL == 0) {
            // In the order the Heart tab says: the food slot first, or the honey.
            if (heart.perfectEating()) {
                eatAtBestMoment(heart);
            } else if (heart.honeyFirst()) {
                drinkHoney(heart);
                eatFromSlot(heart);
            } else {
                eatFromSlot(heart);
                drinkHoney(heart);
            }
        }
    }

    /** The Heart and each unit lose a little health: but only down to the point the difficulty allows. */
    private void starve(HiveHeart heart, ServerLevel level, ServerPlayer owner) {
        starveOne(heart, level);
        if (owner != null) {
            for (UUID id : HivemindManager.get(owner).allUnits()) {
                if (level.getEntity(id) instanceof Mob unit && unit.isAlive() && unit instanceof HiveUnit) {
                    starveOne(unit, level);
                }
            }
        }
    }

    private static void starveOne(LivingEntity entity, ServerLevel level) {
        Difficulty difficulty = level.getDifficulty();
        if (entity.getHealth() > 10.0F || difficulty == Difficulty.HARD || (entity.getHealth() > 1.0F && difficulty == Difficulty.NORMAL)) {
            entity.hurt(level.damageSources().starve(), 1.0F);
        }
    }

    /**
     * Charge the hive for what its units are doing this tick: swimming and sprinting by the distance, jumps, as a
     * player's would cost. Returns the units that are hurt, which heal from the hive's hunger.
     */
    private List<LivingEntity> chargeUnits(HiveHeart heart, ServerLevel level, ServerPlayer owner) {
        List<LivingEntity> hurt = new ArrayList<>();
        for (UUID id : HivemindManager.get(owner).allUnits()) {
            if (!(level.getEntity(id) instanceof Mob unit) || !unit.isAlive() || !(unit instanceof HiveUnit)) {
                continue;
            }
            UnitState before = unitStates.get(id);
            boolean onGround = unit.onGround();
            if (before != null) {
                double dx = unit.getX() - before.x();
                double dz = unit.getZ() - before.z();
                double distance = Math.sqrt(dx * dx + dz * dz);
                // A unit carried to a spot (a worker that was stuck) has not run there.
                if (distance > 0.0D && distance < 3.0D) {
                    boolean sprinting = distance * 20.0D > SPRINT_SPEED;
                    if (unit.isInWater()) {
                        exhaust((float) distance * SWIM_PER_METER);
                    } else if (sprinting && onGround) {
                        exhaust((float) distance * SPRINT_PER_METER);
                    } else if (onGround) {
                        exhaust((float) distance * WALK_PER_METER);
                    }
                    if (before.onGround() && !onGround && unit.getDeltaMovement().y > 0.0D) {
                        exhaust(sprinting ? SPRINT_JUMP : JUMP);
                    }
                } else if (before.onGround() && !onGround && unit.getDeltaMovement().y > 0.0D) {
                    exhaust(JUMP);
                }
            }
            unitStates.put(id, new UnitState(unit.getX(), unit.getZ(), onGround));
            if (unit.getHealth() < unit.getMaxHealth()) {
                hurt.add(unit);
            }
        }
        // Forget units that are gone.
        unitStates.keySet().removeIf(id -> !(level.getEntity(id) instanceof Mob));
        return hurt;
    }

    /**
     * When the hive is missing a whole drumstick (2 food points) it eats from the stack in its food slot. What the food
     * does besides feed is not applied, and a food that leaves something behind (a bowl) leaves it in the hive's storage.
     */
    /** What a bottle of honey gives, as a player gets it. */
    private static final int HONEY_NUTRITION = 6;
    private static final float HONEY_SATURATION = 1.2F;

    /**
     * With the bee nest task done, the hive drinks a bottle of honey from its honey meter when its food is at or below the level set on the Heart tab.
     * The food slot comes first (it is eaten from before this is looked at).
     */
    private void drinkHoney(HiveHeart heart) {
        if (heart.honeyBelow() <= 0 || foodLevel > heart.honeyBelow() || foodLevel >= 20) {
            return;
        }
        drinkHoneyNow(heart);
    }

    /** Drink a bottle of honey from the meter now, if the hive has the research and a bottle of it. True if it did. */
    private boolean drinkHoneyNow(HiveHeart heart) {
        if (!com.projecthivemind.EvolveTask.BEE_NEST.doneIn(heart.evolveMask()) || !heart.fluids().take(HiveFluids.HONEY, HiveFluids.BUCKET / 3)) {
            return false;
        }
        foodLevel = Mth.clamp(foodLevel + HONEY_NUTRITION, 0, 20);
        saturation = Mth.clamp(saturation + HONEY_SATURATION, 0.0F, (float) foodLevel);
        heart.level().playSound(null, heart.blockPosition(), SoundEvents.HONEY_DRINK, SoundSource.NEUTRAL, 0.5F, 1.0F);
        return true;
    }

    private void eatFromSlot(HiveHeart heart) {
        if (heart.slotBelow() <= 0 || foodLevel > heart.slotBelow()) {
            return;
        }
        eatFromSlotNow(heart);
    }

    /** Eat the food in the food slot now. True if there was some. */
    private boolean eatFromSlotNow(HiveHeart heart) {
        SimpleContainer slot = heart.foodSlot();
        ItemStack stack = slot.getItem(0);
        FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(heart);
        if (food == null) {
            return false;
        }
        eat(food);
        food.usingConvertsTo().ifPresent(leftover -> {
            ItemStack rest = heart.getStorage().addItem(leftover.copy());
            if (!rest.isEmpty()) {
                heart.spawnAtLocation(rest);
            }
        });
        stack.shrink(1);
        if (stack.isEmpty()) {
            slot.setItem(0, ItemStack.EMPTY);
        }
        slot.setChanged();
        heart.level().playSound(null, heart.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.5F,
                heart.level().random.nextFloat() * 0.1F + 0.9F);
        return true;
    }

    /** Below this food level the hive eats whatever it has, however much would be wasted: it does not wait for a perfect moment while it starves. */
    private static final int STARVING = 4;

    /** True if this much food and saturation would all be used: the food level does not pass 20, and the saturation does not pass the new food level. */
    private boolean wastesNothing(int nutrition, float saturationGain) {
        return foodLevel + nutrition <= 20 && saturation + saturationGain <= foodLevel + nutrition + 0.001F;
    }

    /**
     * The Heart tab's "eat at the best moment": the hive eats the food in its slot, or drinks honey, only when none of what it gives would be wasted
     * (the food it restores fits under 20, and so does the saturation), whatever the levels above say. The one the tab puts first is tried first.
     */
    private void eatAtBestMoment(HiveHeart heart) {
        FoodProperties slotFood = heart.foodSlot().getItem(0).isEmpty() ? null : heart.foodSlot().getItem(0).getFoodProperties(heart);
        boolean honey = com.projecthivemind.EvolveTask.BEE_NEST.doneIn(heart.evolveMask()) && heart.fluids().amount(HiveFluids.HONEY) >= HiveFluids.BUCKET / 3;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean honeyTurn = (attempt == 0) == heart.honeyFirst();
            if (honeyTurn && honey && (wastesNothing(HONEY_NUTRITION, HONEY_SATURATION) || foodLevel <= STARVING) && drinkHoneyNow(heart)) {
                return;
            }
            if (!honeyTurn && slotFood != null && (wastesNothing(slotFood.nutrition(), slotFood.saturation()) || foodLevel <= STARVING) && eatFromSlotNow(heart)) {
                return;
            }
        }
    }


    public void save(CompoundTag tag) {
        tag.putInt(FOOD_TAG, foodLevel);
        tag.putFloat(SATURATION_TAG, saturation);
        tag.putFloat(EXHAUSTION_TAG, exhaustion);
        tag.putInt(TIMER_TAG, tickTimer);
    }

    public void load(CompoundTag tag) {
        if (tag.contains(FOOD_TAG)) {
            foodLevel = tag.getInt(FOOD_TAG);
            saturation = tag.getFloat(SATURATION_TAG);
            exhaustion = tag.getFloat(EXHAUSTION_TAG);
            tickTimer = tag.getInt(TIMER_TAG);
        }
    }
}
