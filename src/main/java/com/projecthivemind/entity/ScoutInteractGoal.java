package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveAccess;
import com.projecthivemind.HivemindStage;
import com.projecthivemind.HivemindManager;
import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * A scout told to interact: walk up to the block (to open its inventory) or the villager (to trade), then open it for
 * its owner. The owner is the camera, so what opens is a hive screen: the container or the trades on one side, the
 * hive's storage on the other.
 */
public class ScoutInteractGoal extends Goal {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final double BLOCK_REACH_SQR = 3.0D * 3.0D;
    private static final double VILLAGER_REACH_SQR = 2.5D * 2.5D;
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    /** Give up if the scout cannot get there (or the owner's screen stays busy) for this long (20 seconds). */
    private static final int GIVE_UP_TICKS = 400;

    private final HiveScout scout;
    private int repathCooldown;
    private int waitedTicks;

    public ScoutInteractGoal(HiveScout scout) {
        this.scout = scout;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean hasOrder() {
        UnitAction action = scout.action();
        return action != null && (action.kind() == UnitAction.Kind.INTERACT || action.kind() == UnitAction.Kind.TRADE);
    }

    @Override
    public boolean canUse() {
        return hasOrder();
    }

    @Override
    public boolean canContinueToUse() {
        return hasOrder();
    }

    @Override
    public void start() {
        repathCooldown = 0;
        waitedTicks = 0;
    }

    @Override
    public void stop() {
        // Only halt the scout if it has nothing else to do; a new walk order has already set its own path.
        if (scout.action() == null) {
            scout.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        UnitAction action = scout.action();
        if (action == null || !(scout.level() instanceof ServerLevel level)) {
            return;
        }

        boolean trade = action.kind() == UnitAction.Kind.TRADE;
        AbstractVillager villager = null;
        BlockPos pos = action.pos();
        if (trade) {
            villager = action.target() != null && level.getEntity(action.target()) instanceof AbstractVillager found ? found : null;
            if (villager == null || !villager.isAlive()) {
                scout.setAction(null);
                return;
            }
        } else if (pos == null) {
            scout.setAction(null);
            return;
        }

        Vec3 goal = trade ? villager.position() : Vec3.atCenterOf(pos);
        double reach = trade ? VILLAGER_REACH_SQR : BLOCK_REACH_SQR;
        if (++waitedTicks > GIVE_UP_TICKS) {
            scout.setAction(null);
            return;
        }
        if (scout.distanceToSqr(goal) > reach) {
            if (--repathCooldown <= 0) {
                if (trade) {
                    scout.getNavigation().moveTo(villager, SPEED);
                } else {
                    scout.getNavigation().moveTo(goal.x, pos.getY(), goal.z, SPEED);
                }
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }

        scout.getNavigation().stop();
        scout.getLookControl().setLookAt(goal);
        HiveHeart heart = scout.findHeart();
        ServerPlayer owner = scout.ownerId() == null || level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(scout.ownerId());
        if (heart == null || owner == null || HivemindManager.get(owner).stage() != HivemindStage.HIVE) {
            if (owner != null) {
                owner.displayClientMessage(net.minecraft.network.chat.Component.literal(heart == null
                        ? "The scout cannot find its Hive Heart (is it loaded?)" : "The scout cannot open that now"), true);
            }
            LOGGER.warn("[hivemind] scout could not open: heart={} owner={}", heart != null, owner != null);
            scout.setAction(null);
            return;
        }
        // The owner can only have one screen open. Wait while another is, e.g. the hive menu.
        if (owner.containerMenu != owner.inventoryMenu) {
            if (waitedTicks % 40 == 0) {
                owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Close your open menu: the scout is waiting to open the container"), true);
            }
            return;
        }
        if (trade) {
            HiveAccess.openTrade(owner, heart, scout, villager);
        } else {
            if (!HiveAccess.openContainer(owner, heart, scout, level, pos)) {
                LOGGER.warn("[hivemind] openContainer failed at {}: {}", pos, level.getBlockState(pos));
            }
        }
        scout.setAction(null);
    }
}
