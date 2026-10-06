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
    /** The creep block is in the creative inventory, with the other building blocks. */
    @SubscribeEvent
    static void addToCreativeTabs(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ModBlocks.CREEP_BLOCK_ITEM.get());
            event.accept(ModBlocks.CREEP_DIRT_ITEM.get());
            event.accept(ModBlocks.CREEP_GRASS_ITEM.get());
            event.accept(ModBlocks.CREEP_STONE_ITEM.get());
        }
    }


    @SubscribeEvent
    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ChooseModePayload.TYPE, ChooseModePayload.STREAM_CODEC, ServerPayloads::onChooseMode);
        registrar.playToServer(ReturnToBasePayload.TYPE, ReturnToBasePayload.STREAM_CODEC, ServerPayloads::onReturnToBase);
        registrar.playToServer(SetUnitTeamPayload.TYPE, SetUnitTeamPayload.STREAM_CODEC, ServerPayloads::onSetUnitTeam);
        registrar.playToServer(FocusTeamPayload.TYPE, FocusTeamPayload.STREAM_CODEC, ServerPayloads::onFocusTeam);
        registrar.playToServer(com.projecthivemind.network.ConsumeEvolvePayload.TYPE, com.projecthivemind.network.ConsumeEvolvePayload.STREAM_CODEC, ServerPayloads::onConsumeEvolve);
        registrar.playToServer(com.projecthivemind.network.ApplyEnchantPayload.TYPE, com.projecthivemind.network.ApplyEnchantPayload.STREAM_CODEC, ServerPayloads::onApplyEnchant);
        registrar.playToServer(com.projecthivemind.network.ConsumeEnchantPayload.TYPE, com.projecthivemind.network.ConsumeEnchantPayload.STREAM_CODEC, ServerPayloads::onConsumeEnchant);
        registrar.playToServer(com.projecthivemind.network.PlaceRecipePayload.TYPE, com.projecthivemind.network.PlaceRecipePayload.STREAM_CODEC, ServerPayloads::onPlaceRecipe);
        registrar.playToClient(com.projecthivemind.network.SyncEnchantsPayload.TYPE, com.projecthivemind.network.SyncEnchantsPayload.STREAM_CODEC, ClientPayloads::onSyncEnchants);
        registrar.playToClient(com.projecthivemind.network.SyncRecipesPayload.TYPE, com.projecthivemind.network.SyncRecipesPayload.STREAM_CODEC, ClientPayloads::onSyncRecipes);
        registrar.playToServer(com.projecthivemind.network.AssignConstructionPayload.TYPE, com.projecthivemind.network.AssignConstructionPayload.STREAM_CODEC, ServerPayloads::onAssignConstruction);
        registrar.playToServer(com.projecthivemind.network.UpdateConstructionPayload.TYPE, com.projecthivemind.network.UpdateConstructionPayload.STREAM_CODEC, ServerPayloads::onUpdateConstruction);
        registrar.playToServer(com.projecthivemind.network.FinishConstructionPayload.TYPE, com.projecthivemind.network.FinishConstructionPayload.STREAM_CODEC, ServerPayloads::onFinishConstruction);
        registrar.playToClient(com.projecthivemind.network.SyncConstructionsPayload.TYPE, com.projecthivemind.network.SyncConstructionsPayload.STREAM_CODEC, ClientPayloads::onSyncConstructions);
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
        registrar.playToServer(com.projecthivemind.network.GoToPortalPayload.TYPE, com.projecthivemind.network.GoToPortalPayload.STREAM_CODEC, ServerPayloads::onGoToPortal);
        registrar.playToServer(com.projecthivemind.network.BuildGeneratorPayload.TYPE, com.projecthivemind.network.BuildGeneratorPayload.STREAM_CODEC, ServerPayloads::onBuildGenerator);
        registrar.playToServer(com.projecthivemind.network.BuildNetherPortalPayload.TYPE, com.projecthivemind.network.BuildNetherPortalPayload.STREAM_CODEC, ServerPayloads::onBuildNetherPortal);
        registrar.playToServer(com.projecthivemind.network.BuildTunnelPayload.TYPE, com.projecthivemind.network.BuildTunnelPayload.STREAM_CODEC, ServerPayloads::onBuildTunnel);
        registrar.playToServer(com.projecthivemind.network.TogglePortalResummonPayload.TYPE, com.projecthivemind.network.TogglePortalResummonPayload.STREAM_CODEC, ServerPayloads::onTogglePortalResummon);
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
        registrar.playToServer(com.projecthivemind.network.ClearTrashPayload.TYPE, com.projecthivemind.network.ClearTrashPayload.STREAM_CODEC, ServerPayloads::onClearTrash);
        registrar.playToServer(com.projecthivemind.network.ScrollScoutStoragePayload.TYPE, com.projecthivemind.network.ScrollScoutStoragePayload.STREAM_CODEC, ServerPayloads::onScrollScoutStorage);
        registrar.playToServer(com.projecthivemind.network.SetScoutSearchPayload.TYPE, com.projecthivemind.network.SetScoutSearchPayload.STREAM_CODEC, ServerPayloads::onScoutSearch);
        registrar.playToServer(TradePayload.TYPE, TradePayload.STREAM_CODEC, ServerPayloads::onTrade);
        registrar.playToClient(TradeOffersPayload.TYPE, TradeOffersPayload.STREAM_CODEC, ClientPayloads::onTradeOffers);
        registrar.playToClient(SyncHeartHealthPayload.TYPE, SyncHeartHealthPayload.STREAM_CODEC, ClientPayloads::onSyncHeartHealth);
        registrar.playToClient(SyncEyesPayload.TYPE, SyncEyesPayload.STREAM_CODEC, ClientPayloads::onSyncEyes);
        registrar.playToClient(SyncSightPayload.TYPE, SyncSightPayload.STREAM_CODEC, ClientPayloads::onSyncSight);
        registrar.playToServer(com.projecthivemind.network.ControlRequestPayload.TYPE, com.projecthivemind.network.ControlRequestPayload.STREAM_CODEC, ServerPayloads::onControlRequest);
        registrar.playToServer(com.projecthivemind.network.ControlInputPayload.TYPE, com.projecthivemind.network.ControlInputPayload.STREAM_CODEC, ServerPayloads::onControlInput);
        registrar.playToServer(com.projecthivemind.network.ControlSelectPayload.TYPE, com.projecthivemind.network.ControlSelectPayload.STREAM_CODEC, ServerPayloads::onControlSelect);
        registrar.playToClient(com.projecthivemind.network.ControlHotbarPayload.TYPE, com.projecthivemind.network.ControlHotbarPayload.STREAM_CODEC, ClientPayloads::onControlHotbar);
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
        if (event.getEntity() instanceof ServerPlayer player) {
            ScoutControl.tick(player);
            if (player.tickCount % 20 == 0) {
                HivemindManager.ensureHeartLoaded(player);
            }
        }
    }

    /** A player who leaves is no longer controlling a scout. */
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ScoutControl.onLogout(player);
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



    /** How long after the hive hits an enderman it may fight back (a mob's revenge time in the game is about this long). */
    private static final int PROVOKED_TICKS = 200;

    /**
     * Once the hive has consumed a carved pumpkin (an evolution task), endermen do not take its units or its Heart as targets on their own, as they do
     * not take a player wearing one: not when the units look at them. An enderman the hive has hit first does fight back.
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
        // An enderman the hive has hit is another matter: it may take its attacker as a target, as any mob would.
        net.minecraft.world.entity.monster.EnderMan enderman = (net.minecraft.world.entity.monster.EnderMan) event.getEntity();
        boolean provoked = enderman.getLastHurtByMob() == target && enderman.tickCount - enderman.getLastHurtByMobTimestamp() < PROVOKED_TICKS;
        if (heart != null && !provoked && com.projecthivemind.EvolveTask.CARVED_PUMPKIN.doneIn(heart.evolveMask())) {
            event.setCanceled(true);
        }
    }

    /** The Ender Dragon dying counts for every hive that was in the End when it did: it is the quest for level 6. */
    @SubscribeEvent
    static void onDragonDied(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon && !dragon.level().isClientSide
                && dragon.level().getServer() != null) {
            for (net.minecraft.server.level.ServerPlayer player : dragon.level().getServer().getPlayerList().getPlayers()) {
                HivemindManager.dragonDefeated(player, dragon.level().dimension());
            }
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
        if (event.getNewDamage() > 0.0F && event.getEntity() instanceof HiveHeart attackedHeart) {
            com.projecthivemind.entity.HeartAlerts.attacked(attackedHeart, event.getSource());
        }
    }
    /** A mob a hive unit killed gave its experience to the hive's bar already: no orbs of it as well. */
    @SubscribeEvent
    static void onExperienceDrop(net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent event) {
        if (HivemindManager.takeNoXpOrbs(event.getEntity().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        // The totem task: a Heart that would die is saved (once every 5 minutes) as if it held a totem of undying.
        if (event.getEntity() instanceof HiveHeart heart && heart.tryUndying(event.getSource())) {
            event.setCanceled(true);
            return;
        }
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
