package com.projecthivemind;

import java.util.ArrayList;
import java.util.List;
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
import com.projecthivemind.network.SyncHivemindPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
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
        HiveInfection.spread(level, heart);
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

    /** The Heart died: the hive collapses. Its items drop, the creep goes, units die, and the owner is a larva again. */
    public static void onHeartDestroyed(ServerLevel level, HiveHeart heart) {
        BlockPos center = heart.blockPosition();
        Containers.dropContents(level, center, heart.getStorage());
        Containers.dropContents(level, center, heart.getArmorGear());
        Containers.dropContents(level, center, heart.getToolGear());
        HiveInfection.clear(level, heart);
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
                Component.translatable("screen.projecthivemind.hive.title")));
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
            oldest.kill();
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
        }
    }

    /** A unit died: free its owner's slot so they can spawn a replacement. */
    public static void onUnitDied(ServerLevel level, Mob unit) {
        if (!(unit instanceof HiveUnit hiveUnit) || hiveUnit.ownerId() == null) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(hiveUnit.ownerId());
        if (owner == null) {
            return;
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
