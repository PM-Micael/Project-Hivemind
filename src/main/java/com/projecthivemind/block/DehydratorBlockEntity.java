package com.projecthivemind.block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.projecthivemind.HiveFluids;
import com.projecthivemind.ModBlockEntities;
import com.projecthivemind.ModBlocks;
import com.projecthivemind.entity.HiveHeart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The work of a dehydrator. It makes a trail of dehydrated creep, and only the last block of the trail, the head, works:
 * <ol>
 * <li>It drains the fluid sources within one block of itself (the whole 3x3x3 around it) into the hive.</li>
 * <li>Then it waits {@link #DRAIN_INTERVAL} ticks, to give the fluid time to come back (a source that refills itself is drained again and again).</li>
 * <li>With no source within one block it looks over the 11x11x11 around itself. If there is a source there, it spreads one block towards it
 * (the new block is the head) and starts again; if there is none, it is done.</li>
 * </ol>
 * When it is done, all the dehydrated creep it made turns into plain creep and the dehydrator takes itself down.
 */
public class DehydratorBlockEntity extends BlockEntity {
    /** How often it looks at what to do, in ticks. */
    private static final int WORK_INTERVAL = 20;
    /** How long it waits after draining before it tries again, in ticks (5 seconds). */
    private static final int DRAIN_INTERVAL = 100;
    /** How far from the head it looks for a source to spread towards: 5 blocks each way, an 11x11x11. */
    private static final int SCAN_RADIUS = 5;
    /** The longest trail one dehydrator makes. */
    private static final int MAX_BLOCKS = 256;
    /** How many of the nearest sources it tries to find a way towards before it gives up. */
    private static final int TARGET_TRIES = 16;
    /** Keeps the chunks around the dehydrator loaded while it works (the chunk it is in and the ones next to it), looked after again every cycle. */
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> TICKET =
            net.minecraft.server.level.TicketType.create("projecthivemind_dehydrator", Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong), 60);

    /** The trail, oldest first: the last one is the head. */
    private final Set<BlockPos> blocks = new LinkedHashSet<>();
    /** Ticks it still waits before draining again. */
    private int cooldown;
    /** Set when it is done: the countdown to taking itself down. */
    private int finishing = -1;

    public DehydratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DEHYDRATOR.get(), pos, state);
    }

    /** Where it starts: the block it was put against becomes the first dehydrated creep. */
    public void begin(ServerLevel level, BlockPos first) {
        level.setBlock(first, ModBlocks.DEHYDRATED_CREEP.get().defaultBlockState(), Block.UPDATE_ALL);
        blocks.add(first.immutable());
        setChanged();
    }

    /** True if this block can be turned into dehydrated creep: a solid full block that is not the hive's own or unbreakable. */
    public static boolean convertible(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced() && state.getDestroySpeed(level, pos) >= 0.0F
                && level.getBlockEntity(pos) == null && state.isCollisionShapeFullBlock(level, pos)
                && !state.is(ModBlocks.HIVE_PORTAL.get()) && !state.is(ModBlocks.CONSTRUCTION.get())
                && !state.is(ModBlocks.DEHYDRATED_CREEP.get()) && !state.is(ModBlocks.DEHYDRATOR.get());
    }

    /** True if any of the six blocks around this one holds a fluid (a waterlogged block counts). */
    public static boolean touchesFluid(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DehydratorBlockEntity entity) {
        if (level instanceof ServerLevel serverLevel && level.getGameTime() % WORK_INTERVAL == 0) {
            entity.work(serverLevel, pos);
        }
    }

    @Nullable
    private BlockPos head() {
        BlockPos head = null;
        for (BlockPos block : blocks) {
            head = block;
        }
        return head;
    }

    private void work(ServerLevel level, BlockPos self) {
        // One chunk each way around it stays loaded while it works, so that it can go on when nobody is near.
        net.minecraft.world.level.ChunkPos home = new net.minecraft.world.level.ChunkPos(self);
        level.getChunkSource().addRegionTicket(TICKET, home, 3, home);
        if (finishing >= 0) {
            if (--finishing <= 0) {
                level.sendParticles(ParticleTypes.CLOUD, self.getX() + 0.5D, self.getY() + 0.5D, self.getZ() + 0.5D, 8, 0.3D, 0.3D, 0.3D, 0.02D);
                level.removeBlock(self, false);
            }
            return;
        }
        // No hive to give the fluid to: it waits.
        HiveHeart heart = HiveHeart.dehydratorHeartAt(level, self);
        if (heart == null) {
            return;
        }
        // Trail blocks that have been mined or replaced are forgotten.
        blocks.removeIf(block -> !level.getBlockState(block).is(ModBlocks.DEHYDRATED_CREEP.get()));
        BlockPos head = head();
        if (head == null) {
            finish(level);
            return;
        }
        if (cooldown > 0) {
            cooldown -= WORK_INTERVAL;
            return;
        }
        setChanged();
        // 1. Drain the sources within one block of the head.
        int drained = 0;
        boolean full = false;
        for (BlockPos around : BlockPos.betweenClosed(head.offset(-1, -1, -1), head.offset(1, 1, 1))) {
            FluidState fluid = level.getFluidState(around);
            if (fluid.isEmpty() || !fluid.isSource()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid.getType());
            if (heart.fluids().add(id, HiveFluids.BUCKET) <= 0) {
                full = true;
                continue;
            }
            BlockPos at = around.immutable();
            BlockState state = level.getBlockState(at);
            if (state.getBlock() instanceof BucketPickup pickup && !(state.getBlock() instanceof LiquidBlock)) {
                pickup.pickupBlock(null, level, at, state);
            } else {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.sendParticles(ParticleTypes.SPLASH, at.getX() + 0.5D, at.getY() + 0.8D, at.getZ() + 0.5D, 6, 0.3D, 0.2D, 0.3D, 0.0D);
            drained++;
        }
        if (drained > 0) {
            level.playSound(null, head, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.5F, 1.2F);
        }
        if (drained > 0 || full) {
            // It waits, for the fluid to come back if it will (or for room in the meter), and then tries again.
            cooldown = DRAIN_INTERVAL;
            return;
        }
        // 2. Nothing within one block: look for a source in the 11x11x11, and spread one block towards the nearest one it can get to.
        List<BlockPos> sources = new ArrayList<>();
        boolean fullSource = false;
        for (BlockPos around : BlockPos.betweenClosed(head.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS), head.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (!level.isLoaded(around)) {
                continue;
            }
            FluidState fluid = level.getFluidState(around);
            if (!fluid.isSource()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid.getType());
            if (HiveFluids.capacity(id) - heart.fluids().amount(id) < HiveFluids.BUCKET) {
                // The hive's container for this fluid is full: it is not spread towards.
                fullSource = true;
            } else {
                sources.add(around.immutable());
            }
        }
        if (sources.isEmpty() && fullSource) {
            // Fluid is there but the hive has no room for any of it: the process pauses (nothing is destroyed) and looks again in a few seconds.
            cooldown = DRAIN_INTERVAL;
            return;
        }
        if (sources.isEmpty() || blocks.size() >= MAX_BLOCKS) {
            finish(level);
            return;
        }
        // It tries to stay in its own chunk: sources there come first, and it only goes after the others when none are left in it.
        List<BlockPos> inHome = sources.stream().filter(source -> new net.minecraft.world.level.ChunkPos(source).equals(home)).toList();
        if (!inHome.isEmpty()) {
            sources = new ArrayList<>(inHome);
        }
        sources.sort(Comparator.comparingDouble(source -> source.distSqr(head)));
        for (int i = 0; i < Math.min(TARGET_TRIES, sources.size()); i++) {
            BlockPos step = stepTowards(level, head, sources.get(i), home);
            if (step != null) {
                level.setBlock(step, ModBlocks.DEHYDRATED_CREEP.get().defaultBlockState(), Block.UPDATE_ALL);
                blocks.add(step);
                return;
            }
        }
        // Sources there, but no way towards any of them over solid ground: nothing more it can do.
        finish(level);
    }

    /** Ground that a construction of the hive uses is not turned into creep. */
    private boolean protectedByConstruction(BlockPos pos) {
        HiveHeart heart = level == null ? null : HiveHeart.dehydratorHeartAt(level, pos);
        return heart != null && heart.constructions().isProtected(level.dimension(), pos.getX(), pos.getZ());
    }

    /** The block next to the head (in the 3x3x3) that is nearer to the target than the head is and can be turned into creep, the nearest of them and one in the home chunk by preference; or null. */
    @Nullable
    private BlockPos stepTowards(ServerLevel level, BlockPos head, BlockPos target, net.minecraft.world.level.ChunkPos home) {
        double limit = head.distSqr(target);
        double best = Double.MAX_VALUE;
        BlockPos choice = null;
        for (BlockPos near : BlockPos.betweenClosed(head.offset(-1, -1, -1), head.offset(1, 1, 1))) {
            if (blocks.contains(near) || !level.isLoaded(near) || !convertible(level, near) || protectedByConstruction(near)) {
                continue;
            }
            double distance = near.distSqr(target);
            if (distance >= limit) {
                continue;
            }
            // A step that stays in the dehydrator's own chunk beats any step out of it.
            double score = distance + (new net.minecraft.world.level.ChunkPos(near).equals(home) ? 0.0D : 1.0E6D);
            if (score < best) {
                best = score;
                choice = near.immutable();
            }
        }
        return choice;
    }

    /** No source is left in reach: the dehydrated creep becomes plain creep, and the dehydrator goes in a few seconds. */
    private void finish(ServerLevel level) {
        for (BlockPos block : blocks) {
            if (level.getBlockState(block).is(ModBlocks.DEHYDRATED_CREEP.get())) {
                level.setBlock(block, ModBlocks.CREEP_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        blocks.clear();
        finishing = 3;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (BlockPos block : blocks) {
            CompoundTag entry = new CompoundTag();
            entry.put("P", NbtUtils.writeBlockPos(block));
            list.add(entry);
        }
        tag.put("Blocks", list);
        tag.putInt("Cooldown", cooldown);
        tag.putInt("Finishing", finishing);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        blocks.clear();
        for (Tag raw : tag.getList("Blocks", Tag.TAG_COMPOUND)) {
            NbtUtils.readBlockPos((CompoundTag) raw, "P").ifPresent(blocks::add);
        }
        cooldown = tag.getInt("Cooldown");
        finishing = tag.contains("Finishing") ? tag.getInt("Finishing") : -1;
    }

    @Nullable
    public static DehydratorBlockEntity at(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof DehydratorBlockEntity entity ? entity : null;
    }
}
