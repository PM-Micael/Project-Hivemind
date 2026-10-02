package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.client.ClientState;
import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.entity.HiveSoldier;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.menu.HiveMenu;
import com.projecthivemind.network.SyncEyesPayload;
import com.projecthivemind.network.SyncHeartHealthPayload;
import com.projecthivemind.network.SyncHivemindPayload;
import com.projecthivemind.network.SyncUnitsPayload;
import com.projecthivemind.network.SyncSightPayload;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side rules for the Hivemind progression. All state changes go through here. */
public final class HivemindManager {
    /** Larva is 30% of normal player size (hitbox and eye height). */
    private static final double LARVA_SCALE_MODIFIER = -0.7D;
    private static final ResourceLocation LARVA_SCALE_ID = ProjectHivemind.id("larva_scale");
    private static final double START_CAMERA_HEIGHT = 10.0D;
    private static final float START_CAMERA_PITCH = 25.0F;
    /** Chunks kept loaded around the Heart in each direction, so the hive keeps running while the camera roams. */
    private static final int FORCED_CHUNK_RADIUS = 1;

    /** True while this class is changing a player's game mode itself, so it does not treat that as the player's choice. */
    private static boolean applyingMode;

    private HivemindManager() {
    }

    private static void forceMode(ServerPlayer player, GameType mode) {
        applyingMode = true;
        try {
            player.setGameMode(mode);
        } finally {
            applyingMode = false;
        }
    }

    /**
     * Someone (the player, or a command) is changing the game mode of a player in the hive. In the hive the game mode
     * is managed by the mod (spectator for the camera), so instead of applying it, remember it as the player's real
     * mode. That decides whether the creative-inventory swap is offered: only while the real mode is creative.
     *
     * @return true if the change was taken over and the vanilla change should be cancelled
     */
    public static boolean interceptGameModeChange(ServerPlayer player, GameType requested) {
        HivemindData data = get(player);
        if (applyingMode || data.stage() != HivemindStage.HIVE) {
            return false;
        }
        HivemindData updated = data.withPreviousMode(requested);
        if (requested != GameType.CREATIVE) {
            updated = updated.withNormalInventory(false);
        }
        set(player, updated);
        player.displayClientMessage(Component.translatable("message.projecthivemind.mode_remembered", requested.getLongDisplayName()), true);
        refresh(player);
        return true;
    }

    public static HivemindData get(Player player) {
        return player.getData(ModAttachments.HIVEMIND);
    }

    private static void set(ServerPlayer player, HivemindData data) {
        player.setData(ModAttachments.HIVEMIND, data);
    }

    /** Stage of a player, readable from either side. On the client only the local player's stage is known. */
    @Nullable
    public static HivemindStage stageOf(Player player) {
        if (player.level().isClientSide) {
            return ClientState.stage();
        }
        return get(player).stage();
    }

    public static void sync(ServerPlayer player) {
        HivemindData data = get(player);
        PacketDistributor.sendToPlayer(player, new SyncHivemindPayload(data.stage(), data.normalInventory(), data.canSwapInventory()));
    }

    /** Make the player's attributes and game mode match their saved stage, then tell their client. Safe to call repeatedly. */
    public static void refresh(ServerPlayer player) {
        HivemindData data = get(player);
        setLarvaScale(player, data.stage() == HivemindStage.LARVA);
        if (data.stage() == HivemindStage.HIVE) {
            GameType wanted = data.normalInventory() ? GameType.CREATIVE : GameType.SPECTATOR;
            if (player.gameMode.getGameModeForPlayer() != wanted) {
                forceMode(player, wanted);
            }
        }
        sync(player);
    }

    /**
     * Creative players only: swap between the hive and the normal creative inventory, so items can still be spawned
     * in. The server only accepts creative item spawns from a player who really is in creative mode, so this switches
     * the game mode: creative for the normal inventory, spectator for the hive.
     */
    public static void toggleInventoryMode(ServerPlayer player) {
        HivemindData data = get(player);
        if (!data.canSwapInventory()) {
            return;
        }
        set(player, data.withNormalInventory(!data.normalInventory()));
        refresh(player);
    }

    /** How far from a unit the camera stands when the player jumps to it, in blocks. */
    private static final double FOCUS_DISTANCE = 3.0D;

