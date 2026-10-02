package com.projecthivemind.entity;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.projecthivemind.UnitAction;
import com.projecthivemind.build.TowerBuild;
import com.projecthivemind.build.TowerSet;

import com.projecthivemind.HiveEquipment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * A worker on a tower build: take the next block it can reach a place to stand for, walk there (up the tower's own
 * steps), and place it from the hive's storage. When the hive runs out of what the block is made of the worker waits,
 * and carries on as soon as there is some.
 */
public class WorkerBuildGoal extends Goal {
    private static final double SPEED = 1.0D;
    private static final int REPATH_INTERVAL = 10;
    private static final int PLACE_INTERVAL = 6;
    /** Digging a block out is quicker than building one in. */
    private static final int DIG_INTERVAL = 3;
    /** How often a worker with nothing to do looks for something. */
    private static final int LOOK_INTERVAL = 10;
    /** A worker that has not got closer for this long (6 seconds) is carried to its spot: it is "climbing". */
    private static final int STUCK_TICKS = 120;

    private final Mob mob;
    private final HiveUnit unit;
    @Nullable
    private TowerBuild.Job job;
    private int repathCooldown;
    private int placeCooldown;
    private int lookCooldown;
    private int stuckTicks;
    private double bestDistance;

    public WorkerBuildGoal(Mob mob) {
        this.mob = mob;
        this.unit = (HiveUnit) mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean building() {
        UnitAction action = unit.action();
        return action != null && action.kind() == UnitAction.Kind.BUILD;
    }

    @Override
    public boolean canUse() {
        return building();
    }

    @Override
    public boolean canContinueToUse() {
        return building();
    }

    @Override
    public void start() {
        job = null;
        lookCooldown = 0;
        placeCooldown = 0;
    }

    @Override
    public void stop() {
        HiveHeart heart = unit.findHeart();
        if (heart != null && heart.activeBuild() != null) {
            heart.activeBuild().release(mob.getUUID());
        }
        job = null;
        // Only halt the worker if it has nothing else to do; a new order has already set its own path.
        if (unit.action() == null) {
            mob.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        HiveHeart heart = unit.findHeart();
        TowerBuild build = heart == null ? null : heart.activeBuild();
        if (build == null || !(mob.level() instanceof ServerLevel level)) {
            // The build was cancelled, or finished by the others.
            unit.setAction(null);
            return;
        }

        if (job == null) {
            if (--lookCooldown > 0) {
                return;
            }
            lookCooldown = LOOK_INTERVAL;
            if (build.isComplete(level)) {
                finish(heart, build);
                return;
            }
            job = build.claimNext(level, mob);
            repathCooldown = 0;
            stuckTicks = 0;
            bestDistance = Double.MAX_VALUE;
            if (job == null) {
                return;
            }
        }

        Vec3 target = Vec3.atCenterOf(job.placement().pos());
        mob.getLookControl().setLookAt(target);
        if (mob.getEyePosition().distanceToSqr(target) <= build.placeReach() * build.placeReach()) {
            mob.getNavigation().stop();
            if (job.placement().dig()) {
                digStep(level, heart, build, true);
                return;
            }
            // Something solid where a block is to go (the rock a shaft's wall is set into) is dug out first, with a tool, and into the hive.
            BlockPos spot = job.placement().pos();
            BlockState inTheWay = level.getBlockState(spot);
            if (!inTheWay.isAir() && !inTheWay.canBeReplaced() && inTheWay.getDestroySpeed(level, spot) >= 0.0F && !inTheWay.hasBlockEntity()) {
                digStep(level, heart, build, false);
                return;
            }
            if (--placeCooldown > 0) {
                return;
            }
            placeCooldown = job.placement().dig() ? DIG_INTERVAL : PLACE_INTERVAL;
            TowerBuild.Result result = build.place(level, heart, mob, job.placement());
            if (result == TowerBuild.Result.NO_MATERIAL) {
                build.release(mob.getUUID());
                job = null;
                lookCooldown = 40;
                notifyWaiting(level, heart, build);
            } else {
                job = null;
                lookCooldown = 1;
            }
            return;
        }

        // Not in reach yet: go to the spot, and if the way is not working, be carried the last of it.
        Vec3 stand = job.stand();
        double distance = mob.position().distanceToSqr(stand);
        if (distance < bestDistance - 0.25D) {
            bestDistance = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > STUCK_TICKS) {
            mob.getNavigation().stop();
            mob.moveTo(stand.x, stand.y, stand.z, mob.getYRot(), mob.getXRot());
            stuckTicks = 0;
            bestDistance = Double.MAX_VALUE;
            return;
        }
        if (--repathCooldown <= 0) {
            mob.getNavigation().moveTo(stand.x, stand.y, stand.z, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    /** The block a dig is on, and whether the tool for it has been picked, so a new block gets a fresh choice. */
    @Nullable
    private BlockPos equippedFor;
    private int swingCooldown;

    /**
     * One tick of digging a block out of a shaft, the way a worker digs anywhere else: with the best tool the hive has for it, taking as
     * long as the block takes with that tool, and with what comes out going straight into the hive. A block that no tool in the hive can
     * break properly (obsidian with a wooden pickaxe) is not dug at all: the worker waits, and tries again every couple of seconds, until
     * a good enough tool is in the hive's tool slots or the player cancels.
     */
    private void digStep(ServerLevel level, HiveHeart heart, TowerBuild build, boolean endsJob) {
        BlockPos pos = job.placement().pos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) {
            build.releaseClaim(pos);
            job = null;
            lookCooldown = 1;
            return;
        }
        if (state.getDestroySpeed(level, pos) < 0.0F || !(mob instanceof HiveWorker worker)) {
            // Cannot be broken at all (bedrock): it is left out of the shaft.
            build.skip(pos);
            job = null;
            lookCooldown = 1;
            return;
        }
        if (!pos.equals(equippedFor)) {
            HiveEquipment.equipBestTool(worker, heart, state);
            equippedFor = pos;
        }
        ItemStack tool = mob.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        if (!correct) {
            // Nothing in the hive's tool slots will do: wait for one.
            level.destroyBlockProgress(mob.getId(), pos, -1);
            build.release(mob.getUUID());
            job = null;
            equippedFor = null;
            lookCooldown = 40;
            notifyNeedsTool(level, heart, build, state);
            return;
        }
        float hardness = state.getDestroySpeed(level, pos);
        float speed = HiveEquipment.miningSpeed(tool, state, level.registryAccess());
        float progress = heart.addDigProgress(pos, speed / hardness / 30.0F);
        if (--swingCooldown <= 0) {
            mob.swing(InteractionHand.MAIN_HAND);
            swingCooldown = 6;
        }
        level.destroyBlockProgress(mob.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        if (progress >= 1.0F) {
            HiveDrops.store(level, heart, pos, state, level.getBlockEntity(pos), mob, tool);
            level.destroyBlock(pos, false, mob);
            heart.food().exhaust(com.projecthivemind.HiveFood.BREAK_BLOCK);
            tool.hurtAndBreak(1, mob, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            heart.clearDigProgress(pos);
            level.destroyBlockProgress(mob.getId(), pos, -1);
            equippedFor = null;
            if (endsJob) {
                build.releaseClaim(pos);
                job = null;
                lookCooldown = 1;
            } else {
                // It was something in the way of a block to be placed: now the block goes in.
                placeCooldown = 0;
            }
        }
    }

    private void notifyNeedsTool(ServerLevel level, HiveHeart heart, TowerBuild build, BlockState state) {
        if (!build.shouldNotifyWaiting(level.getGameTime()) || heart.getServer() == null || heart.ownerId() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner != null) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.tower_needs_tool", state.getBlock().getName()), true);
        }
    }

    /** Every worker on the tower stops once it is whole; the first to notice tells the player. */
    private void finish(HiveHeart heart, TowerBuild build) {
        if (heart.activeBuild() == build) {
            heart.setActiveBuild(null);
            if (heart.getServer() != null && heart.ownerId() != null) {
                ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
                if (owner != null) {
                    owner.displayClientMessage(Component.translatable("message.projecthivemind.tower_finished"), false);
                }
            }
        }
        unit.setAction(null);
    }

    private void notifyWaiting(ServerLevel level, HiveHeart heart, TowerBuild build) {
        if (!build.shouldNotifyWaiting(level.getGameTime()) || heart.getServer() == null || heart.ownerId() == null) {
            return;
        }
        ServerPlayer owner = heart.getServer().getPlayerList().getPlayer(heart.ownerId());
        if (owner != null) {
            // Name what ran out: the walls if there are none of those, otherwise the stairs.
            boolean wallsOut = build.set().availableWall(heart.getStorage()) == null;
            owner.displayClientMessage(Component.translatable("message.projecthivemind.tower_waiting",
                    (wallsOut ? build.set().walls().get(0) : build.set().stairs().get(0)).getDescription()), true);
        }
    }
}
