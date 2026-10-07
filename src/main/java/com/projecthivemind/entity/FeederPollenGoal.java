package com.projecthivemind.entity;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.EvolveTask;
import com.projecthivemind.HiveArea;
import com.projecthivemind.HiveFluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Once the hive has consumed a bee nest, an idle feeder set to gather pollen works like a bee: it flies up to a flower inside the hive border,
 * hovers over it while it collects pollen (nectar drips from it on the way home, since the bee's own pollen look cannot be set from outside the game's code), flies back to the Heart and turns it into honey in the honey meter. A trip takes about 20 seconds, so
 * six feeders make about 18 bottles of honey a minute (see {@link #HONEY_PER_TRIP}). It comes after channelling and composting.
 */
public class FeederPollenGoal extends Goal {
    /** How much honey one trip gives: a bottle, a third of a bucket (see HiveFluids). */
    public static final int HONEY_PER_TRIP = HiveFluids.BUCKET / 3;
    /** How long the feeder hovers over the flower, in ticks (4 seconds). */
    private static final int POLLINATE_TICKS = 80;
    private static final int SCAN_INTERVAL = 20;
    private static final double SPEED = 1.0D;
    private static final double HOVER_HEIGHT = 1.2D;
    /** How high above the Heart's ground a feeder flies to hand over its pollen (the same as where it waits). */
    private static final double HOME_HEIGHT = 3.0D;
    private static final double CLOSE_SQR = 1.5D * 1.5D;
    private static final int REPATH_INTERVAL = 10;
    private static final int GIVE_UP_TICKS = 300;
    private static final int IGNORE_TICKS = 600;
    private static final int CLAIM_TICKS = 40;

    private enum Phase {
        TO_FLOWER, POLLINATE, TO_HEART
    }

    private record Claim(UUID owner, long until) {
    }

    /** Which feeder is working which flower, so that the feeders spread over the flowers. */
    private static final Map<GlobalPos, Claim> CLAIMS = new HashMap<>();

    private final HiveFeeder feeder;
    @Nullable
    private BlockPos flower;
    private Phase phase = Phase.TO_FLOWER;
    private int timer;
    private int nextScan;
    private int repathCooldown;
    private int stuckTicks;
    private final Map<BlockPos, Integer> ignored = new HashMap<>();

    public FeederPollenGoal(HiveFeeder feeder) {
        this.feeder = feeder;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private HiveHeart allowedHeart() {
        HiveHeart heart = feeder.findLocalHeart();
        return heart != null && EvolveTask.BEE_NEST.doneIn(heart.evolveMask()) && feeder.behavior().gatherPollen() ? heart : null;
    }

    private static boolean room(HiveHeart heart) {
        return HiveFluids.capacity(HiveFluids.HONEY) - heart.fluids().amount(HiveFluids.HONEY) >= HONEY_PER_TRIP;
    }

    private boolean claimedByOther(BlockPos pos) {
        Claim claim = CLAIMS.get(GlobalPos.of(feeder.level().dimension(), pos));
        return claim != null && claim.until() > feeder.level().getGameTime() && !claim.owner().equals(feeder.getUUID());
    }

    private void claim(BlockPos pos) {
        CLAIMS.put(GlobalPos.of(feeder.level().dimension(), pos), new Claim(feeder.getUUID(), feeder.level().getGameTime() + CLAIM_TICKS));
    }

    @Override
    public boolean canUse() {
        HiveHeart heart = allowedHeart();
        if (heart == null || !room(heart) || feeder.tickCount < nextScan || !(feeder.level() instanceof ServerLevel level)) {
            return false;
        }
        nextScan = feeder.tickCount + SCAN_INTERVAL;
        ignored.values().removeIf(until -> until <= feeder.tickCount);
        flower = findFlower(level, heart);
        return flower != null;
    }
    /** Every flower within reach of a Heart, found once for all its feeders and looked at again every few seconds (nearest the Heart first). */
    private record FlowerList(long time, java.util.List<BlockPos> flowers) {
    }

    private static final Map<UUID, FlowerList> FLOWER_LISTS = new HashMap<>();
    private static final int LIST_TICKS = 100;
    private static final int LIST_MAX = 256;
    /** How far from the Heart flowers are looked for, sideways and up or down. */
    private static final int AREA_RADIUS = 32;
    private static final int AREA_HEIGHT = 12;

    private static java.util.List<BlockPos> flowersAround(ServerLevel level, HiveHeart heart) {
        FlowerList cached = FLOWER_LISTS.get(heart.getUUID());
        if (cached != null && level.getGameTime() - cached.time() <= LIST_TICKS) {
            return cached.flowers();
        }
        BlockPos center = heart.blockPosition();
        int minY = center.getY() - AREA_HEIGHT;
        int maxY = center.getY() + AREA_HEIGHT;
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> isFlower = state -> state.is(BlockTags.FLOWERS);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int chunkX = (center.getX() - AREA_RADIUS) >> 4; chunkX <= (center.getX() + AREA_RADIUS) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - AREA_RADIUS) >> 4; chunkZ <= (center.getZ() + AREA_RADIUS) >> 4; chunkZ++) {
                // A chunk with no flower in it is passed over without looking at a block.
                if (!WorkerAutoJobs.chunkMayHave(level, chunkX, chunkZ, minY, maxY, isFlower)) {
                    continue;
                }
                for (int x = Math.max(chunkX << 4, center.getX() - AREA_RADIUS); x <= Math.min((chunkX << 4) + 15, center.getX() + AREA_RADIUS); x++) {
                    for (int z = Math.max(chunkZ << 4, center.getZ() - AREA_RADIUS); z <= Math.min((chunkZ << 4) + 15, center.getZ() + AREA_RADIUS); z++) {
                        if (!HiveArea.containsXZ(heart, x + 0.5D, z + 0.5D)) {
                            continue;
                        }
                        for (int y = minY; y <= maxY; y++) {
                            pos.set(x, y, z);
                            if (found.size() < LIST_MAX && level.getBlockState(pos).is(BlockTags.FLOWERS)) {
                                found.add(pos.immutable());
                            }
                        }
                    }
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(flower -> flower.distSqr(center)));
        FLOWER_LISTS.put(heart.getUUID(), new FlowerList(level.getGameTime(), found));
        return found;
    }

    /** The flower nearest the Heart that no other feeder is working and this one has not given up on, or null. */
    @Nullable
    private BlockPos findFlower(ServerLevel level, HiveHeart heart) {
        for (BlockPos pos : flowersAround(level, heart)) {
            if (!ignored.containsKey(pos) && !claimedByOther(pos) && level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.FLOWERS)) {
                return pos;
            }
        }
        return null;
    }


    @Override
    public boolean canContinueToUse() {
        HiveHeart heart = allowedHeart();
        if (heart == null || flower == null) {
            return false;
        }
        // The flower has to be there until the pollen is taken; after that it only matters where the Heart is.
        return phase == Phase.TO_HEART || feeder.level().getBlockState(flower).is(BlockTags.FLOWERS);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        phase = Phase.TO_FLOWER;
        timer = 0;
        stuckTicks = 0;
        repathCooldown = 0;
    }

    @Override
    public void stop() {
        feeder.getNavigation().stop();
        flower = null;
    }

    /** Fly towards a point, the way the feeder's other jobs do. True once it is close enough. */
    private boolean flyTo(Vec3 spot) {
        if (feeder.position().distanceToSqr(spot) <= CLOSE_SQR) {
            stuckTicks = 0;
            return true;
        }
        ++stuckTicks;
        if (--repathCooldown <= 0) {
            feeder.getNavigation().moveTo(spot.x, spot.y, spot.z, SPEED);
            repathCooldown = REPATH_INTERVAL;
        }
        return false;
    }

    @Override
    public void tick() {
        HiveHeart heart = allowedHeart();
        if (heart == null || flower == null || !(feeder.level() instanceof ServerLevel level)) {
            return;
        }
        switch (phase) {
            case TO_FLOWER -> {
                claim(flower);
                Vec3 center = Vec3.atCenterOf(flower);
                if (flyTo(new Vec3(center.x, flower.getY() + HOVER_HEIGHT, center.z))) {
                    phase = Phase.POLLINATE;
                    timer = POLLINATE_TICKS;
                    feeder.getNavigation().stop();
                } else if (stuckTicks > GIVE_UP_TICKS) {
                    ignored.put(flower, feeder.tickCount + IGNORE_TICKS);
                    flower = null;
                }
            }
            case POLLINATE -> {
                claim(flower);
                Vec3 center = Vec3.atCenterOf(flower);
                feeder.getLookControl().setLookAt(center);
                if (timer % 10 == 0) {
                    level.sendParticles(ParticleTypes.FALLING_NECTAR, center.x, center.y + 0.4D, center.z, 3, 0.2D, 0.1D, 0.2D, 0.0D);
                }
                if (--timer <= 0) {
                    phase = Phase.TO_HEART;
                    stuckTicks = 0;
                    repathCooldown = 0;
                }
            }
            case TO_HEART -> {
                Vec3 home = new Vec3(heart.getX(), heart.getY() + HOME_HEIGHT, heart.getZ());
                // Carrying pollen: a little drip of nectar behind it.
                if (feeder.tickCount % 8 == 0) {
                    level.sendParticles(ParticleTypes.FALLING_NECTAR, feeder.getX(), feeder.getY() + 0.2D, feeder.getZ(), 1, 0.1D, 0.0D, 0.1D, 0.0D);
                }
                if (flyTo(home)) {
                    heart.fluids().add(HiveFluids.HONEY, HONEY_PER_TRIP);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, home.x, home.y, home.z, 6, 0.3D, 0.3D, 0.3D, 0.0D);
                    level.playSound(null, heart.blockPosition(), SoundEvents.BEEHIVE_EXIT, SoundSource.NEUTRAL, 0.4F, 1.0F);
                    flower = null;
                    nextScan = feeder.tickCount;
                } else if (stuckTicks > GIVE_UP_TICKS) {
                    // It cannot get there: the pollen is given up and it starts again.
                    flower = null;
                }
            }
        }
    }
}
