package com.projecthivemind;

import java.util.Set;

import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.entity.HiveFeeder;
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
import com.projecthivemind.network.WeakStairsPayload;
import com.projecthivemind.network.WeakToolPayload;
import com.projecthivemind.network.OpenHiveMenuPayload;
import com.projecthivemind.network.SetUnitTeamPayload;
import com.projecthivemind.network.FocusTeamPayload;
import com.projecthivemind.network.ReturnToBasePayload;
import com.projecthivemind.network.ReturnToHeartPayload;
import com.projecthivemind.network.BuildTowerPayload;
import com.projecthivemind.network.BuildBridgePayload;
import com.projecthivemind.network.DigStaircasePayload;
import com.projecthivemind.network.OpenBookPayload;
import com.projecthivemind.network.OpenSignPayload;
import com.projecthivemind.network.CancelJobPayload;
import com.projecthivemind.network.KillUnitPayload;
import com.projecthivemind.network.DropItemPayload;
import com.projecthivemind.network.FocusUnitPayload;
import com.projecthivemind.network.ScoutUsePayload;
import com.projecthivemind.network.SetJobResumePayload;
import com.projecthivemind.network.SyncUnitsPayload;
import com.projecthivemind.network.ViewUnitPayload;
import com.projecthivemind.network.SetCollectorTaskPayload;
import com.projecthivemind.network.SetWorkerFillPayload;
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
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ProjectHivemind.MODID)
public final class CommonEvents {
    private CommonEvents() {
    }

    // ---- mod bus: registration ----