    /**
     * Put the camera 3 blocks from one of the player's units, at its eye height and looking straight at it, keeping the
     * direction the camera was already facing (so the view does not spin round). The tilt is the camera's lowest.
     */
    public static void focusUnit(ServerPlayer player, int unitId) {
        if (get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        if (player.serverLevel().getEntity(unitId) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                && player.getUUID().equals(unit.ownerId())) {
            float yaw = player.getYRot();
            double x = mob.getX() + Mth.sin(yaw * Mth.DEG_TO_RAD) * FOCUS_DISTANCE;
            double z = mob.getZ() - Mth.cos(yaw * Mth.DEG_TO_RAD) * FOCUS_DISTANCE;
            player.teleportTo(player.serverLevel(), x, mob.getEyeY(), z, yaw, 5.0F);
        }
    }

    /** Move the camera back above the Hive Heart, keeping the player's own view angle. Hive stage only. */
    public static void returnToHeart(ServerPlayer player) {
        if (get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        HiveHeart heart = findHeart(player);
        if (heart == null || !(heart.level() instanceof ServerLevel level)) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_heart"), true);
            return;
        }
        float pitch = Mth.clamp(player.getXRot(), 5.0F, 88.0F);
        double behind = START_CAMERA_HEIGHT / Math.tan(Math.toRadians(pitch));
        float yaw = player.getYRot();
        player.teleportTo(level, heart.getX() + Mth.sin(yaw * Mth.DEG_TO_RAD) * behind, heart.getY() + START_CAMERA_HEIGHT,
                heart.getZ() - Mth.cos(yaw * Mth.DEG_TO_RAD) * behind, yaw, pitch);
    }

    public static void choose(ServerPlayer player, boolean hivemind) {
        if (get(player).stage() != HivemindStage.UNCHOSEN) {
            return;
        }
        if (hivemind) {
            set(player, get(player).withStage(HivemindStage.LARVA));
            player.displayClientMessage(Component.translatable("message.projecthivemind.larva_hint"), true);
        } else {
            set(player, get(player).withStage(HivemindStage.STEVE));
        }
        refresh(player);
    }

    // ---- the Hive Heart ----

    /** The player's Hive Heart entity, or null if they have none or it is not loaded. */
    @Nullable
    public static HiveHeart findHeart(ServerPlayer player) {
        HivemindData data = get(player);
        if (data.heart().isEmpty() || data.heartId().isEmpty()) {
            return null;
        }
        ServerLevel level = player.server.getLevel(data.heart().get().dimension());
        if (level != null && level.getEntity(data.heartId().get()) instanceof HiveHeart heart && heart.isAlive()) {
            return heart;
        }
        return null;
    }

    /** Larva right-clicked a block: plant the Hive Heart next to it and turn the player into the bodyless hivemind. */
    public static void tryPlaceHeart(ServerPlayer player, BlockPos clicked, @Nullable Direction face) {
        HivemindData data = get(player);
        if (data.stage() != HivemindStage.LARVA) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = clicked.relative(face == null ? Direction.UP : face);
        BlockState existing = level.getBlockState(target);
        if (!existing.canBeReplaced() || !existing.getFluidState().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_room"), true);
            return;
        }

        HiveLevel hiveLevel = HiveLevels.get(1);
        HiveHeart heart = ModEntities.HIVE_HEART.get().create(level);
        if (heart == null) {
            return;
        }
        heart.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.0F, 0.0F);
        heart.setOwnerId(player.getUUID());
        heart.setHiveLevel(hiveLevel.level());
        heart.setPersistenceRequired();

        setHeartChunksForced(level, target, true);
        level.addFreshEntity(heart);
        level.playSound(null, target, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 1.0F, 0.8F);

        set(player, data.withStage(HivemindStage.HIVE)
                .withHeart(GlobalPos.of(level.dimension(), target), heart.getUUID())
                .withPreviousMode(player.gameMode.getGameModeForPlayer()));
        setLarvaScale(player, false);

        // The player entity doubles as the camera: spectator gives free, collision-less flight and
        // makes it invisible. It is not a body any more, so put it above and behind the heart, looking
        // down at it, so the heart starts in the middle of the view. (The player can re-angle it later.)
        forceMode(player, GameType.SPECTATOR);
        float yaw = player.getYRot();
        double behind = START_CAMERA_HEIGHT / Math.tan(Math.toRadians(START_CAMERA_PITCH));
        double cameraX = target.getX() + 0.5D + Mth.sin(yaw * Mth.DEG_TO_RAD) * behind;
        double cameraZ = target.getZ() + 0.5D - Mth.cos(yaw * Mth.DEG_TO_RAD) * behind;
        player.teleportTo(level, cameraX, target.getY() + START_CAMERA_HEIGHT, cameraZ, yaw, START_CAMERA_PITCH);
        player.displayClientMessage(Component.translatable("message.projecthivemind.heart_placed"), true);
        sync(player);
    }

