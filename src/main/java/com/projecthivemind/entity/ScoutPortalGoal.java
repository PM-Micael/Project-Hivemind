package com.projecthivemind.entity;

import java.util.EnumSet;

import com.projecthivemind.HivePortals;
import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** A scout told to place a hive portal on a face of a block: it walks up to the spot and puts the portal there. */
public class ScoutPortalGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final double REACH = 4.0D;
    private static final int REPATH_INTERVAL = 10;
    /** Give up if the scout cannot get there for this long (15 seconds). */
    private static final int GIVE_UP_TICKS = 300;

    private final HiveScout scout;
    private int repathCooldown;
    private int waitedTicks;

    public ScoutPortalGoal(HiveScout scout) {
        this.scout = scout;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean hasOrder() {
        UnitAction action = scout.action();
        return action != null && (action.kind() == UnitAction.Kind.PORTAL || action.kind() == UnitAction.Kind.DEHYDRATOR) && action.pos() != null && action.face() != null;
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
        BlockPos pos = action.pos();
        Direction face = action.face();
        HiveHeart heart = scout.findHeart();
        ServerPlayer owner = scout.ownerId() == null || level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(scout.ownerId());
        if (pos == null || face == null || heart == null || owner == null) {
            scout.setAction(null);
            return;
        }
        Vec3 spot = Vec3.atCenterOf(pos.relative(face));
        if (scout.getEyePosition().distanceToSqr(spot) > REACH * REACH) {
            if (++waitedTicks > GIVE_UP_TICKS) {
                owner.displayClientMessage(Component.translatable("message.projecthivemind.scout_cannot_reach"), true);
                scout.setAction(null);
            } else if (--repathCooldown <= 0) {
                scout.getNavigation().moveTo(spot.x, spot.y, spot.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        scout.getNavigation().stop();
        scout.getLookControl().setLookAt(spot);
        scout.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (action.kind() == UnitAction.Kind.DEHYDRATOR) {
            String problem = com.projecthivemind.Dehydrators.problem(level, heart, pos, face);
            if (problem != null || !com.projecthivemind.Dehydrators.place(level, heart, pos, face)) {
                owner.displayClientMessage(Component.translatable(problem != null ? problem : "message.projecthivemind.dehydrator_no_room"), true);
            }
        } else if (!HivePortals.place(level, heart, pos, face, com.projecthivemind.HivemindManager.slotNumber(owner, com.projecthivemind.UnitKind.SCOUT, scout.getUUID()))) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.portal_no_room"), true);
        } else {
            HivePortals.sync(owner, heart);
        }
        scout.setAction(null);
    }
}
