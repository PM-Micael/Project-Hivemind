package com.projecthivemind;

import java.util.Set;

import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveSoldier;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.ChooseModePayload;
import com.projecthivemind.network.ClientPayloads;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.OpenHiveMenuPayload;
import com.projecthivemind.network.ServerPayloads;
import com.projecthivemind.network.SpawnUnitPayload;
import com.projecthivemind.network.SyncHivemindPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ProjectHivemind.MODID)
public final class CommonEvents {
    /** Spawns the infection stops. Commands, spawn eggs and the like still work, so builders can place mobs on purpose. */
    private static final Set<MobSpawnType> BLOCKED_SPAWNS = Set.of(
            MobSpawnType.NATURAL,
            MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.SPAWNER,
            MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT);

    private CommonEvents() {
    }

    // ---- mod bus: registration ----

    @SubscribeEvent
    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ChooseModePayload.TYPE, ChooseModePayload.STREAM_CODEC, ServerPayloads::onChooseMode);
        registrar.playToServer(SpawnUnitPayload.TYPE, SpawnUnitPayload.STREAM_CODEC, ServerPayloads::onSpawnUnit);
        registrar.playToServer(OpenHiveMenuPayload.TYPE, OpenHiveMenuPayload.STREAM_CODEC, ServerPayloads::onOpenHiveMenu);
        registrar.playToServer(HiveMenuClickPayload.TYPE, HiveMenuClickPayload.STREAM_CODEC, ServerPayloads::onHiveMenuClick);
        registrar.playToServer(ToggleInventoryModePayload.TYPE, ToggleInventoryModePayload.STREAM_CODEC, ServerPayloads::onToggleInventoryMode);
        registrar.playToClient(SyncHivemindPayload.TYPE, SyncHivemindPayload.STREAM_CODEC, ClientPayloads::onSync);
    }

    @SubscribeEvent
    static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.HIVE_HEART.get(), HiveHeart.createHeartAttributes().build());
        event.put(ModEntities.HIVE_WORKER.get(), Skeleton.createAttributes().build());
        event.put(ModEntities.HIVE_SOLDIER.get(), HiveSoldier.createHiveAttributes().build());
        event.put(ModEntities.HIVE_COLLECTOR.get(), HiveCollector.createCollectorAttributes().build());
    }

    // ---- game bus ----

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HivemindManager.refresh(player);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HivemindManager.refresh(player);
        }
    }

    /** As a larva, right-clicking any block plants the Hive Heart instead of the normal interaction. */
    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (HivemindManager.stageOf(player) != HivemindStage.LARVA) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() == InteractionHand.MAIN_HAND && player instanceof ServerPlayer serverPlayer) {
            HivemindManager.tryPlaceHeart(serverPlayer, event.getPos(), event.getFace());
        }
    }

    /** In the hive the game mode is managed by the mod; a requested change is remembered instead of applied. */
    @SubscribeEvent
    static void onChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && HivemindManager.interceptGameModeChange(player, event.getNewGameMode())) {
            event.setCanceled(true);
        }
    }

    /** Nothing spawns inside an infected area. */
    @SubscribeEvent
    static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (BLOCKED_SPAWNS.contains(event.getSpawnType()) && HiveInfection.isInfected(event.getLevel(), event.getPos())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (mob instanceof HiveHeart heart) {
            HivemindManager.onHeartDestroyed(level, heart);
        } else if (mob instanceof HiveUnit) {
            HivemindManager.onUnitDied(level, mob);
        }
    }
}