    /** The Heart died: the hive collapses. Its items drop, units die, and the owner is a larva again. */
    public static void onHeartDestroyed(ServerLevel level, HiveHeart heart) {
        BlockPos center = heart.blockPosition();
        Containers.dropContents(level, center, heart.getStorage());
        Containers.dropContents(level, center, heart.getArmorGear());
        Containers.dropContents(level, center, heart.getToolGear());
        Containers.dropContents(level, center, heart.furnace().items());
        Containers.dropContents(level, center, heart.scoutHand());
        Containers.dropContents(level, center, heart.foodSlot());
        setHeartChunksForced(level, center, false);

        UUID ownerId = heart.ownerId();
        ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return;
        }
        HivemindData data = get(owner);
        for (UUID unitId : data.allUnits()) {
            for (ServerLevel unitLevel : level.getServer().getAllLevels()) {
                Entity unit = unitLevel.getEntity(unitId);
                if (unit != null) {
                    unit.discard();
                }
            }
        }
        set(owner, data.collapsed());
        forceMode(owner, data.previousMode().orElse(level.getServer().getDefaultGameType()));
        owner.displayClientMessage(Component.translatable("message.projecthivemind.heart_destroyed"), false);
        refresh(owner);
    }

    private static void setHeartChunksForced(ServerLevel level, BlockPos heart, boolean forced) {
        ChunkPos center = new ChunkPos(heart);
        for (int dx = -FORCED_CHUNK_RADIUS; dx <= FORCED_CHUNK_RADIUS; dx++) {
            for (int dz = -FORCED_CHUNK_RADIUS; dz <= FORCED_CHUNK_RADIUS; dz++) {
                level.setChunkForced(center.x + dx, center.z + dz, forced);
            }
        }
    }

    // ---- the hive menu ----

    /** What a job is, in words, for the unit's page of the hive menu. Empty if there is none to speak of. */
    private static net.minecraft.network.chat.Component describeJob(ServerLevel level, HiveHeart heart, UnitAction job) {
        switch (job.kind()) {
            case DIG:
                if (job.pos() != null) {
                    return Component.translatable("job.projecthivemind.mine", level.getBlockState(job.pos()).getBlock().getName());
                }
                break;
            case ATTACK:
                if (job.target() != null) {
                    net.minecraft.world.entity.Entity target = level.getEntity(job.target());
                    return Component.translatable("job.projecthivemind.attack", target == null ? Component.translatable("job.projecthivemind.a_mob") : target.getName());
                }
                break;
            case BUILD:
                return Component.translatable(heart.activeBuild() != null && heart.activeBuild().plan().direction() == com.projecthivemind.build.TowerDirection.DOWN
                        ? "job.projecthivemind.build_shaft" : "job.projecthivemind.build_tower");
            default:
                break;
        }
        return Component.empty();
    }

    /** The player confirmed cancelling a unit's job. Only valid with the hive menu open, for the player's own units. */
    public static void cancelUnitJob(ServerPlayer player, int unitId) {
        if (!(player.containerMenu instanceof HiveMenu)) {
            return;
        }
        if (player.serverLevel().getEntity(unitId) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                && player.getUUID().equals(unit.ownerId())) {
            unit.cancelJob();
            HiveHeart heart = findHeart(player);
            if (heart != null) {
                HiveActions.syncActions(player, heart);
            }
        }
    }

    /** The player ticked or unticked "go back to this job" on a unit's page. Only valid with the hive menu open. */
    public static void setJobResume(ServerPlayer player, int unitId, boolean resume) {
        if (!(player.containerMenu instanceof HiveMenu)) {
            return;
        }
        if (player.serverLevel().getEntity(unitId) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                && player.getUUID().equals(unit.ownerId())) {
            unit.setResumeJob(resume);
        }
    }

    /** A collector's planting tasks, for the unit pages; everything else has none. */
    private static SyncUnitsPayload.Task taskOf(Mob mob) {
        if (mob instanceof HiveCollector collector) {
            return new SyncUnitsPayload.Task(itemName(collector.task(HiveCollector.PlantKind.CROP).item()),
                    List.copyOf(collector.task(HiveCollector.PlantKind.CROP).spots()),
                    itemName(collector.task(HiveCollector.PlantKind.SAPLING).item()),
                    List.copyOf(collector.task(HiveCollector.PlantKind.SAPLING).spots()));
        }
        return SyncUnitsPayload.Task.NONE;
    }

    private static String itemName(@Nullable net.minecraft.world.item.Item item) {
        return item == null ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * The player set up one of a collector's planting tasks. {@code op} is 0 to choose the item (an item name, empty for
     * none), 1 to add a soil block to plant on, 2 to clear all the blocks, 3 to remove one; add 10 for saplings instead of
     * crops. Only for the player's own collectors, and a block only inside the hive area.
     */
    public static void setCollectorTask(ServerPlayer player, int unitId, int op, String item, BlockPos pos) {
        if (!(player.serverLevel().getEntity(unitId) instanceof HiveCollector collector) || !collector.isAlive()
                || !player.getUUID().equals(collector.ownerId())) {
            return;
        }
        HiveHeart heart = findHeart(player);
        HiveCollector.PlantKind kind = op >= 10 ? HiveCollector.PlantKind.SAPLING : HiveCollector.PlantKind.CROP;
        switch (op % 10) {
            case 0 -> {
                net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(item);
                collector.setPlantItem(kind, id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null));
            }
            case 1 -> {
                if (heart == null || !player.serverLevel().isLoaded(pos) || !HiveArea.containsXZ(heart, pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
                    player.displayClientMessage(Component.translatable("message.projecthivemind.plant_outside"), true);
                } else {
                    collector.addPlantSpot(kind, pos);
                }
            }
            case 2 -> collector.clearPlantSpots(kind);
            case 3 -> collector.removePlantSpot(kind, pos);
            default -> {
            }
        }
        sendUnits(player);
    }

    /** Tell the owner who their units are, for the unit pages of the hive menu. */
    public static void sendUnits(ServerPlayer owner) {
        ServerLevel level = owner.serverLevel();
        List<SyncUnitsPayload.Entry> entries = new ArrayList<>();
        for (UnitKind kind : UnitKind.values()) {
            for (UUID id : get(owner).units().getOrDefault(kind, List.of())) {
                if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && entries.size() < SyncUnitsPayload.MAX_ENTRIES) {
                    HiveUnit unit = mob instanceof HiveUnit found ? found : null;
                    UnitAction job = unit == null ? null : unit.job();
                    HiveHeart heart = findHeart(owner);
                    Component text = job == null || heart == null ? Component.empty() : describeJob(level, heart, job);
                    boolean paused = job != null && !job.equals(unit.action());
                    entries.add(new SyncUnitsPayload.Entry(mob.getId(), kind.ordinal(), text,
                            SyncUnitsPayload.Entry.flags(paused, unit == null || unit.resumeJob()),
                            new SyncUnitsPayload.Vitals(mob.getHealth(), mob.getMaxHealth()), taskOf(mob)));
                }
            }
        }
        PacketDistributor.sendToPlayer(owner, new SyncUnitsPayload(entries));
    }

    public static void openMenu(ServerPlayer player) {
        if (get(player).stage() != HivemindStage.HIVE) {
            return;
        }
        HiveHeart heart = findHeart(player);
        if (heart == null) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_heart"), true);
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, ignored) -> HiveMenu.create(containerId, inventory, heart, player),
                Component.translatable("screen.projecthivemind.hive.title")), buf -> {
            buf.writeVarInt(heart.getStorage().getContainerSize());
            // The built-in furnace is not offered in the menu any more.
            buf.writeBoolean(false);
        });
        sendUnits(player);
    }

    // ---- units ----

    /**
     * Every interval, from the Heart: make the units the hive is owed. For each kind below its cap, one new unit.
     * For a kind at its cap, if its oldest unit was made before the gear last changed, replace it with a fresh one
     * that has the current gear: so after a swap the hive turns its units over, one per interval, oldest first.
     * Collectors use no gear and are only ever topped up.
     */
    public static void tickUnitSpawning(HiveHeart heart) {
        if (heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null) {
            return;
        }
        HivemindData data = get(owner);
        boolean theirHeart = data.heartId().isPresent() && data.heartId().get().equals(heart.getUUID());
        if (data.stage() != HivemindStage.HIVE || !theirHeart) {
            return;
        }

        heart.refreshGearVersions();
        for (UnitKind kind : UnitKind.values()) {
            spawnOrRefresh(owner, heart, kind);
        }
    }

    private static void spawnOrRefresh(ServerPlayer owner, HiveHeart heart, UnitKind kind) {
        int cap = HiveLevels.get(heart.hiveLevel()).cap(kind);
        if (cap <= 0) {
            return;
        }
        if (get(owner).count(kind) < cap) {
            createUnit(owner, heart, kind);
            return;
        }
        // At the cap: replace the oldest unit if it is out of date.
        Mob oldest = oldestOutOfDate(owner, heart, kind);
        if (oldest != null) {
            // The hive itself is ending this unit, to make a new one with the new gear: that costs the Heart nothing.
            replacingUnit = true;
            try {
                oldest.kill();
            } finally {
                replacingUnit = false;
            }
            // The kill frees the slot through the normal death handling; only replace it if that happened.
            if (get(owner).count(kind) < cap) {
                createUnit(owner, heart, kind);
            }
        }
    }

    /**
     * The oldest unit of this kind, if it was made before the gear last changed. Units are tracked oldest first, and
     * the oldest is the most out of date. Collectors use no gear and are never out of date.
     */
    @Nullable
    private static Mob oldestOutOfDate(ServerPlayer owner, HiveHeart heart, UnitKind kind) {
        List<UUID> units = get(owner).units().getOrDefault(kind, List.of());
        if (kind == UnitKind.COLLECTOR || units.isEmpty()) {
            return null;
        }
        Mob oldest = findUnit(owner, units.get(0));
        return oldest instanceof HiveUnit unit && unit.gearVersion() < heart.gearVersionFor(kind) ? oldest : null;
    }

    /**
     * What the next interval will do for this kind of unit, for the hive menu to show: {@link HiveMenu#STATUS_SPAWNING}
     * below the cap; {@link HiveMenu#STATUS_REFRESHING} at the cap when a gear change is waiting to be noticed or the
     * oldest unit is already out of date; otherwise {@link HiveMenu#STATUS_IDLE}.
     */
    public static int spawnStatus(ServerPlayer owner, HiveHeart heart, UnitKind kind) {
        int cap = HiveLevels.get(heart.hiveLevel()).cap(kind);
        if (get(owner).count(kind) < cap) {
            return HiveMenu.STATUS_SPAWNING;
        }
        boolean refreshing = heart.gearChangePending(kind) || oldestOutOfDate(owner, heart, kind) != null;
        return refreshing ? HiveMenu.STATUS_REFRESHING : HiveMenu.STATUS_IDLE;
    }

    @Nullable
    private static Mob findUnit(ServerPlayer owner, UUID id) {
        for (ServerLevel level : owner.server.getAllLevels()) {
            if (level.getEntity(id) instanceof Mob unit && unit.isAlive()) {
                return unit;
            }
        }
        return null;
    }

    /** Make one unit at the Heart, with no cap checks: callers have already decided it should exist. */
    private static void createUnit(ServerPlayer player, HiveHeart heart, UnitKind kind) {
        ServerLevel level = (ServerLevel) heart.level();

        // Stand each kind on a different side of the heart.
        double x = heart.getX() + (kind == UnitKind.WORKER ? 1.5D : kind == UnitKind.SOLDIER ? -1.5D : 0.0D);
        double z = heart.getZ() + (kind == UnitKind.COLLECTOR ? 1.5D : kind == UnitKind.SCOUT ? -1.5D : 0.0D);

        Mob unit = switch (kind) {
            case SCOUT -> ModEntities.HIVE_SCOUT.get().create(level);
            case WORKER -> ModEntities.HIVE_WORKER.get().create(level);
            case SOLDIER -> ModEntities.HIVE_SOLDIER.get().create(level);
            case COLLECTOR -> ModEntities.HIVE_COLLECTOR.get().create(level);
        };
        if (unit == null) {
            return;
        }
        ((HiveUnit) unit).setOwnerId(player.getUUID());
        ((HiveUnit) unit).setGearVersion(heart.gearVersionFor(kind));
        if (unit instanceof HiveCollector collector) {
            collector.setHeartId(heart.getUUID());
        } else if (unit instanceof HiveSoldier soldier) {
            // Soldiers spawn wearing and wielding copies of whatever is in the hive's gear slots right now.
            soldier.setHeartId(heart.getUUID());
            HiveEquipment.equipSoldier(soldier, heart);
        } else if (unit instanceof HiveWorker worker) {
            // Workers pick a tool from the hive's slots when they have a job to do.
            worker.setHeartId(heart.getUUID());
        } else if (unit instanceof HiveScout scout) {
            scout.setHeartId(heart.getUUID());
        }
        unit.moveTo(x, heart.getY(), z, player.getYRot(), 0.0F);
        unit.setPersistenceRequired();
        level.addFreshEntity(unit);

        // Summoning a unit costs the hive 2 saturation (a saturation point is 4 exhaustion, as in the game's own food).
        heart.food().payForUnit();
        set(player, get(player).withUnit(kind, unit.getUUID()));
        sync(player);
    }

    /** Once a second, from the Heart: keep the owner's client told which blocks have units working on them. */
    public static void tickActionSync(HiveHeart heart) {
        if (heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner != null && get(owner).stage() == HivemindStage.HIVE) {
            HiveActions.syncActions(owner, heart);
            // The names of the units (Soldier 1, Soldier 2...) are shown over them in the world, so the list is kept up to date.
            sendUnits(owner);
            // Only bother looking for threats if the soldiers are set to respond to them.
            heart.setThreats(anySoldierRespondsToThreats(owner) ? findThreats(owner, heart) : Set.of());
        }
    }

    /**
     * Several times a second, from the Heart: work out what the hive can see, keep the eyes for the workers' job
     * scans, and tell the owner's client which mobs are in sight so it can hide the rest.
     */
    public static void tickSight(HiveHeart heart) {
        if (heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null || get(owner).stage() != HivemindStage.HIVE) {
            return;
        }
        ServerLevel level = (ServerLevel) heart.level();
        List<HiveSight.Eye> eyes = HiveSight.eyes(level, owner, heart);
        // The client fogs terrain by these, so it needs them whenever a unit has moved.
        if (!eyes.equals(heart.sightEyes())) {
            List<SyncEyesPayload.EyePoint> points = new ArrayList<>();
            for (HiveSight.Eye eye : eyes) {
                points.add(new SyncEyesPayload.EyePoint(eye.position().x, eye.position().y, eye.position().z, (float) eye.radius()));
            }
            PacketDistributor.sendToPlayer(owner, new SyncEyesPayload(points));
        }
        heart.setSightEyes(eyes);

        Set<Integer> visible = HiveSight.visibleMobs(level, eyes);
        if (!visible.equals(heart.syncedSight())) {
            heart.setSyncedSight(visible);
            PacketDistributor.sendToPlayer(owner, new SyncSightPayload(List.copyOf(visible)));
        }
    }

    // ---- natural spawning around the camera ----

    /** How far from the camera chunks take part in spawning, in blocks: vanilla's 128. */
    private static final double SPAWN_DISTANCE_SQR = 16384.0D;
    private static final int SPAWN_CHUNK_RADIUS = 8;
    /** The game scales its mob caps by this: the chunks in a 17 by 17 square. */
    private static final int SPAWN_CAP_DIVISOR = 289;

    /**
     * Natural mob spawning around the hivemind's camera. The game only spawns mobs around players that are not in
     * spectator mode, and the bodyless hivemind always is, so without this the world would go quiet: no hostile mobs
     * to fight (or to survive a night against), no animals. This runs the game's own spawning, with its own rules,
     * caps and light levels, on the chunks within 128 blocks of the camera, once a tick like the game does.
     * (Spawns inside the hive area are still refused, see CommonEvents.)
     */
    public static void tickNaturalSpawning(HiveHeart heart) {
        if (heart.ownerId() == null || heart.getServer() == null || !(heart.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        // Only when the game would not: any other player mode already gets the game's own spawning around the player.
        if (owner == null || !owner.isSpectator() || get(owner).stage() != HivemindStage.HIVE || owner.serverLevel() != level
                || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return;
        }
        boolean enemies = level.getServer().isSpawningMonsters();
        boolean friendlies = level.getServer().isSpawningAnimals();
        if (!enemies && !friendlies) {
            return;
        }

        ChunkPos center = owner.chunkPosition();
        List<LevelChunk> chunks = new ArrayList<>();
        for (int dx = -SPAWN_CHUNK_RADIUS; dx <= SPAWN_CHUNK_RADIUS; dx++) {
            for (int dz = -SPAWN_CHUNK_RADIUS; dz <= SPAWN_CHUNK_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                double distanceX = pos.getMiddleBlockX() - owner.getX();
                double distanceZ = pos.getMiddleBlockZ() - owner.getZ();
                if (distanceX * distanceX + distanceZ * distanceZ >= SPAWN_DISTANCE_SQR) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk != null && chunk.getFullStatus() == FullChunkStatus.ENTITY_TICKING
                        && level.isNaturalSpawningAllowed(pos) && level.getWorldBorder().isWithinBounds(pos)) {
                    chunks.add(chunk);
                }
            }
        }
        if (chunks.isEmpty()) {
            return;
        }


        // The game's own per-player mob cap says "no" when no player is close enough, which is always so for a
        // spectator. So the caps are worked out here instead: the same totals, counted over the whole world.
        Object2IntOpenHashMap<MobCategory> counts = new Object2IntOpenHashMap<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
                continue;
            }
            MobCategory category = entity.getType().getCategory();
            if (category != MobCategory.MISC) {
                counts.addTo(category, 1);
            }
        }
        // Passive animals and the like only get their turn every 20 seconds, as in the game.
        boolean persistent = level.getGameTime() % 400L == 0L;
        Util.shuffle(chunks, level.random);
        // The game's spawning looks for the nearest player who is not a spectator, and does nothing without one. So the
        // camera passes for a normal player for the length of this loop, and is put back before anything else can
        // look. The mode is set directly: no packet goes to the client and no event fires.
        GameType realMode = owner.gameMode.gameModeForPlayer;
        owner.gameMode.gameModeForPlayer = GameType.SURVIVAL;
        try {
            for (LevelChunk chunk : chunks) {
                for (MobCategory category : MobCategory.values()) {
                    if (category == MobCategory.MISC || (category.isFriendly() && !friendlies) || (!category.isFriendly() && !enemies)
                            || (category.isPersistent() && !persistent)) {
                        continue;
                    }
                    int cap = category.getMaxInstancesPerChunk() * chunks.size() / SPAWN_CAP_DIVISOR;
                    if (counts.getInt(category) >= cap) {
                        continue;
                    }
                    // The game's extra check against a biome's spawn budget (soul sand valleys and the like) is skipped.
                    NaturalSpawner.spawnCategoryForChunk(category, level, chunk, (type, pos, spawnChunk) -> true, (mob, spawnChunk) -> {
                        counts.addTo(category, 1);
                    });
                }
            }
        } finally {
            owner.gameMode.gameModeForPlayer = realMode;
        }
    }

    // ---- the level-up quest ----

    /**
     * A living thing died. If a hive unit killed it, and it was not one of the hive's own, the unit's hive gets the
     * kill for its quest.
     */
    public static void onKill(LivingEntity victim, DamageSource source) {
        Entity killer = source.getEntity();
        if (killer instanceof HiveUnit unit && victim instanceof Mob && !(victim instanceof HiveUnit) && !(victim instanceof HiveHeart)) {
            HiveHeart heart = unit.findHeart();
            if (heart != null) {
                heart.addKill();
            }
        }
    }

    /**
     * Once a second, from the Heart: work out how far the hive is through its level-up quest, and level it up when
     * every part is done. Logs: the most the hive's storage has held at once counts, and it never goes back down.
     * Exploring: every chunk a unit of the hive has stood in counts once, except the chunks of the hive area itself.
     * Kills and survival are counted as they happen (see {@link #onUnitKill} and the age added here).
     */
    public static void tickQuests(HiveHeart heart) {
        HiveLevel.Quest quest = HiveLevels.get(heart.hiveLevel()).quest();
        if (quest == null || heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null || get(owner).stage() != HivemindStage.HIVE) {
            return;
        }
        ServerLevel level = (ServerLevel) heart.level();

        // Time only passes for the quest while its owner is here to live through it.
        heart.addAge(HiveHeart.QUEST_INTERVAL_TICKS);

        int logs = 0;
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (stack.is(ItemTags.LOGS)) {
                logs += stack.getCount();
            }
        }
        heart.setLogsProgress(Math.max(heart.logsProgress(), Math.min(logs, quest.logs())));

        AABB area = HiveArea.areaBox(level, heart);
        int areaMinX = Mth.floor(area.minX) >> 4;
        int areaMaxX = Mth.floor(area.maxX - 1.0E-4D) >> 4;
        int areaMinZ = Mth.floor(area.minZ) >> 4;
        int areaMaxZ = Mth.floor(area.maxZ - 1.0E-4D) >> 4;
        for (UUID id : get(owner).allUnits()) {
            if (level.getEntity(id) instanceof Mob unit && unit.isAlive()) {
                ChunkPos chunk = unit.chunkPosition();
                boolean inHiveArea = chunk.x >= areaMinX && chunk.x <= areaMaxX && chunk.z >= areaMinZ && chunk.z <= areaMaxZ;
                if (!inHiveArea) {
                    heart.exploredChunks().add(chunk.toLong());
                }
            }
        }

        if (heart.logsProgress() >= quest.logs() && heart.exploredChunkCount() >= quest.chunks()
                && heart.kills() >= quest.kills() && heart.ageTicks() >= quest.survivalTicks()) {
            levelUp(heart, owner);
        }
    }

    /** The quest is done: the hive moves up one level and gets everything the new level has. */
    private static void levelUp(HiveHeart heart, ServerPlayer owner) {
        ServerLevel level = (ServerLevel) heart.level();
        heart.setHiveLevel(heart.hiveLevel() + 1);
        // The storage is a new, bigger container now: a menu that is still open would be looking at the old one.
        if (owner.containerMenu != owner.inventoryMenu) {
            owner.closeContainer();
        }
        level.playSound(null, heart.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 0.8F);
        owner.sendSystemMessage(Component.translatable("message.projecthivemind.level_up", heart.hiveLevel()));
    }

    /**
     * Tell the owner the Heart's health for the health bar: whenever it changes, and once a second regardless, so a
     * client that has just joined gets it too.
     */
    public static void tickHealthSync(HiveHeart heart) {
        if (heart.ownerId() == null || heart.getServer() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner == null || get(owner).stage() != HivemindStage.HIVE) {
            return;
        }
        float health = heart.getHealth();
        int armor = heart.getArmorValue();
        int foodLevel = heart.food().foodLevel();
        if (health != heart.syncedHealth() || armor != heart.syncedArmor() || foodLevel != heart.syncedFood() || heart.tickCount % 20 == 0) {
            heart.setSyncedFood(foodLevel);
            heart.setSyncedArmor(armor);
            heart.setSyncedHealth(health);
            PacketDistributor.sendToPlayer(owner, new SyncHeartHealthPayload(health, heart.getMaxHealth(), armor, foodLevel,
                    HiveLevels.get(heart.hiveLevel()).infectionRadius(), heart.blockPosition()));
        }
    }




    /** How far around each hive member to look for mobs that are coming for it. */
    private static final double THREAT_SCAN_RADIUS = 24.0D;

    /**
     * The mobs that are hostile to the hive right now: any that recently hurt the Heart or one of the owner's units,
     * plus any that currently have one of them as their target. Players are not mobs, so they are never threats.
     */
    private static Set<UUID> findThreats(ServerPlayer owner, HiveHeart heart) {
        ServerLevel level = (ServerLevel) heart.level();
        List<LivingEntity> hive = new ArrayList<>();
        hive.add(heart);
        for (UUID id : get(owner).allUnits()) {
            if (level.getEntity(id) instanceof LivingEntity unit && unit.isAlive()) {
                hive.add(unit);
            }
        }

        Set<UUID> threats = new HashSet<>();
        for (LivingEntity member : hive) {
            // The game forgets who hurt an entity after about five seconds, which is what "recently" means here.
            if (member.getLastHurtByMob() instanceof Mob attacker && isOutsider(attacker)) {
                threats.add(attacker.getUUID());
            }
            for (Mob mob : level.getEntitiesOfClass(Mob.class, member.getBoundingBox().inflate(THREAT_SCAN_RADIUS),
                    mob -> isOutsider(mob) && isHiveMember(mob.getTarget()))) {
                threats.add(mob.getUUID());
            }
        }
        return threats;
    }

    private static boolean isHiveMember(@Nullable Entity entity) {
        return entity instanceof HiveUnit || entity instanceof HiveHeart;
    }

    private static boolean isOutsider(Mob mob) {
        return mob.isAlive() && !isHiveMember(mob);
    }

    /** True if any of the owner's soldiers is set to respond to mobs that threaten the hive. */
    private static boolean anySoldierRespondsToThreats(ServerPlayer owner) {
        ServerLevel level = owner.serverLevel();
        for (UUID id : get(owner).allUnits()) {
            if (level.getEntity(id) instanceof HiveSoldier soldier && soldier.isAlive() && soldier.behavior().threats()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The player edited one unit's behaviour settings on its page of the hive menu. Only valid with the hive menu open,
     * and only for the player's own units.
     */
    public static void setUnitBehavior(ServerPlayer player, int unitId, int flags, List<Integer> radii) {
        if (!(player.containerMenu instanceof HiveMenu)) {
            return;
        }
        if (player.serverLevel().getEntity(unitId) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit
                && player.getUUID().equals(unit.ownerId())) {
            // Every kind's settings clamp a bad radius, so a bad client cannot set a silly one.
            int[] padded = new int[4];
            for (int i = 0; i < Math.min(4, radii.size()); i++) {
                padded[i] = radii.get(i);
            }
            unit.setBehavior(flags, padded);
        }
    }

    /** The client reports which units the player has selected; those follow orders only, not the hive's defaults. */
    public static void setSelection(ServerPlayer player, List<Integer> unitIds) {
        HiveHeart heart = findHeart(player);
        if (heart != null) {
            heart.setSelectedUnits(Set.copyOf(unitIds));
        }
    }


    /** A unit died: free its owner's slot so they can spawn a replacement. */
    /** What it costs the Heart when one of its units dies: 4 health points, 2 hearts. */
    public static final float UNIT_DEATH_DAMAGE = 4.0F;

    /** True while the hive is itself ending a unit to replace it (so that death is not a loss). Server thread only. */
    private static boolean replacingUnit;

    public static void onUnitDied(ServerLevel level, Mob unit) {
        if (!(unit instanceof HiveUnit hiveUnit) || hiveUnit.ownerId() == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(hiveUnit.ownerId());
        if (owner == null) {
            return;
        }
        // Losing a unit hurts the hive: the Heart loses 2 hearts. Armor and invulnerability do not count, it is exactly that.
        // (When the Heart itself was destroyed it is not alive, and its units dying with it cost it nothing.)
        HiveHeart heart = hiveUnit.findHeart();
        if (heart != null && heart.isAlive() && !replacingUnit) {
            heart.invulnerableTime = 0;
            heart.hurt(level.damageSources().genericKill(), UNIT_DEATH_DAMAGE);
        }
        set(owner, get(owner).withoutUnit(hiveUnit.kind(), unit.getUUID()));
        sync(owner);
    }

    private static void setLarvaScale(ServerPlayer player, boolean larva) {
        AttributeInstance scale = player.getAttribute(Attributes.SCALE);
        if (scale == null) {
            return;
        }
        if (larva && !scale.hasModifier(LARVA_SCALE_ID)) {
            scale.addPermanentModifier(new AttributeModifier(LARVA_SCALE_ID, LARVA_SCALE_MODIFIER, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else if (!larva) {
            scale.removeModifier(LARVA_SCALE_ID);
        }
    }
}
