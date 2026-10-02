package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.ScoutItems;
import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * A scout told to use the item in its hand on a face of a block: walk up to it (unless the item is used from where the
 * scout stands, like a pearl or a book), then use it once. If it cannot be done, the player is told.
 */
public class ScoutUseItemGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    /** Give up if the scout cannot get there for this long (15 seconds). */
    private static final int GIVE_UP_TICKS = 300;

    private final HiveScout scout;
    private int repathCooldown;
    private int waitedTicks;

    public ScoutUseItemGoal(HiveScout scout) {
        this.scout = scout;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean hasOrder() {
        UnitAction action = scout.action();
        return action != null && action.kind() == UnitAction.Kind.USE_ITEM && action.pos() != null && action.face() != null;
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
        if (pos == null || face == null || heart == null || owner == null || heart.scoutHand().getItem(0).isEmpty()) {
            scout.setAction(null);
            return;
        }

        // Where the item is used: the spot in front of the clicked face.
        Vec3 spot = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        boolean far = ScoutItems.usedFromAfar(heart.scoutHand().getItem(0));
        if (!far && scout.getEyePosition().distanceToSqr(spot) > ScoutItems.REACH * ScoutItems.REACH) {
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
        // The owner can only have one screen open: a book or a sign needs the screen free.
        if (owner.containerMenu != owner.inventoryMenu && (far || heart.scoutHand().getItem(0).is(net.minecraft.tags.ItemTags.SIGNS) || heart.scoutHand().getItem(0).is(net.minecraft.tags.ItemTags.HANGING_SIGNS))) {
            return;
        }
        boolean done = ScoutItems.use(level, heart, scout, owner, pos, face);
        if (!done) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.scout_cannot_use"), true);
        }
        scout.setAction(null);
    }
}
