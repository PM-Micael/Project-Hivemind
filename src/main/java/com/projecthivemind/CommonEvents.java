package com.projecthivemind;

import com.projecthivemind.entity.HiveSoldier;
import com.projecthivemind.entity.HiveWorker;
import com.projecthivemind.network.ChooseModePayload;
import com.projecthivemind.network.ClientPayloads;
import com.projecthivemind.network.ServerPayloads;
import com.projecthivemind.network.SpawnUnitPayload;
import com.projecthivemind.network.SyncHivemindPayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.world.entity.monster.Skeleton;

@EventBusSubscriber(modid = ProjectHivemind.MODID)
public final class CommonEvents {
    private CommonEvents() {
    }

    // ---- mod bus: registration ----

    @SubscribeEvent
    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ChooseModePayload.TYPE, ChooseModePayload.STREAM_CODEC, ServerPayloads::onChooseMode);
        registrar.playToServer(SpawnUnitPayload.TYPE, SpawnUnitPayload.STREAM_CODEC, ServerPayloads::onSpawnUnit);
        registrar.playToClient(SyncHivemindPayload.TYPE, SyncHivemindPayload.STREAM_CODEC, ClientPayloads::onSync);
    }

    @SubscribeEvent
    static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.HIVE_WORKER.get(), Skeleton.createAttributes().build());
        event.put(ModEntities.HIVE_SOLDIER.get(), HiveSoldier.createHiveAttributes().build());
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

    /** As a larva, right-clicking any block places the Hive Heart instead of the normal interaction. */
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

    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob
                && (mob instanceof HiveWorker || mob instanceof HiveSoldier)
                && mob.level() instanceof ServerLevel level) {
            HivemindManager.onUnitDied(level, mob);
        }
    }
}
