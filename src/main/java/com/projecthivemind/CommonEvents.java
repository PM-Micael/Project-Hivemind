package com.projecthivemind;

import java.util.Set;

import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.entity.HiveSoldier;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.entity.HiveUnit;
import com.projecthivemind.network.ChooseModePayload;
import com.projecthivemind.network.ClientPayloads;
import com.projecthivemind.network.HiveMenuClickPayload;
import com.projecthivemind.network.BlockActionPayload;
import com.projecthivemind.network.MobActionPayload;
import com.projecthivemind.network.SelectionPayload;
import com.projecthivemind.network.SyncEyesPayload;
import com.projecthivemind.network.SyncHeartHealthPayload;
import com.projecthivemind.network.SyncSightPayload;
import com.projecthivemind.network.SyncActionsPayload;
import com.projecthivemind.network.WeakToolPayload;
import com.projecthivemind.network.OpenHiveMenuPayload;
import com.projecthivemind.network.ReturnToHeartPayload;
import com.projecthivemind.network.BuildTowerPayload;
import com.projecthivemind.network.OpenBookPayload;
import com.projecthivemind.network.OpenSignPayload;
import com.projecthivemind.network.CancelJobPayload;
import com.projecthivemind.network.DropItemPayload;
import com.projecthivemind.network.FocusUnitPayload;
import com.projecthivemind.network.ScoutUsePayload;
import com.projecthivemind.network.SetJobResumePayload;
import com.projecthivemind.network.SyncUnitsPayload;
import com.projecthivemind.network.ViewUnitPayload;
import com.projecthivemind.network.SetCollectorTaskPayload;
import com.projecthivemind.network.SetUnitBehaviorPayload;
import com.projecthivemind.network.SignTextPayload;
import com.projecthivemind.network.ScrollStoragePayload;
import com.projecthivemind.network.ServerPayloads;
import com.projecthivemind.network.SetMenuViewPayload;
import com.projecthivemind.network.SyncHivemindPayload;
import com.projecthivemind.network.ToggleInventoryModePayload;
import com.projecthivemind.network.TradeOffersPayload;
import com.projecthivemind.network.TradePayload;

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
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
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
        registrar.playToServer(OpenHiveMenuPayload.TYPE, OpenHiveMenuPayload.STREAM_CODEC, ServerPayloads::onOpenHiveMenu);
        registrar.playToServer(HiveMenuClickPayload.TYPE, HiveMenuClickPayload.STREAM_CODEC, ServerPayloads::onHiveMenuClick);
        registrar.playToServer(BlockActionPayload.TYPE, BlockActionPayload.STREAM_CODEC, ServerPayloads::onBlockAction);
        registrar.playToServer(SelectionPayload.TYPE, SelectionPayload.STREAM_CODEC, ServerPayloads::onSelection);
        registrar.playToServer(SetMenuViewPayload.TYPE, SetMenuViewPayload.STREAM_CODEC, ServerPayloads::onSetMenuView);
        registrar.playToServer(FocusUnitPayload.TYPE, FocusUnitPayload.STREAM_CODEC, ServerPayloads::onFocusUnit);
        registrar.playToServer(DropItemPayload.TYPE, DropItemPayload.STREAM_CODEC, ServerPayloads::onDropItem);
        registrar.playToServer(SetCollectorTaskPayload.TYPE, SetCollectorTaskPayload.STREAM_CODEC, ServerPayloads::onSetCollectorTask);
        registrar.playToServer(SetUnitBehaviorPayload.TYPE, SetUnitBehaviorPayload.STREAM_CODEC, ServerPayloads::onSetUnitBehavior);
        registrar.playToServer(CancelJobPayload.TYPE, CancelJobPayload.STREAM_CODEC, ServerPayloads::onCancelJob);
        registrar.playToServer(SetJobResumePayload.TYPE, SetJobResumePayload.STREAM_CODEC, ServerPayloads::onSetJobResume);
        registrar.playToServer(ViewUnitPayload.TYPE, ViewUnitPayload.STREAM_CODEC, ServerPayloads::onViewUnit);
        registrar.playToClient(SyncUnitsPayload.TYPE, SyncUnitsPayload.STREAM_CODEC, ClientPayloads::onSyncUnits);
        registrar.playToServer(ScoutUsePayload.TYPE, ScoutUsePayload.STREAM_CODEC, ServerPayloads::onScoutUse);
        registrar.playToServer(SignTextPayload.TYPE, SignTextPayload.STREAM_CODEC, ServerPayloads::onSignText);
        registrar.playToClient(OpenBookPayload.TYPE, OpenBookPayload.STREAM_CODEC, ClientPayloads::onOpenBook);
        registrar.playToClient(OpenSignPayload.TYPE, OpenSignPayload.STREAM_CODEC, ClientPayloads::onOpenSign);
        registrar.playToServer(BuildTowerPayload.TYPE, BuildTowerPayload.STREAM_CODEC, ServerPayloads::onBuildTower);
        registrar.playToServer(ScrollStoragePayload.TYPE, ScrollStoragePayload.STREAM_CODEC, ServerPayloads::onScrollStorage);
        registrar.playToServer(TradePayload.TYPE, TradePayload.STREAM_CODEC, ServerPayloads::onTrade);
        registrar.playToClient(TradeOffersPayload.TYPE, TradeOffersPayload.STREAM_CODEC, ClientPayloads::onTradeOffers);
        registrar.playToClient(SyncHeartHealthPayload.TYPE, SyncHeartHealthPayload.STREAM_CODEC, ClientPayloads::onSyncHeartHealth);
        registrar.playToClient(SyncEyesPayload.TYPE, SyncEyesPayload.STREAM_CODEC, ClientPayloads::onSyncEyes);
        registrar.playToClient(SyncSightPayload.TYPE, SyncSightPayload.STREAM_CODEC, ClientPayloads::onSyncSight);
        registrar.playToServer(MobActionPayload.TYPE, MobActionPayload.STREAM_CODEC, ServerPayloads::onMobAction);
        registrar.playToClient(WeakToolPayload.TYPE, WeakToolPayload.STREAM_CODEC, ClientPayloads::onWeakTool);
        registrar.playToClient(SyncActionsPayload.TYPE, SyncActionsPayload.STREAM_CODEC, ClientPayloads::onSyncActions);
        registrar.playToServer(ToggleInventoryModePayload.TYPE, ToggleInventoryModePayload.STREAM_CODEC, ServerPayloads::onToggleInventoryMode);
        registrar.playToServer(ReturnToHeartPayload.TYPE, ReturnToHeartPayload.STREAM_CODEC, ServerPayloads::onReturnToHeart);
        registrar.playToClient(SyncHivemindPayload.TYPE, SyncHivemindPayload.STREAM_CODEC, ClientPayloads::onSync);
    }

    @SubscribeEvent
    static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.HIVE_HEART.get(), HiveHeart.createHeartAttributes().build());
        event.put(ModEntities.HIVE_SCOUT.get(), HiveScout.createScoutAttributes().build());
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
        if (BLOCKED_SPAWNS.contains(event.getSpawnType()) && HiveArea.contains(event.getLevel(), event.getPos())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }


    /** A hive unit or the Heart took a hit: it costs the hive what it costs a player. */
    @SubscribeEvent
    static void onLivingDamaged(LivingDamageEvent.Post event) {
        HiveHeart heart = event.getEntity() instanceof HiveHeart own ? own
                : event.getEntity() instanceof HiveUnit unit ? unit.findHeart() : null;
        if (heart != null && event.getNewDamage() > 0.0F) {
            heart.food().exhaust(event.getSource().getFoodExhaustion());
        }
    }
    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        HivemindManager.onKill(event.getEntity(), event.getSource());
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
