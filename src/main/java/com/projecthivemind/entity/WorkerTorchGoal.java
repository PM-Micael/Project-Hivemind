package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A worker told to place a torch: walk up to the block it was pointed at, and put a torch from the hive against the face that was
 * clicked: standing on top of it, or on its side. The order ends once the torch is up, or at once if there is no torch in the hive or
 * it cannot go there.
 */
public class WorkerTorchGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    private static final int GIVE_UP_TICKS = 200;

    private final HiveWorker worker;
    private int repathCooldown;
    private int stuckTicks;

    public WorkerTorchGoal(HiveWorker worker) {
        this.worker = worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private UnitAction order() {
        UnitAction action = worker.action();
        return action != null && action.kind() == UnitAction.Kind.TORCH && action.pos() != null && action.face() != null ? action : null;
    }

    @Override
    public boolean canUse() {
        return order() != null && worker.findHeart() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
    }

    @Override
    public void stop() {
        if (worker.action() == null) {
            worker.getNavigation().stop();
        }
    }

    /** The torch as it will stand: upright for the top of a block, otherwise a wall torch facing away from the block. */
    private static BlockState torchState(Direction face) {
        return face == Direction.UP ? Blocks.TORCH.defaultBlockState()
                : Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, face);
    }

    @Override
    public void tick() {
        UnitAction order = order();
        HiveHeart heart = worker.findHeart();
        if (order == null || heart == null) {
            return;
        }
        ServerLevel level = (ServerLevel) worker.level();
        Direction face = order.face();
        BlockPos spot = order.pos().relative(face);
        BlockState torch = torchState(face);
        // Nothing to do if it cannot go there, or the hive has no torch.
        if (face == Direction.DOWN || !level.getBlockState(spot).canBeReplaced() || !torch.canSurvive(level, spot)
                || heart.getStorage().countItem(Items.TORCH) <= 0) {
            worker.setAction(null);
            return;
        }
        if (!WorkerDigGoal.inDigReach(worker, spot)) {
            if (++stuckTicks > GIVE_UP_TICKS) {
                worker.setAction(null);
            } else if (--repathCooldown <= 0) {
                Vec3 center = Vec3.atCenterOf(spot);
                worker.getNavigation().moveTo(center.x, spot.getY(), center.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        worker.getNavigation().stop();
        worker.getLookControl().setLookAt(Vec3.atCenterOf(spot));
        heart.getStorage().removeItemType(Items.TORCH, 1);
        level.setBlock(spot, torch, Block.UPDATE_ALL);
        level.playSound(null, spot, torch.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        worker.swing(InteractionHand.MAIN_HAND);
        worker.setAction(null);
    }
}
