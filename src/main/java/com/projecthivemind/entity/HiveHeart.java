package com.projecthivemind.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.HiveActions;
import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveFood;
import com.projecthivemind.HiveBrewing;
import com.projecthivemind.HivePortals;
import com.projecthivemind.HiveFurnace;
import com.projecthivemind.HiveLevel;
import com.projecthivemind.HiveLevels;
import com.projecthivemind.HiveSight;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.ModComponents;
import com.projecthivemind.ScoutItems;
import com.projecthivemind.UnitKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Hive Heart: a stationary entity that is the hive. It has health, owns the hive's shared inventory and
 * knows the hive's level. The chunks around it are force-loaded, so it stays available while the camera roams.
 */
public class HiveHeart extends Mob {
    private static final String OWNER_TAG = "HiveOwner";
    private static final String LEVEL_TAG = "HiveLevel";
    private static final String LOGS_TAG = "QuestLogs";
    private static final String EXPLORED_TAG = "ExploredChunks";
    private static final String KILLS_TAG = "QuestKills";
    private static final String AGE_TAG = "HiveAge";
    private static final String FURNACE_TAG = "HiveFurnace";
    private static final String SCOUT_HAND_TAG = "ScoutHand";
    private static final String JUKEBOX_TAG = "JukeboxDisc";
    private static final String FOOD_SLOT_TAG = "FoodSlot";
    private static final String TRASH_TAG = "Trash";
    private static final String STORAGE_TAG = "HiveStorage";
    private static final String EVOLVE_TAG = "EvolveTasks";
    private static final String ARMOR_TAG = "HiveArmor";
    private static final String SCOUT_ARMOR_TAG = "ScoutArmor";
    private static final String TOOLS_TAG = "HiveTools";
    private static final String ARMOR_VERSION_TAG = "ArmorVersion";
    private static final String TOOL_VERSION_TAG = "ToolVersion";

    @Nullable
    private UUID ownerId;
    private int hiveLevel = 1;
    /** Quest progress: the most logs the hive has held at once, up to what the quest asks. It never goes back down. */
    private int logsProgress;
    /** Quest progress: the most coal and iron ingots the hive has held at once (up to what the quest asks), and the lowest height a unit has been at. */
    private int coalProgress;
    private int ironProgress;
    private int lowestY = Integer.MAX_VALUE;
    /** What the storage held of logs, coal and iron ingots at the last quest check (-1 before the first), to see how much came in since. Not saved. */
    private final int[] lastHeld = {-1, -1, -1, -1};
    /** Quest progress: blaze rods collected, and whether the Nether has been entered. */
    private int blazeProgress;
    private boolean netherEntered;
    /** Quest progress: the Ender Dragon was defeated while this hive was in the End. Saved. */
    private boolean dragonDefeated;
    /** Quest progress: mobs the hive's units have killed. */
    private int kills;
    /** Quest progress: ticks the hive has lasted, counted only while its owner is in the world. */
    private int ageTicks;
    /** The hive's constructions: bridges, staircases, towers and shafts, and the workers on each. Saved. */
    private final com.projecthivemind.build.Constructions constructions = new com.projecthivemind.build.Constructions();
    /** Which mob each soldier is fighting by itself (see SoldierDefaultAttackGoal), so that the soldiers split up between the mobs. Not saved. */
    private final java.util.Map<UUID, FightAssignment> fights = new java.util.HashMap<>();

    private record FightAssignment(UUID target, long until) {
    }

    /** A soldier is going after this mob: it is counted as on it for a few seconds, and renewed as long as it keeps at it. */
    public void assignFight(UUID soldier, UUID target, long now) {
        fights.put(soldier, new FightAssignment(target, now + 60L));
    }

    public void releaseFight(UUID soldier) {
        fights.remove(soldier);
    }

