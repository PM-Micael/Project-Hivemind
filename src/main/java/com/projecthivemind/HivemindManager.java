package com.projecthivemind;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.client.ClientState;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.SyncHivemindPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side rules for the Hivemind progression. All state changes go through here. */
public final class HivemindManager {
    /** Larva is 30% of normal player size (hitbox and eye height). */
    private static final double LARVA_SCALE_MODIFIER = -0.7D;
    private static final double START_CAMERA_HEIGHT = 10.0D;
    private static final float START_CAMERA_PITCH = 25.0F;
    private static final ResourceLocation LARVA_SCALE_ID = ProjectHivemind.id("larva_scale");

    private HivemindManager() {
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
        PacketDistributor.sendToPlayer(player,
                new SyncHivemindPayload(data.stage(), data.hasUnit(UnitKind.WORKER), data.hasUnit(UnitKind.SOLDIER)));
    }

    /** Make the player's attributes and game mode match their saved stage, then tell their client. Safe to call repeatedly. */
    public static void refresh(ServerPlayer player) {
        HivemindStage stage = get(player).stage();
        setLarvaScale(player, stage == HivemindStage.LARVA);
        if (stage == HivemindStage.HIVE && player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }
        sync(player);
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

    /** Larva right-clicked a block: place the Hive Heart next to it and turn the player into the bodyless hivemind. */
    public static void tryPlaceHeart(ServerPlayer player, BlockPos clicked, @Nullable Direction face) {
        if (get(player).stage() != HivemindStage.LARVA) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = clicked.relative(face == null ? Direction.UP : face);
        BlockState existing = level.getBlockState(target);
        if (!existing.canBeReplaced() || !existing.getFluidState().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.no_room"), true);
            return;
        }

        level.setBlock(target, ModBlocks.HIVE_HEART.get().defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, target, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 1.0F, 0.8F);

        set(player, get(player).withStage(HivemindStage.HIVE).withHeart(GlobalPos.of(level.dimension(), target)));
        setLarvaScale(player, false);
        // The player entity doubles as the camera: spectator gives free, collision-less flight and
        // makes it invisible. It is not a body any more, so put it above and behind the heart, looking
        // down at it, so the heart starts in the middle of the view. (The client refines the pitch to
        // match the player's FOV setting.)
        player.setGameMode(GameType.SPECTATOR);
        float yaw = player.getYRot();
        double behind = START_CAMERA_HEIGHT / Math.tan(Math.toRadians(START_CAMERA_PITCH));
        double cameraX = target.getX() + 0.5D + Mth.sin(yaw * Mth.DEG_TO_RAD) * behind;
        double cameraZ = target.getZ() + 0.5D - Mth.cos(yaw * Mth.DEG_TO_RAD) * behind;
        player.teleportTo(level, cameraX, target.getY() + START_CAMERA_HEIGHT, cameraZ, yaw, START_CAMERA_PITCH);
        player.displayClientMessage(Component.translatable("message.projecthivemind.heart_placed"), true);
        sync(player);
    }

    /** Spawn a unit at the Hive Heart, unless the player already has one of that kind. */
    public static void spawnUnit(ServerPlayer player, UnitKind kind) {
        HivemindData data = get(player);
        if (data.stage() != HivemindStage.HIVE || data.heart().isEmpty() || data.hasUnit(kind)) {
            return;
        }
        GlobalPos heart = data.heart().get();
        ServerLevel level = player.server.getLevel(heart.dimension());
        if (level == null) {
            return;
        }

        // Stand each kind on a different side of the heart.
        double x = heart.pos().getX() + 0.5D + (kind == UnitKind.WORKER ? 1.5D : -1.5D);
        double y = heart.pos().getY();
        double z = heart.pos().getZ() + 0.5D;

        Mob unit = kind == UnitKind.WORKER
                ? ModEntities.HIVE_WORKER.get().create(level)
                : ModEntities.HIVE_SOLDIER.get().create(level);
        if (unit == null) {
            return;
        }
        ((HiveUnit) unit).setOwnerId(player.getUUID());
        unit.moveTo(x, y, z, player.getYRot(), 0.0F);
        unit.setPersistenceRequired();
        level.addFreshEntity(unit);

        set(player, data.withUnit(kind, Optional.of(unit.getUUID())));
        sync(player);
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
        UnitKind kind = hiveUnit.kind();
        HivemindData data = get(owner);
        UUID tracked = data.unit(kind).orElse(null);
        if (unit.getUUID().equals(tracked)) {
            set(owner, data.withUnit(kind, Optional.empty()));
            sync(owner);
        }
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
