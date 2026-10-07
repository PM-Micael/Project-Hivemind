package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.HiveEquipment;
import com.projecthivemind.HiveFood;
import com.projecthivemind.UnitAction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A worker told to dig a block: walk up to it, then break it with the best tool the hive has for the job.
 *
 * <p>Progress is shared through the Heart, so several workers on the same block dig it faster. The block only drops
 * its items if the tool is the right kind for it, exactly as for a player.
 */
public class WorkerDigGoal extends Goal {
    /**
     * How far a worker can dig, in blocks, measured like a player's reach: from its eyes to the nearest point of the
     * block. (Measured from its feet to the block's centre, as it once was, a worker could not reach more than about
     * 3 blocks up, so the upper logs of a tree were out of reach.) This is a little over a survival player's 4.5.
     */
    public static final double DIG_REACH = 5.0D;
    /** How far a worker set to fell trees can reach a log or a leaf of a tree: about the height of a tall tree. */
    public static final double FELLING_REACH = 16.0D;
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    /** Ticks a worker may spend unable to get within reach before it gives up on the block. */
    private static final int GIVE_UP_TICKS = 200;
    private static final int SWING_INTERVAL = 6;

    private final Mob worker;
    private final HiveUnit unit;
    private int repathCooldown;
    private int stuckTicks;
    /** The nearest the worker has been to the block it is walking to, for telling a long walk from being stuck. */
    private double nearest = Double.MAX_VALUE;
    @Nullable
    private BlockPos nearestFor;
    private int swingCooldown;
    /** The block and block type the worker's tool was last chosen for, so a different target gets a fresh choice. */
    @Nullable
    private BlockPos equippedFor;
    @Nullable
    private Block equippedForBlock;
    /** The worker is holding a tool picked from the hive, so an empty hand means that tool broke. */
    private boolean holdingTool;
    /** The block this worker last drew cracks on, so they can be cleared when it stops. */
    @Nullable
    private BlockPos crackPos;

    /** For a worker, which picks the best tool from the hive; or a scout, which digs with whatever it is holding. */
    public WorkerDigGoal(Mob worker) {
        this.worker = worker;
        this.unit = (HiveUnit) worker;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** True if the block is close enough to dig from where the mob stands. */
    public static boolean inDigReach(Mob digger, BlockPos pos) {
        // A worker set to fell trees (or to chop them by range) can chop a tree from its foot, all the way up: the trunk is far taller than an arm reaches.
        double reach = digger instanceof HiveWorker worker && (worker.behavior().fellTrees() || worker.behavior().chopLogs()) && TreeFelling.isTreeBlock(digger.level().getBlockState(pos))
                ? FELLING_REACH : DIG_REACH;
        return new AABB(pos).distanceToSqr(digger.getEyePosition()) <= reach * reach;
    }

    @Nullable
    private BlockPos target() {
        UnitAction action = unit.action();
        return action != null && action.kind() == UnitAction.Kind.DIG ? action.pos() : null;
    }

    @Override
    public boolean canUse() {
        return target() != null && unit.findHeart() != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Digging progress is added once per call of tick(): a goal is only ticked every other game tick unless it asks for every one,
        // which would halve the speed of the formula a player digs with.
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldown = 0;
        stuckTicks = 0;
        swingCooldown = 0;
        equippedFor = null;
        equippedForBlock = null;
        holdingTool = false;
    }

    @Override
    public void stop() {
        // Only halt the worker if it has nothing else to do. If it was just given a new order (say, to walk
        // somewhere), that order's path is already set and must not be cancelled here.
        if (unit.action() == null) {
            worker.getNavigation().stop();
        }
        clearCracks();
    }

    @Override
    public void tick() {
        BlockPos pos = target();
        HiveHeart heart = unit.findHeart();
        if (pos == null || heart == null) {
            return;
        }
        ServerLevel level = (ServerLevel) worker.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            // Already gone (another worker finished it, or something else did).
            heart.clearDigProgress(pos);
            unit.setAction(null);
            return;
        }

        // Choose the best tool for this block before anything else, so the worker walks over already holding it. Choose
        // again whenever the target changes (a new block given to a worker that is already digging) and if the tool
        // broke and the hive has another.
        boolean toolBroke = holdingTool && worker.getMainHandItem().isEmpty();
        if (!pos.equals(equippedFor) || state.getBlock() != equippedForBlock || toolBroke) {
            if (worker instanceof HiveWorker hiveWorker) {
                if (WorkerAutoJobs.bareHandBlock(state)) {
                    // Crops, grass, flowers and leaves are picked with an empty hand: no tool is taken from the hive, and none wears.
                    hiveWorker.resetGearMirror();
                    hiveWorker.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                } else {
                    HiveEquipment.equipBestTool(hiveWorker, heart, state);
                }
            } else if (worker instanceof HiveScout scout) {
                // A scout picks from the same pool of tools; with none suitable it digs with what it holds.
                scout.holdBestToolFor(heart, state);
            }
            equippedFor = pos;
            equippedForBlock = state.getBlock();
            holdingTool = !worker.getMainHandItem().isEmpty();
        }

        Vec3 center = Vec3.atCenterOf(pos);
        if (!inDigReach(worker, pos)) {
            // A long walk across the area is not stuck: the count starts again whenever the worker has got a few blocks nearer.
            double distance = worker.position().distanceTo(center);
            if (!pos.equals(nearestFor) || distance < nearest - 2.0D) {
                nearestFor = pos;
                nearest = distance;
                stuckTicks = 0;
            }
            if (++stuckTicks > GIVE_UP_TICKS) {
                unit.setAction(null);
            } else if (--repathCooldown <= 0) {
                worker.getNavigation().moveTo(center.x, pos.getY(), center.z, SPEED);
                repathCooldown = REPATH_INTERVAL;
            }
            return;
        }
        stuckTicks = 0;
        worker.getNavigation().stop();
        worker.getLookControl().setLookAt(center);

        ItemStack tool = worker.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float hardness = state.getDestroySpeed(level, pos);
        // The same formula a player uses: speed / hardness, divided by 30 with the right tool and 100 without. The
        // speed includes Efficiency, the same as when the tool was chosen.
        float speed = HiveEquipment.miningSpeed(tool, state, level.registryAccess());
        float progress = heart.addDigProgress(pos, speed / hardness / (correct ? 30.0F : 100.0F));

        if (--swingCooldown <= 0) {
            worker.swing(InteractionHand.MAIN_HAND);
            swingCooldown = SWING_INTERVAL;
        }
        level.destroyBlockProgress(worker.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        crackPos = pos;

        if (progress >= 1.0F) {
            breakBlock(level, heart, pos, state, tool, correct);
        }
    }

    private void breakBlock(ServerLevel level, HiveHeart heart, BlockPos pos, BlockState state, ItemStack tool, boolean correct) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (correct) {
            // Whatever a worker or scout breaks goes into the hive's inventory (what does not fit is dropped), not onto the ground.
            HiveDrops.store(level, heart, pos, state, blockEntity, worker, tool);
        }
        level.destroyBlock(pos, false, worker);
        heart.food().exhaust(HiveFood.BREAK_BLOCK);
        // Wears the copy; the gear mirror charges the same wear to the original in the hive.
        tool.hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
        heart.clearDigProgress(pos);
        clearCracks();
        unit.setAction(null);
        if (worker instanceof HiveWorker hiveWorker) {
            hiveWorker.rescanSoon();
        }
    }

    private void clearCracks() {
        if (crackPos != null) {
            worker.level().destroyBlockProgress(worker.getId(), crackPos, -1);
            crackPos = null;
        }
    }
}