    /** How many soldiers other than this one are on this mob. */
    public int fightersOn(UUID target, UUID except, long now) {
        fights.values().removeIf(assignment -> assignment.until() <= now);
        int count = 0;
        for (java.util.Map.Entry<UUID, FightAssignment> entry : fights.entrySet()) {
            if (!entry.getKey().equals(except) && entry.getValue().target().equals(target)) {
                count++;
            }
        }
        return count;
    }
    /** The health last sent to the owner for the health bar. */
    private float syncedHealth = -1.0F;
    private int syncedArmor = -1;
    private int syncedFood = -1;
    /** The furnace built into the Heart, usable once a furnace has been consumed on the Evolve tab. It always exists so the menu code stays simple. */
    private final HiveFurnace furnace = new HiveFurnace();
    /** The brewing stand built into the Heart, usable once a brewing stand has been consumed on the Evolve tab. */
    private final HiveBrewing brewing = new HiveBrewing();
    /** The portals standing and the summoning going on (from level 2). */
    private final PortalNetwork portals = new PortalNetwork();
    /** Where each of the hive's units was last seen (dimension and chunk), so they can be loaded again after a restart. */
    private final java.util.Map<UUID, com.projecthivemind.HivemindManager.UnitSpot> unitSpots = new java.util.HashMap<>();
    /** The item in the scout's hand, put there from the hive menu. The scout holds a copy, and what it uses comes off this. */
    private final SimpleContainer scoutHand = new SimpleContainer(SCOUT_HOTBAR_SLOTS);
    /** The slot of the scout hotbar that is held: always the first (the hive menu's hand slot) except while a player controls a scout and picks another. Not saved. */
    private int scoutSelected;
    /** The hive's jukebox slot: the music disc that is playing. Saved. */
    private final SimpleContainer jukeboxSlot = new SimpleContainer(1);
    /** The disc the owner's client was last told about. Not saved. */
    private String lastMusic = "";
    /** The food the hive eats from: put in the hive menu, under the armor slots. Only food goes in. */
    private final SimpleContainer foodSlot = new SimpleContainer(1);
    /** The trash: what the player put there stays until another item is put over it. One slot, holding a stack as large as the storage's. Saved. */
    private final HiveStorage trash = new HiveStorage(1, () -> 1000);
    /** The hive's hunger: see HiveFood. */
    private final HiveFood food = new HiveFood();
    /** Quest progress: the chunks (as packed ChunkPos) the hive's units have been in, outside the hive area. */
    private final Set<Long> exploredChunks = new HashSet<>();
    private HiveStorage storage = new HiveStorage(HiveLevels.get(1).storageSlots(), this::stackMultiplier);
    /** One piece per armor slot, in {@link HiveEquipment#ARMOR_SLOTS} order. New soldiers get copies of these. */
    private final SimpleContainer armorSlots = new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length);
    /** The armor every scout wears (leather only), shared by all scouts the way the soldiers' armor is by all soldiers. Saved. */
    private final SimpleContainer scoutArmor = new SimpleContainer(HiveEquipment.ARMOR_SLOTS.length);
    /** Tools and weapons. New soldiers wield a copy of the one with the highest attack damage. */
    private final SimpleContainer toolSlots = new SimpleContainer(HiveEquipment.TOOL_SLOTS);

    /** What the hive can see from, refreshed several times a second. Not saved. */
    private List<HiveSight.Eye> sightEyes = List.of();
    /** The mobs the owner's client was last told are in sight. Not saved. */
    private Set<Integer> syncedSight = Set.of();
    /** The eyes last sent to the owner, which are those of the camera's dimension. */
    private List<HiveSight.Eye> syncedEyes = List.of();

    public List<HiveSight.Eye> syncedEyes() {
        return syncedEyes;
    }

    public void setSyncedEyes(List<HiveSight.Eye> eyes) {
        this.syncedEyes = eyes;
    }



    public List<HiveSight.Eye> sightEyes() {
        return sightEyes;
    }

    public void setSightEyes(List<HiveSight.Eye> eyes) {
        this.sightEyes = eyes;
    }

    public Set<Integer> syncedSight() {
        return syncedSight;
    }

    public void setSyncedSight(Set<Integer> mobIds) {
        this.syncedSight = mobIds;
    }

    /** Entity ids of the units the owner has selected right now, as their client reports. Not saved. */
    private Set<Integer> selectedUnits = Set.of();
    /** The hive's teams of units. Saved. */
    private final HiveTeams teams = new HiveTeams();
    /** The settings of the hive's units, kept by their number in the list of their kind. Saved. */
    private final SlotConfigs slotConfigs = new SlotConfigs();



    public SlotConfigs slotConfigs() {
        return slotConfigs;
    }

    public HiveTeams teams() {
        return teams;
    }

    /**
     * The scout this unit's team follows, or null: when the unit is in a team that has a scout, and is not that scout itself. Only the
     * first scout found counts. While that scout is inside the hive border the team's behaviour is off: this is null and the unit
     * goes on with its normal AI.
     */
    @Nullable
    public Mob teamLeader(Mob member) {
        HiveScout scout = teamScout(member);
        return scout != null && !isInsideBorder(scout) ? scout : null;
    }

    /** True if the unit is in a team whose scout is inside the hive border: the team's behaviour is off for it. */
    public boolean teamScoutInside(Mob member) {
        HiveScout scout = teamScout(member);
        return scout != null && isInsideBorder(scout);
    }

    private boolean isInsideBorder(Mob mob) {
        return mob.level() == this.level() && com.projecthivemind.HiveArea.containsCube(this, mob.getX(), mob.getY(), mob.getZ());
    }

    @Nullable
    private HiveScout teamScout(Mob member) {
        int team = teams.teamOf(member.getUUID());
        // The scout is looked for where the member is, which is not always where the Heart is: a team that has gone through a portal is in the
        // Nether with its scout, and follows it there (a scout in another dimension than its team is not followed).
        if (team < 0 || !(member.level() instanceof ServerLevel level)) {
            return null;
        }
        for (UUID id : teams.members(team)) {
            if (!id.equals(member.getUUID()) && level.getEntity(id) instanceof HiveScout scout && scout.isAlive()) {
                return scout;
            }
        }
        return null;
    }

    /** Units the player has selected follow orders only; they ignore the hive's default behaviour. */
    public boolean isUnitSelected(int entityId) {
        return selectedUnits.contains(entityId);
    }

    public void setSelectedUnits(Set<Integer> entityIds) {
        this.selectedUnits = entityIds;
    }

    /** Counts how many times the armor slots have really been changed. Saved, so units made earlier stay comparable. */
    private int armorVersion;
    /** Counts how many times the tool slots have really been changed. */
    private int toolVersion;
    /** What the gear slots held when changes were last looked for, to tell a real swap from wear. Not saved. */
    @Nullable
    private List<ItemStack> lastArmorSignature;
    @Nullable
    private List<ItemStack> lastToolSignature;

    /** Ticks until the next spawning interval, which tops up units and refreshes out-of-date ones. */
    public int ticksUntilSpawn() {
        return Math.max(0, com.projecthivemind.EvolveTask.spawnIntervalTicks(evolveMask) - spawnTimer);
    }

    /**
     * The gear version a unit of this kind is made with. Soldiers care about armor and tools, workers only about
     * tools, and collectors use no gear. A unit made at an older version than this is out of date.
     */
    public int gearVersionFor(UnitKind kind) {
        return switch (kind) {
            case SOLDIER -> armorVersion + toolVersion;
            case WORKER -> toolVersion;
            case SCOUT, COLLECTOR, FEEDER -> 0;
        };
    }

    /**
     * Look for changes to the armor and tool slots since last time, bump the versions if there are any, and say which changed (bit 1 armor,
     * bit 2 tools; 0 for none). Only a real
     * change counts: a tool wearing down, or the hidden link stamp being added, does not.
     */
    public int refreshGearVersions() {
        List<ItemStack> armor = signature(armorSlots);
        List<ItemStack> tools = signature(toolSlots);
        if (lastArmorSignature == null || lastToolSignature == null) {
            // First look since the Heart loaded: take it as the starting point rather than as a change.
            lastArmorSignature = armor;
            lastToolSignature = tools;
            return 0;
        }
        int changed = 0;
        if (!sameSignature(armor, lastArmorSignature)) {
            armorVersion++;
            lastArmorSignature = armor;
            changed |= 1;
        }
        if (!sameSignature(tools, lastToolSignature)) {
            toolVersion++;
            lastToolSignature = tools;
            changed |= 2;
        }
        return changed;
    }

    /** The contents of a gear container, with wear and the link stamp stripped off so only a real swap shows up. */
    private static List<ItemStack> signature(SimpleContainer container) {
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack copy = container.getItem(i).copy();
            if (!copy.isEmpty()) {
                copy.remove(DataComponents.DAMAGE);
                copy.remove(ModComponents.HIVE_LINK.get());
            }
            contents.add(copy);
        }
        return contents;
    }

    private static boolean sameSignature(List<ItemStack> a, List<ItemStack> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** How far along each block being dug is, from 0 to 1. Shared by every worker digging it. Not saved. */
    private final Map<BlockPos, Float> digProgress = new HashMap<>();
    /** What the owner's client was last told units are working on. Not saved. */
    private HiveActions.Snapshot syncedActions = HiveActions.Snapshot.EMPTY;

    /** Add to a block's dig progress and return the new total. */
    public float addDigProgress(BlockPos pos, float amount) {
        return digProgress.merge(pos.immutable(), amount, Float::sum);
    }

    public void clearDigProgress(BlockPos pos) {
        digProgress.remove(pos);
    }

    public HiveActions.Snapshot syncedActions() {
        return syncedActions;
    }

    public void setSyncedActions(HiveActions.Snapshot snapshot) {
        this.syncedActions = snapshot;
    }


    /** The loaded Hive Heart with this id, or null. */
    @Nullable
    public static HiveHeart find(Level level, @Nullable UUID id) {
        if (id != null && level instanceof ServerLevel serverLevel) {
            HiveHeart here = findIn(serverLevel, id);
            if (here != null) {
                return here;
            }
            for (ServerLevel other : serverLevel.getServer().getAllLevels()) {
                if (other != serverLevel && findIn(other, id) instanceof HiveHeart heart) {
                    return heart;
                }
            }
        }
        return null;
    }

    @Nullable
    private static HiveHeart findIn(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof HiveHeart heart && heart.isAlive() ? heart : null;
    }

    @Override
    public void remove(RemovalReason reason) {
        // A destroyed Heart takes its light away with it.
        if (reason.shouldDestroy() && !this.level().isClientSide) {
            removeLight();
            removeGlowLights();
            releaseSlowed();
        }
        // A destroyed Heart no longer holds its chunks loaded (one that is merely unloading keeps them).
        if (reason.shouldDestroy()) {
            HivemindManager.holdHiveChunks(this, false);
        }
        super.remove(reason);
    }

    /** Where this Heart's light block is, or null if it has not put one down. Not saved: it is found again from the Heart's own place. */
    @Nullable
    private BlockPos lightPos;

    /** Put the light block where the Heart is (only in air, or where it already is), and take it from anywhere the Heart has moved from. */
    private void keepLight() {
        BlockPos here = this.blockPosition();
        if (lightPos != null && !lightPos.equals(here)) {
            removeLight();
        }
        net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(here);
        if (state.isAir()) {
            this.level().setBlock(here, net.minecraft.world.level.block.Blocks.LIGHT.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15), 2);
            lightPos = here;
        } else if (state.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
            lightPos = here;
        }
    }

    /** Take the light block away, if it is still there. */
    private void removeLight() {
        if (lightPos != null && this.level().getBlockState(lightPos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
            this.level().setBlock(lightPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        lightPos = null;
    }
    /**
     * How far apart the glowstone task's light blocks are, in blocks. Light 15 loses 1 for every block it travels, and monsters only spawn where the
     * block light is 0: lights 8 apart and 5 above the ground leave every point of the ground at light 2 or more (4 + 4 sideways and 5 up is 13).
     */
    private static final int GLOW_SPACING = 8;
    /** How far above the Heart's own block the lights hang: 4, which is 5 above the ground the Heart stands on. */
    private static final int GLOW_HEIGHT = 4;
    /** If the spot is not air (a hill, a tree), the light goes up to this many blocks higher instead. */
    private static final int GLOW_RISE = 3;

    /**
     * Where the glowstone task's lights were last put: the centre of their grid, its dimension and the hive's radius then. Saved with the Heart, so
     * that after a restart, or when a new Heart takes the hive up somewhere else, the lights left at the old place are found and taken away.
     */
    @Nullable
    private BlockPos glowCenter;
    @Nullable
    private String glowDimension;
    private int glowRadius;

    /** The columns where the lights go, for a grid centred on this place: a square over the hive area, and a little past its edge so that the edge is covered. */
    private static java.util.List<BlockPos> glowColumns(BlockPos center, int radius) {
        int steps = (radius + GLOW_SPACING - 1) / GLOW_SPACING;
        java.util.List<BlockPos> columns = new java.util.ArrayList<>();
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                columns.add(center.offset(i * GLOW_SPACING, 0, j * GLOW_SPACING));
            }
        }
        return columns;
    }

    /** Take every light block away where a grid with this centre and radius would hang one. */
    private void clearGlowGrid(BlockPos center, int radius) {
        for (BlockPos column : glowColumns(center, radius)) {
            if (!this.level().hasChunkAt(column)) {
                continue;
            }
            for (int rise = 0; rise <= GLOW_RISE; rise++) {
                BlockPos spot = column.above(rise);
                if (this.level().getBlockState(spot).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                    this.level().setBlock(spot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * With the glowstone task done, light blocks hang over the hive area in a grid, bright enough that nothing hostile can spawn inside it. They
     * only go where there is air (or a little above where there is not), and the ones that are there are left alone. Called once a second, and at
     * once when the task is done or the hive levels up, so that it follows the Heart: lights at an old place (the Heart is somewhere else now) are
     * taken away, and the grid grows with the hive area.
     */
    private void keepGlowLights() {
        if (!com.projecthivemind.EvolveTask.GLOWSTONE.doneIn(evolveMask) || this.level().isClientSide) {
            return;
        }
        BlockPos center = this.blockPosition().above(GLOW_HEIGHT);
        int radius = com.projecthivemind.HiveLevels.get(hiveLevel).infectionRadius();
        String dimension = this.level().dimension().location().toString();
        // The lights of an earlier place: only in this dimension can they be reached from here.
        if (glowCenter != null && (!glowCenter.equals(center) || !dimension.equals(glowDimension)) && dimension.equals(glowDimension)) {
            clearGlowGrid(glowCenter, glowRadius);
        }
        glowCenter = center;
        glowDimension = dimension;
        glowRadius = radius;
        net.minecraft.world.level.block.state.BlockState light = net.minecraft.world.level.block.Blocks.LIGHT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15);
        for (BlockPos column : glowColumns(center, radius)) {
            if (!this.level().hasChunkAt(column)) {
                continue;
            }
            for (int rise = 0; rise <= GLOW_RISE; rise++) {
                BlockPos spot = column.above(rise);
                net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(spot);
                if (state.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                    break;
                }
                if (state.isAir()) {
                    this.level().setBlock(spot, light, 2);
                    break;
                }
            }
        }
    }

    /** The hive grew or changed (a level-up): look at the glowstone task's lights again at once. */
    public void refreshGlowLights() {
        keepGlowLights();
    }

    /** The Heart is destroyed: its lights go with it, wherever it last put them. */
    private void removeGlowLights() {
        if (glowCenter != null && this.level().dimension().location().toString().equals(glowDimension)) {
            clearGlowGrid(glowCenter, glowRadius);
        }
        glowCenter = null;
        glowDimension = null;
    }



    // ---- the totem of undying and the cobweb evolutions ----

    /** 5 minutes: how long the totem effect takes to come back after it has saved the Heart. */
    public static final int TOTEM_COOLDOWN_TICKS = 6000;
    /** Ticks left until the totem effect is ready again; 0 when it is ready. Saved, so a restart does not give it back. */
    private int totemCooldown;

    public int totemCooldown() {
        return totemCooldown;
    }

    /**
     * Called when the Heart is about to die. With the totem task done and the effect ready it is as if the Heart held a totem of undying: it does not
     * die, it is left with 1 health, its effects are cleared and it gets Regeneration, Absorption and Fire Resistance as the totem gives, and the effect
     * is not ready again for {@value #TOTEM_COOLDOWN_TICKS} ticks. Like the item it does not save the Heart from what gets past all protection (the void,
     * /kill). True if it saved the Heart.
     */
    public boolean tryUndying(net.minecraft.world.damagesource.DamageSource source) {
        if (!com.projecthivemind.EvolveTask.TOTEM.doneIn(evolveMask) || totemCooldown > 0 || this.level().isClientSide
                || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        this.setHealth(1.0F);
        this.removeAllEffects();
        this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 900, 1));
        this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 100, 1));
        this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 800, 0));
        // The totem's own particles and sound, as a mob that pops one gives them.
        this.level().broadcastEntityEvent(this, (byte) 35);
        totemCooldown = TOTEM_COOLDOWN_TICKS;
        return true;
    }

    private static final net.minecraft.resources.ResourceLocation COBWEB_SLOW = com.projecthivemind.ProjectHivemind.id("cobweb_slow");
    /** How much slower enemies in the hive area are with the cobweb task done: 25%. */
    private static final double COBWEB_SLOWNESS = -0.25D;
    /** The enemies slowed right now, so that one that has left the hive area (or the task being gone) is let go. Not saved: the slow does not save either. */
    private final java.util.Set<UUID> slowed = new HashSet<>();

    private static void slow(net.minecraft.world.entity.LivingEntity mob, boolean slow) {
        for (net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute : java.util.List.of(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, net.minecraft.world.entity.ai.attributes.Attributes.FLYING_SPEED)) {
            net.minecraft.world.entity.ai.attributes.AttributeInstance instance = mob.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            if (slow && !instance.hasModifier(COBWEB_SLOW)) {
                instance.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(COBWEB_SLOW, COBWEB_SLOWNESS,
                        net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            } else if (!slow) {
                instance.removeModifier(COBWEB_SLOW);
            }
        }
    }

    /** With the cobweb task done, every enemy inside the hive area is 25% slower (checked a few times a second); the ones that left are let go. */
    private void keepCobweb() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.Set<UUID> now = new HashSet<>();
        if (com.projecthivemind.EvolveTask.COBWEB.doneIn(evolveMask)) {
            for (Mob mob : serverLevel.getEntitiesOfClass(Mob.class, com.projecthivemind.HiveArea.areaBox(serverLevel, this),
                    candidate -> candidate instanceof net.minecraft.world.entity.monster.Enemy && candidate.isAlive() && !HiveAttacks.spares(candidate))) {
                slow(mob, true);
                now.add(mob.getUUID());
            }
        }
        for (UUID id : slowed) {
            if (!now.contains(id) && serverLevel.getEntity(id) instanceof Mob mob) {
                slow(mob, false);
            }
        }
        slowed.clear();
        slowed.addAll(now);
    }

    /**
     * With the experience bottle task done, the hive gets a bottle o' enchanting's worth of experience (3 to 11 points, as the bottle gives) every
     * minute, on the owner's experience bar.
     */
    private void giveBottleExperience() {
        if (this.ownerId == null || this.getServer() == null) {
            return;
        }
        net.minecraft.server.level.ServerPlayer owner = this.getServer().getPlayerList().getPlayer(this.ownerId);
        if (owner != null) {
            HivemindManager.giveHiveExperience(owner, this, 3 + this.random.nextInt(5) + this.random.nextInt(5));
        }
    }

    /** The Heart is gone: no enemy stays slowed for it. */
    private void releaseSlowed() {
        if (this.level() instanceof ServerLevel serverLevel) {
            for (UUID id : slowed) {
                if (serverLevel.getEntity(id) instanceof Mob mob) {
                    slow(mob, false);
                }
            }
        }
        slowed.clear();
    }

    /** 10 seconds. */
    private static final int SPAWN_INTERVAL_TICKS = 200;

    /** 1 second: how often the owner is told which blocks have units working on them. */
    private static final int ACTION_SYNC_INTERVAL_TICKS = 20;
    public static final int QUEST_INTERVAL_TICKS = 20;

    /** A quarter second: how often what the hive can see is worked out, so hidden mobs appear and vanish promptly. */
    private static final int SIGHT_INTERVAL_TICKS = 5;

    private int spawnTimer;

    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> DATA_LEVEL =
            net.minecraft.network.syncher.SynchedEntityData.defineId(HiveHeart.class, net.minecraft.network.syncher.EntityDataSerializers.INT);

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LEVEL, 1);
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_LEVEL.equals(key)) {
            this.setPos(this.position());
        }
    }

    /** How wide the Heart is at a level, in blocks: 1, then 3 from level 3, then 5 from level 5. */
    public static int widthAt(int level) {
        return level >= 5 ? 5 : level >= 3 ? 3 : 1;
    }

    /** How tall the Heart is at a level, in blocks: 1, 2 from level 2, 3 from level 4. */
    public static int heightAt(int level) {
        return level >= 4 ? 3 : level >= 2 ? 2 : 1;
    }

    /** The level the Heart looks like (kept in step on the client by the synced data). */
    public int visualLevel() {
        return this.entityData.get(DATA_LEVEL);
    }

    /** The body is solid: players and mobs cannot walk through it. */
    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    /** The body is as big as the level says (the entity type itself is a block: its size cannot change with a level). */
    @Override
    protected net.minecraft.world.phys.AABB makeBoundingBox() {
        if (this.entityData == null) {
            return super.makeBoundingBox();
        }
        double half = widthAt(visualLevel()) / 2.0D;
        net.minecraft.world.phys.Vec3 at = this.position();
        return new net.minecraft.world.phys.AABB(at.x - half, at.y, at.z - half, at.x + half, at.y + heightAt(visualLevel()), at.z + half);
    }

    /** Standing inside its own bigger body is not suffocating. */
    @Override
    public boolean isInWall() {
        return false;
    }

    public HiveHeart(EntityType<? extends HiveHeart> type, Level level) {
        super(type, level);
        // The armor it wears is the hive's: it is dropped with the rest of the hive's things (see HivemindManager), not here too.
        for (net.minecraft.world.entity.EquipmentSlot slot : HiveEquipment.ARMOR_SLOTS) {
            this.setDropChance(slot, 0.0F);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            // Until it is as big as it gets, small particles mark the space the full-size Heart will take.
            if (visualLevel() < 5 && this.tickCount % 3 == 0) {
                spawnGrowthMarkers();
            }
            return;
        }
        if (totemCooldown > 0) {
            totemCooldown--;
        }
        if (this.tickCount % 10 == 0) {
            keepCobweb();
        }
        if (this.tickCount % 1200 == 0 && com.projecthivemind.EvolveTask.EXPERIENCE_BOTTLE.doneIn(evolveMask)) {
            giveBottleExperience();
        }
        // The Heart gives off light: a light block (invisible, inside its body) at its own place, kept there once a second.
        if (this.tickCount % 20 == 0) {
            keepLight();
            keepGlowLights();
        }
        // The Heart makes its own units: every interval it tops up what is below the cap and refreshes out-of-date gear.
        if (++spawnTimer >= com.projecthivemind.EvolveTask.spawnIntervalTicks(evolveMask)) {
            spawnTimer = 0;
            HivemindManager.tickUnitSpawning(this);
        }
        // Sight first, so the workers' scans and the action sync below always use fresh eyes.
        if (this.tickCount % SIGHT_INTERVAL_TICKS == 0) {
            HivemindManager.tickSight(this);
            HivemindManager.tickHealthSync(this);
            HivemindManager.tickKeepLoaded(this);
        }
        if (this.tickCount % 5 == 0) {
            HivemindManager.tickGearSync(this);
        }
        if (this.tickCount % 20 == 0) {
            HivemindManager.tickDefence(this);
            HeartAlerts.tick(this);
            refillFoodSlot();
        }
        if (this.tickCount % 2 == 0) {
            // The storage is kept in alphabetical order, with like stacks merged.
            StorageSorter.sort(storage);
        }
        HivemindManager.tickNaturalSpawning(this);
        if (this.level() instanceof ServerLevel foodLevel) {
            food.tick(this, foodLevel);
        }
        if (this.tickCount % 10 == 0) {
            wearHiveArmor();
            if (ownerId != null && this.getServer() != null) {
                ServerPlayer owner = this.getServer().getPlayerList().getPlayer(ownerId);
                if (owner != null) {
                    ScoutItems.tickBook(this, owner);
                }
            }
        }
        if (com.projecthivemind.EvolveTask.FURNACE.doneIn(evolveMask) && this.level() instanceof ServerLevel serverLevel) {
            furnace.tick(serverLevel);
        }
        if (com.projecthivemind.EvolveTask.BREWING_STAND.doneIn(evolveMask) && this.level() instanceof ServerLevel serverLevel) {
            brewing.tick(serverLevel);
        }
        HeartTurret.tick(this);
        HeartThorns.tick(this);
        HeartSonicBoom.tick(this);
        HeartBeam.tick(this);
        HeartAura.tick(this);
        HiveMusic.tick(this);
        com.projecthivemind.HiveConstructions.tick(this);
        HivePortals.tick(this);
        if (this.tickCount % QUEST_INTERVAL_TICKS == 0) {
            HivemindManager.tickQuests(this);
        }
        if (this.tickCount % ACTION_SYNC_INTERVAL_TICKS == 0) {
            HivemindManager.tickActionSync(this);
        }
    }

    public static AttributeSupplier.Builder createHeartAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, HiveLevels.get(1).maxHealth())
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    public void setOwnerId(@Nullable UUID ownerId) {
        this.ownerId = ownerId;
    }

    public int logsProgress() {
        return logsProgress;
    }

    public void setLogsProgress(int logs) {
        this.logsProgress = logs;
    }

    /**
     * Quest progress for something collected: whatever the storage holds now beyond what it held at the last check counts as collected,
     * up to {@code limit}. What is taken out (melted, crafted, used) is never taken off, and putting it back counts again. {@code slot}
     * 0 is logs, 1 coal, 2 iron ingots, 3 blaze rods; returns the new progress.
     */
    public int collected(int slot, int heldNow, int progress, int limit) {
        int gained = lastHeld[slot] < 0 ? 0 : Math.max(0, heldNow - lastHeld[slot]);
        lastHeld[slot] = heldNow;
        return Math.min(limit, progress + gained);
    }

    public int blazeProgress() {
        return blazeProgress;
    }

    public void setBlazeProgress(int blazeRods) {
        this.blazeProgress = blazeRods;
    }

    public boolean dragonDefeated() {
        return dragonDefeated;
    }

    public void setDragonDefeated(boolean defeated) {
        this.dragonDefeated = defeated;
    }

    public boolean netherEntered() {
        return netherEntered;
    }

    public void setNetherEntered(boolean entered) {
        this.netherEntered = entered;
    }

    public int coalProgress() {
        return coalProgress;
    }

    public void setCoalProgress(int coal) {
        this.coalProgress = coal;
    }

    public int ironProgress() {
        return ironProgress;
    }

    public void setIronProgress(int iron) {
        this.ironProgress = iron;
    }

    /** The lowest block height one of the hive's units has stood at, or Integer.MAX_VALUE if none has been counted. */
    public int lowestY() {
        return lowestY;
    }

    public void setLowestY(int y) {
        this.lowestY = y;
    }

    public int kills() {
        return kills;
    }

    public void addKill() {
        kills++;
    }

    public int syncedFood() {
        return syncedFood;
    }

    public void setSyncedFood(int food) {
        this.syncedFood = food;
    }

    public int syncedArmor() {
        return syncedArmor;
    }

    public void setSyncedArmor(int armor) {
        this.syncedArmor = armor;
    }

    public float syncedHealth() {
        return syncedHealth;
    }

    public void setSyncedHealth(float health) {
        this.syncedHealth = health;
    }

    public com.projecthivemind.build.Constructions constructions() {
        return constructions;
    }


    public int ageTicks() {
        return ageTicks;
    }

    public void addAge(int ticks) {
        ageTicks += ticks;
    }

    /**
     * The Heart wears the armor in the hive's armor slots: the very same stacks, so it gets what armor gives (armor and
     * toughness points, knockback resistance, protection enchantments) and the armor wears down as the Heart takes
     * hits. The game works the armor's attributes out from what is equipped, so this is all it takes.
     */
    private void wearHiveArmor() {
        for (int i = 0; i < HiveEquipment.ARMOR_SLOTS.length; i++) {
            ItemStack stored = armorSlots.getItem(i);
            if (this.getItemBySlot(HiveEquipment.ARMOR_SLOTS[i]) != stored) {
                this.setItemSlot(HiveEquipment.ARMOR_SLOTS[i], stored);
            }
        }
    }

    public HiveFood food() {
        return food;
    }

    public HiveStorage trash() {
        return trash;
    }

    public SimpleContainer foodSlot() {
        return foodSlot;
    }

    /** Keeps the food slot full: while it holds less than a full stack, the same food (same item and data) is moved in from the hive's storage. Once a second. */
    private void refillFoodSlot() {
        ItemStack held = foodSlot.getItem(0);
        if (held.isEmpty() || held.getCount() >= held.getMaxStackSize()) {
            return;
        }
        for (int i = 0; i < storage.getContainerSize() && held.getCount() < held.getMaxStackSize(); i++) {
            ItemStack stored = storage.getItem(i);
            if (stored.isEmpty() || !ItemStack.isSameItemSameComponents(held, stored)) {
                continue;
            }
            int moved = Math.min(held.getMaxStackSize() - held.getCount(), stored.getCount());
            held.grow(moved);
            stored.shrink(moved);
            storage.setChanged();
        }
        foodSlot.setChanged();
    }

    /** Like a player, the Heart wears down the armor it wears (the hive's own pieces) when it takes a hit. */
    @Override
    protected void hurtArmor(DamageSource source, float damage) {
        this.doHurtEquipment(source, damage, HiveEquipment.ARMOR_SLOTS);
    }

    public SimpleContainer jukeboxSlot() {
        return jukeboxSlot;
    }

    public String lastMusic() {
        return lastMusic;
    }

    public void setLastMusic(String disc) {
        this.lastMusic = disc;
    }

    /** How many slots the scouts' hotbar has: the first is the item the scouts hold for the orders they are given. */
    public static final int SCOUT_HOTBAR_SLOTS = 9;

    public int scoutSelected() {
        return scoutSelected;
    }

    public void setScoutSelected(int slot) {
        this.scoutSelected = Math.max(0, Math.min(SCOUT_HOTBAR_SLOTS - 1, slot));
    }

    /** The item the scouts hold now: the selected slot of the hotbar. */
    public ItemStack scoutHeld() {
        return scoutHand.getItem(scoutSelected);
    }

    public void setScoutHeld(ItemStack stack) {
        scoutHand.setItem(scoutSelected, stack);
    }

    public SimpleContainer scoutHand() {
        return scoutHand;
    }

    public HiveFurnace furnace() {
        return furnace;
    }

    public HiveBrewing brewing() {
        return brewing;
    }

    public PortalNetwork portals() {
        return portals;
    }

    @Nullable
    public com.projecthivemind.HivemindManager.UnitSpot unitSpot(UUID unit) {
        return unitSpots.get(unit);
    }

    public void setUnitSpot(UUID unit, com.projecthivemind.HivemindManager.UnitSpot spot) {
        unitSpots.put(unit, spot);
    }

    public void forgetUnitSpot(UUID unit) {
        unitSpots.remove(unit);
    }

    public Set<Long> exploredChunks() {
        return exploredChunks;
    }

    public int exploredChunkCount() {
        return exploredChunks.size();
    }

    /** Client side: a few spores drifting in the cube the Heart will fill at level 5 (5 wide, 3 tall). */
    private void spawnGrowthMarkers() {
        double half = widthAt(5) / 2.0D;
        for (int i = 0; i < 2; i++) {
            this.level().addParticle(net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE,
                    this.getX() + (this.random.nextDouble() * 2.0D - 1.0D) * half,
                    this.getY() + this.random.nextDouble() * heightAt(5),
                    this.getZ() + (this.random.nextDouble() * 2.0D - 1.0D) * half, 0.0D, 0.01D, 0.0D);
        }
    }

    public int hiveLevel() {
        return hiveLevel;
    }

    /** The enchantments the hive has made available in its enchanting station, by consuming a book with each. Saved. */
    private final java.util.Set<net.minecraft.resources.ResourceLocation> unlockedEnchants = new java.util.LinkedHashSet<>();

    public java.util.Set<net.minecraft.resources.ResourceLocation> unlockedEnchants() {
        return java.util.Collections.unmodifiableSet(unlockedEnchants);
    }

    public void unlockEnchant(net.minecraft.resources.ResourceLocation id) {
        unlockedEnchants.add(id);
    }

    /** The evolution tasks the hive has done, as a mask of {@link com.projecthivemind.EvolveTask#bit}s. */
    private long evolveMask;

    public long evolveMask() {
        return evolveMask;
    }

    /**
     * The tasks still to do that the hive can do now: those whose item is somewhere in its storage, as a mask of bits.
     */
    public long evolveReadyMask() {
        long ready = 0L;
        for (com.projecthivemind.EvolveTask task : com.projecthivemind.EvolveTask.values()) {
            if (!task.doneIn(evolveMask) && storageHasFor(task) >= 0) {
                ready |= task.bit();
            }
        }
        return ready;
    }

    /** The storage slot holding an item for this task, or -1. */
    public int storageHasFor(com.projecthivemind.EvolveTask task) {
        for (int i = 0; i < storage.getContainerSize(); i++) {
            if (!storage.getItem(i).isEmpty() && task.accepts(storage.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Mark a task as done: its reward is in effect from now on. */
    public void completeEvolve(com.projecthivemind.EvolveTask task) {
        evolveMask |= task.bit();
        if (task == com.projecthivemind.EvolveTask.GLOWSTONE) {
            keepGlowLights();
        }
    }

    /** How many full stacks a slot of the hive's storage holds: one more for each task done. */
    /** How many teams the hive has: one for the Heart, and one for each portal it may have (see HivePortals#max). */
    public int teamCount() {
        return 1 + HivePortals.max(this);
    }

    public int stackMultiplier() {
        return com.projecthivemind.EvolveTask.stackMultiplier(evolveMask);
    }

    public HiveStorage getStorage() {
        return storage;
    }

    public SimpleContainer getScoutArmor() {
        return scoutArmor;
    }

    public SimpleContainer getArmorGear() {
        return armorSlots;
    }

    public SimpleContainer getToolGear() {
        return toolSlots;
    }

    /**
     * A soldier's copy of a piece of gear lost durability: charge the same amount to the original in the hive's slots.
     * If that wears the original out, it breaks. Does nothing if the original is no longer in a gear slot.
     */
    /** With the anvil evolution done, the hive's tools and armor wear half as fast: each point of wear is dropped half the time. */
    private int reducedWear(int amount) {
        if (!com.projecthivemind.EvolveTask.ANVIL.doneIn(evolveMask)) {
            return amount;
        }
        int kept = 0;
        for (int i = 0; i < amount; i++) {
            if (this.random.nextBoolean()) {
                kept++;
            }
        }
        return kept;
    }

    public void damageLinked(UUID link, int amount) {
        damageLinked(link, amount, true);
    }

    /** As above; {@code reducible} false charges all of it (the copy broke, so the original is worn out to match). */
    public void damageLinked(UUID link, int amount, boolean reducible) {
        amount = reducible ? reducedWear(amount) : amount;
        if (amount > 0 && !damageLinkedIn(armorSlots, link, amount) && !damageLinkedIn(scoutArmor, link, amount)) {
            damageLinkedIn(toolSlots, link, amount);
        }
    }

    private boolean damageLinkedIn(SimpleContainer container, UUID link, int amount) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!link.equals(HiveEquipment.link(stack))) {
                continue;
            }
            int damage = stack.getDamageValue() + amount;
            if (damage >= stack.getMaxDamage()) {
                container.setItem(i, ItemStack.EMPTY);
                this.level().playSound(null, this.blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.NEUTRAL, 0.8F, 0.8F + this.random.nextFloat() * 0.4F);
            } else {
                stack.setDamageValue(damage);
                container.setChanged();
            }
            return true;
        }
        return false;
    }

    /** Apply a level's stats: full health at the new maximum, and storage resized without losing items. */
    public void setHiveLevel(int newLevel) {
        HiveLevel definition = HiveLevels.get(newLevel);
        this.hiveLevel = definition.level();
        net.minecraft.world.phys.Vec3 spot = this.position();
        this.entityData.set(DATA_LEVEL, hiveLevel);
        this.setPos(spot);
        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(definition.maxHealth());
        this.setHealth(this.getMaxHealth());
        if (storage.getContainerSize() != definition.storageSlots()) {
            HiveStorage resized = new HiveStorage(definition.storageSlots(), this::stackMultiplier);
            for (int i = 0; i < Math.min(storage.getContainerSize(), resized.getContainerSize()); i++) {
                resized.setItem(i, storage.getItem(i));
            }
            this.storage = resized;
        }
    }

    // ---- stay put, never despawn ----

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(net.minecraft.world.entity.Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.WART_BLOCK_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WART_BLOCK_BREAK;
    }

    // ---- persistence ----

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) {
            tag.putUUID(OWNER_TAG, ownerId);
        }
        tag.putInt(LEVEL_TAG, hiveLevel);
        tag.putInt(LOGS_TAG, logsProgress);
        tag.putInt("QuestCoal", coalProgress);
        tag.putInt("QuestBlaze", blazeProgress);
        tag.putBoolean("QuestNether", netherEntered);
        tag.putBoolean("QuestDragon", dragonDefeated);
        tag.put("Teams", teams.save());
        tag.put("SlotConfigs", slotConfigs.save());
        tag.putInt("QuestIron", ironProgress);
        tag.putInt("QuestLowestY", lowestY);
        tag.putInt(KILLS_TAG, kills);
        tag.putInt(AGE_TAG, ageTicks);
        tag.put(FURNACE_TAG, furnace.save(registryAccess()));
        tag.put("HiveBrewing", brewing.save(registryAccess()));
        tag.put("PortalNetwork", portals.save());
        net.minecraft.nbt.ListTag spots = new net.minecraft.nbt.ListTag();
        for (java.util.Map.Entry<UUID, com.projecthivemind.HivemindManager.UnitSpot> entry : unitSpots.entrySet()) {
            CompoundTag spot = new CompoundTag();
            spot.putUUID("Unit", entry.getKey());
            spot.putString("Dimension", entry.getValue().dimension().location().toString());
            spot.putLong("Chunk", entry.getValue().chunk().toLong());
            spots.add(spot);
        }
        tag.put("UnitSpots", spots);
        food.save(tag);
        tag.put("Constructions", constructions.save());
        tag.put("KeptSites", constructions.saveKept());
        tag.put(SCOUT_HAND_TAG, ContainerHelper.saveAllItems(new CompoundTag(), scoutHand.getItems(), registryAccess()));
        tag.put(JUKEBOX_TAG, ContainerHelper.saveAllItems(new CompoundTag(), jukeboxSlot.getItems(), registryAccess()));
        tag.put(FOOD_SLOT_TAG, ContainerHelper.saveAllItems(new CompoundTag(), foodSlot.getItems(), registryAccess()));
        tag.put(TRASH_TAG, trash.save(registryAccess()));
        tag.putLongArray(EXPLORED_TAG, exploredChunks.stream().mapToLong(Long::longValue).toArray());
        tag.put(STORAGE_TAG, storage.save(registryAccess()));
        tag.putLong(EVOLVE_TAG, evolveMask);
        tag.putInt("TotemCooldown", totemCooldown);
        net.minecraft.nbt.ListTag enchants = new net.minecraft.nbt.ListTag();
        for (net.minecraft.resources.ResourceLocation id : unlockedEnchants) {
            enchants.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("UnlockedEnchants", enchants);
        if (glowCenter != null && glowDimension != null) {
            tag.putIntArray("GlowCenter", new int[] {glowCenter.getX(), glowCenter.getY(), glowCenter.getZ()});
            tag.putString("GlowDimension", glowDimension);
            tag.putInt("GlowRadius", glowRadius);
        }
        tag.put(ARMOR_TAG, ContainerHelper.saveAllItems(new CompoundTag(), armorSlots.getItems(), registryAccess()));
        tag.put(SCOUT_ARMOR_TAG, ContainerHelper.saveAllItems(new CompoundTag(), scoutArmor.getItems(), registryAccess()));
        tag.put(TOOLS_TAG, ContainerHelper.saveAllItems(new CompoundTag(), toolSlots.getItems(), registryAccess()));
        tag.putInt(ARMOR_VERSION_TAG, armorVersion);
        tag.putInt(TOOL_VERSION_TAG, toolVersion);

    }

    /**
     * The whole hive as it is now, for the next Heart after this one is destroyed: everything the Heart saves, except what belongs to
     * this body (its health, where units were seen, the tower being built) and its hunger, which starts afresh.
     */
    public CompoundTag snapshotForRebirth() {
        CompoundTag tag = new CompoundTag();
        this.addAdditionalSaveData(tag);
        for (String key : new String[] {"Health", "DeathTime", "HurtTime", "HurtByTimestamp", "Attributes", "active_effects", "UnitSpots", "TowerBuild", "Pos", "Motion"}) {
            tag.remove(key);
        }
        CompoundTag foodKeys = new CompoundTag();
        food.save(foodKeys);
        for (String key : foodKeys.getAllKeys()) {
            tag.remove(key);
        }
        return tag;
    }

    /** A new Heart takes up the hive an earlier one left: its state loaded, at full health, with no member of a team that no longer exists. */
    public void restoreFromRebirth(CompoundTag tag) {
        this.readAdditionalSaveData(tag.copy());
        for (java.util.UUID member : new java.util.ArrayList<>(teams.members(0))) {
            teams.leave(member);
        }
        // Applies the level's health and storage size, and the size of the body.
        this.setHiveLevel(hiveLevel);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(OWNER_TAG)) {
            ownerId = tag.getUUID(OWNER_TAG);
        }
        if (tag.contains(LEVEL_TAG)) {
            hiveLevel = HiveLevels.get(tag.getInt(LEVEL_TAG)).level();
            this.entityData.set(DATA_LEVEL, hiveLevel);
            this.setPos(this.position());
        }
        logsProgress = tag.getInt(LOGS_TAG);
        coalProgress = tag.getInt("QuestCoal");
        blazeProgress = tag.getInt("QuestBlaze");
        netherEntered = tag.getBoolean("QuestNether");
        dragonDefeated = tag.getBoolean("QuestDragon");
        teams.load(tag.getList("Teams", net.minecraft.nbt.Tag.TAG_COMPOUND));
        slotConfigs.load(tag.getList("SlotConfigs", net.minecraft.nbt.Tag.TAG_COMPOUND));
        ironProgress = tag.getInt("QuestIron");
        lowestY = tag.contains("QuestLowestY") ? tag.getInt("QuestLowestY") : Integer.MAX_VALUE;
        kills = tag.getInt(KILLS_TAG);
        ageTicks = tag.getInt(AGE_TAG);
        food.load(tag);
        constructions.load(tag.getList("Constructions", Tag.TAG_COMPOUND));
        constructions.loadKept(tag.getList("KeptSites", Tag.TAG_COMPOUND));
        trash.clearContent();
        if (tag.contains(TRASH_TAG)) {
            trash.load(tag.getCompound(TRASH_TAG), registryAccess());
        }
        foodSlot.clearContent();
        if (tag.contains(FOOD_SLOT_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(FOOD_SLOT_TAG), foodSlot.getItems(), registryAccess());
        }
        scoutHand.clearContent();
        jukeboxSlot.clearContent();
        if (tag.contains(JUKEBOX_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(JUKEBOX_TAG), jukeboxSlot.getItems(), registryAccess());
        }
        if (tag.contains(SCOUT_HAND_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(SCOUT_HAND_TAG), scoutHand.getItems(), registryAccess());
        }
        unitSpots.clear();
        for (net.minecraft.nbt.Tag raw : tag.getList("UnitSpots", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag spot = (CompoundTag) raw;
            net.minecraft.resources.ResourceLocation dimension = net.minecraft.resources.ResourceLocation.tryParse(spot.getString("Dimension"));
            if (spot.hasUUID("Unit") && dimension != null) {
                unitSpots.put(spot.getUUID("Unit"), new com.projecthivemind.HivemindManager.UnitSpot(
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension), new net.minecraft.world.level.ChunkPos(spot.getLong("Chunk"))));
            }
        }
        if (tag.contains("PortalNetwork")) {
            portals.load(tag.getCompound("PortalNetwork"));
        }
        if (tag.contains("HiveBrewing")) {
            brewing.load(tag.getCompound("HiveBrewing"), registryAccess());
        }
        if (tag.contains(FURNACE_TAG)) {
            furnace.load(tag.getCompound(FURNACE_TAG), registryAccess());
        }
        exploredChunks.clear();
        for (long chunk : tag.getLongArray(EXPLORED_TAG)) {
            exploredChunks.add(chunk);
        }
        evolveMask = tag.getLong(EVOLVE_TAG);
        totemCooldown = tag.getInt("TotemCooldown");
        unlockedEnchants.clear();
        net.minecraft.nbt.ListTag savedEnchants = tag.getList("UnlockedEnchants", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < savedEnchants.size(); i++) {
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(savedEnchants.getString(i));
            if (id != null) {
                unlockedEnchants.add(id);
            }
        }
        int[] glow = tag.getIntArray("GlowCenter");
        glowCenter = glow.length == 3 ? new BlockPos(glow[0], glow[1], glow[2]) : null;
        glowDimension = glowCenter != null ? tag.getString("GlowDimension") : null;
        glowRadius = tag.getInt("GlowRadius");
        storage = new HiveStorage(HiveLevels.get(hiveLevel).storageSlots(), this::stackMultiplier);
        if (tag.contains(STORAGE_TAG)) {
            storage.load(tag.getCompound(STORAGE_TAG), registryAccess());
        }
        scoutArmor.clearContent();
        if (tag.contains(SCOUT_ARMOR_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(SCOUT_ARMOR_TAG), scoutArmor.getItems(), registryAccess());
        }
        if (tag.contains(ARMOR_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(ARMOR_TAG), armorSlots.getItems(), registryAccess());
        }
        if (tag.contains(TOOLS_TAG)) {
            ContainerHelper.loadAllItems(tag.getCompound(TOOLS_TAG), toolSlots.getItems(), registryAccess());
        }
        armorVersion = tag.getInt(ARMOR_VERSION_TAG);
        toolVersion = tag.getInt(TOOL_VERSION_TAG);
        // The signatures are not saved: the first look after loading becomes the baseline.
        lastArmorSignature = null;
        lastToolSignature = null;

    }
}