    @SubscribeEvent
    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ChooseModePayload.TYPE, ChooseModePayload.STREAM_CODEC, ServerPayloads::onChooseMode);
        registrar.playToServer(ReturnToBasePayload.TYPE, ReturnToBasePayload.STREAM_CODEC, ServerPayloads::onReturnToBase);
        registrar.playToServer(SetUnitTeamPayload.TYPE, SetUnitTeamPayload.STREAM_CODEC, ServerPayloads::onSetUnitTeam);
        registrar.playToServer(FocusTeamPayload.TYPE, FocusTeamPayload.STREAM_CODEC, ServerPayloads::onFocusTeam);
        registrar.playToServer(com.projecthivemind.network.ConsumeEvolvePayload.TYPE, com.projecthivemind.network.ConsumeEvolvePayload.STREAM_CODEC, ServerPayloads::onConsumeEvolve);
        registrar.playToServer(com.projecthivemind.network.EnchantPayload.TYPE, com.projecthivemind.network.EnchantPayload.STREAM_CODEC, ServerPayloads::onEnchant);
        registrar.playToServer(com.projecthivemind.network.BuildWallPayload.TYPE, com.projecthivemind.network.BuildWallPayload.STREAM_CODEC, ServerPayloads::onBuildWall);
        registrar.playToServer(BuildBridgePayload.TYPE, BuildBridgePayload.STREAM_CODEC, ServerPayloads::onBuildBridge);
        registrar.playToServer(OpenHiveMenuPayload.TYPE, OpenHiveMenuPayload.STREAM_CODEC, ServerPayloads::onOpenHiveMenu);
        registrar.playToServer(HiveMenuClickPayload.TYPE, HiveMenuClickPayload.STREAM_CODEC, ServerPayloads::onHiveMenuClick);
        registrar.playToServer(BlockActionPayload.TYPE, BlockActionPayload.STREAM_CODEC, ServerPayloads::onBlockAction);
        registrar.playToServer(SelectionPayload.TYPE, SelectionPayload.STREAM_CODEC, ServerPayloads::onSelection);
        registrar.playToServer(SetMenuViewPayload.TYPE, SetMenuViewPayload.STREAM_CODEC, ServerPayloads::onSetMenuView);
        registrar.playToServer(FocusUnitPayload.TYPE, FocusUnitPayload.STREAM_CODEC, ServerPayloads::onFocusUnit);
        registrar.playToServer(DropItemPayload.TYPE, DropItemPayload.STREAM_CODEC, ServerPayloads::onDropItem);
        registrar.playToServer(com.projecthivemind.network.SetWorkerCompostPayload.TYPE, com.projecthivemind.network.SetWorkerCompostPayload.STREAM_CODEC, ServerPayloads::onSetWorkerCompost);
        registrar.playToServer(SetWorkerFillPayload.TYPE, SetWorkerFillPayload.STREAM_CODEC, ServerPayloads::onSetWorkerFill);
        registrar.playToServer(SetCollectorTaskPayload.TYPE, SetCollectorTaskPayload.STREAM_CODEC, ServerPayloads::onSetCollectorTask);
        registrar.playToServer(SetUnitBehaviorPayload.TYPE, SetUnitBehaviorPayload.STREAM_CODEC, ServerPayloads::onSetUnitBehavior);
        registrar.playToServer(CancelJobPayload.TYPE, CancelJobPayload.STREAM_CODEC, ServerPayloads::onCancelJob);
        registrar.playToServer(KillUnitPayload.TYPE, KillUnitPayload.STREAM_CODEC, ServerPayloads::onKillUnit);
        registrar.playToServer(SetJobResumePayload.TYPE, SetJobResumePayload.STREAM_CODEC, ServerPayloads::onSetJobResume);
        registrar.playToServer(ViewUnitPayload.TYPE, ViewUnitPayload.STREAM_CODEC, ServerPayloads::onViewUnit);
        registrar.playToClient(SyncUnitsPayload.TYPE, SyncUnitsPayload.STREAM_CODEC, ClientPayloads::onSyncUnits);
        registrar.playToClient(com.projecthivemind.network.SyncPortalsPayload.TYPE, com.projecthivemind.network.SyncPortalsPayload.STREAM_CODEC, ClientPayloads::onSyncPortals);
        registrar.playToClient(com.projecthivemind.network.SyncMusicPayload.TYPE, com.projecthivemind.network.SyncMusicPayload.STREAM_CODEC, ClientPayloads::onSyncMusic);
        registrar.playToClient(com.projecthivemind.network.ConfirmPortalPayload.TYPE, com.projecthivemind.network.ConfirmPortalPayload.STREAM_CODEC, ClientPayloads::onConfirmPortal);
        registrar.playToServer(com.projecthivemind.network.PlacePortalPayload.TYPE, com.projecthivemind.network.PlacePortalPayload.STREAM_CODEC, ServerPayloads::onPlacePortal);
        registrar.playToServer(com.projecthivemind.network.SummonUnitsPayload.TYPE, com.projecthivemind.network.SummonUnitsPayload.STREAM_CODEC, ServerPayloads::onSummonUnits);
        registrar.playToServer(com.projecthivemind.network.DeletePortalPayload.TYPE, com.projecthivemind.network.DeletePortalPayload.STREAM_CODEC, ServerPayloads::onDeletePortal);
        registrar.playToClient(com.projecthivemind.network.SyncTeamPayload.TYPE, com.projecthivemind.network.SyncTeamPayload.STREAM_CODEC, ClientPayloads::onSyncTeam);
        registrar.playToServer(com.projecthivemind.network.SetTeamRadiusPayload.TYPE, com.projecthivemind.network.SetTeamRadiusPayload.STREAM_CODEC, ServerPayloads::onSetTeamRadius);
        registrar.playToServer(com.projecthivemind.network.PlaceTorchPayload.TYPE, com.projecthivemind.network.PlaceTorchPayload.STREAM_CODEC, ServerPayloads::onPlaceTorch);
        registrar.playToServer(ScoutUsePayload.TYPE, ScoutUsePayload.STREAM_CODEC, ServerPayloads::onScoutUse);
        registrar.playToServer(SignTextPayload.TYPE, SignTextPayload.STREAM_CODEC, ServerPayloads::onSignText);
        registrar.playToClient(OpenBookPayload.TYPE, OpenBookPayload.STREAM_CODEC, ClientPayloads::onOpenBook);
        registrar.playToClient(OpenSignPayload.TYPE, OpenSignPayload.STREAM_CODEC, ClientPayloads::onOpenSign);
        registrar.playToServer(DigStaircasePayload.TYPE, DigStaircasePayload.STREAM_CODEC, ServerPayloads::onDigStaircase);
        registrar.playToServer(BuildTowerPayload.TYPE, BuildTowerPayload.STREAM_CODEC, ServerPayloads::onBuildTower);
        registrar.playToServer(com.projecthivemind.network.SetStorageSearchPayload.TYPE, com.projecthivemind.network.SetStorageSearchPayload.STREAM_CODEC, ServerPayloads::onStorageSearch);
        registrar.playToServer(ScrollStoragePayload.TYPE, ScrollStoragePayload.STREAM_CODEC, ServerPayloads::onScrollStorage);
        registrar.playToServer(TradePayload.TYPE, TradePayload.STREAM_CODEC, ServerPayloads::onTrade);
        registrar.playToClient(TradeOffersPayload.TYPE, TradeOffersPayload.STREAM_CODEC, ClientPayloads::onTradeOffers);
        registrar.playToClient(SyncHeartHealthPayload.TYPE, SyncHeartHealthPayload.STREAM_CODEC, ClientPayloads::onSyncHeartHealth);
        registrar.playToClient(SyncEyesPayload.TYPE, SyncEyesPayload.STREAM_CODEC, ClientPayloads::onSyncEyes);
        registrar.playToClient(SyncSightPayload.TYPE, SyncSightPayload.STREAM_CODEC, ClientPayloads::onSyncSight);
        registrar.playToServer(MobActionPayload.TYPE, MobActionPayload.STREAM_CODEC, ServerPayloads::onMobAction);
        registrar.playToClient(WeakToolPayload.TYPE, WeakToolPayload.STREAM_CODEC, ClientPayloads::onWeakTool);
        registrar.playToClient(WeakStairsPayload.TYPE, WeakStairsPayload.STREAM_CODEC, ClientPayloads::onWeakStairs);
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
        event.put(ModEntities.HIVE_FEEDER.get(), HiveFeeder.createFeederAttributes().build());
    }

    // ---- game bus ----

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HivemindManager.refresh(player);
        }
    }

    /** A hivemind whose Heart is not loaded gets it loaded, so the hive works wherever the camera is. */
    @SubscribeEvent
    static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 20 == 0) {
            HivemindManager.ensureHeartLoaded(player);
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



    /**
     * Once the hive has consumed a carved pumpkin (an evolution task), endermen do not take its units or its Heart as targets, as they do not take
     * a player wearing one: not when the units look at them, and not when the units fight them.
     */
    @SubscribeEvent
    static void onEndermanTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.monster.EnderMan) || event.getNewAboutToBeSetTarget() == null
                || !(event.getNewAboutToBeSetTarget().level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        net.minecraft.world.entity.LivingEntity target = event.getNewAboutToBeSetTarget();
        java.util.UUID owner = target instanceof HiveUnit unit ? unit.ownerId() : target instanceof HiveHeart own ? own.ownerId() : null;
        net.minecraft.server.level.ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        HiveHeart heart = player == null ? null : HivemindManager.findHeart(player);
        if (heart != null && com.projecthivemind.EvolveTask.CARVED_PUMPKIN.doneIn(heart.evolveMask())) {
            event.setCanceled(true);
        }
    }

    /** Whatever the hive attacks with never damages a hive unit or a Heart, however it gets there. */
    @SubscribeEvent
    static void onHiveAttack(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (com.projecthivemind.entity.HiveAttacks.spares(event.getEntity()) && com.projecthivemind.entity.HiveAttacks.isHiveAttack(event.getSource())) {
            event.setCanceled(true);
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
        if (event.getNewDamage() > 0.0F && event.getEntity() instanceof Mob hurt && hurt instanceof HiveUnit) {
            HivemindManager.onUnitHurt(hurt);
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
