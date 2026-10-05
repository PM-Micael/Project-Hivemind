package com.projecthivemind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveAttacks;
import com.projecthivemind.entity.HiveDrops;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.network.ControlHotbarPayload;
import com.projecthivemind.network.ControlInputPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Taking control of a scout: the player plays from the scout's point of view, as they would play a normal character.
 *
 * <p>The player's own entity stays a spectator camera, which is how the hivemind always is. The game's own spectator "camera"
 * does the seeing: the player's view is the scout's eyes, and the player's entity follows the scout around, which also keeps the
 * chunks round the scout loaded. What the player presses is sent here every tick (see {@link ControlInputPayload}); a spectator
 * can do nothing in the world by itself, so the scout does it all: walking, jumping, breaking blocks with the hive's tools,
 * placing what it holds, hitting mobs, opening chests and trading.
 *
 * <p>The scout's hand is the hive's one hand slot, like for any scout. The hotbar is nine items the player picked out of the hive's
 * storage: choosing one puts the item that was in the hand back into the storage and takes that item out of it into the hand.
 */
public final class ScoutControl {
    /** How far a block can be reached from the scout's eyes, in blocks (a survival player's block reach). */
    public static final double BLOCK_REACH = 4.5D;
    /** How far a mob can be hit from the scout's eyes (a survival player's attack reach). */
    public static final double ENTITY_REACH = 3.0D;
    /** Ticks between repeated placings while the use button is held (a player's right-click delay). */
    private static final int USE_REPEAT_TICKS = 4;
    /** Ticks after a block breaks before the next one starts breaking (a player's is 5). */
    private static final int BREAK_DELAY_TICKS = 5;
    /** The input the client sent is dropped if it is older than this: a scout does not run on by itself when the client stops talking. */
    private static final int STALE_INPUT_TICKS = 5;
    private static final int HOTBAR_SYNC_INTERVAL = 5;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ScoutControl() {
    }

    /** What one controlled scout is doing, per owner. Nothing here is saved: control ends when the game does. */
    private static final class Session {
        final UUID scoutId;
        ControlInputPayload input = new ControlInputPayload(0.0F, 0.0F, 0, 0.0F, 0.0F);
        long inputTime;
        /** What each hotbar slot stands for: one of the item, or empty. */
        final ItemStack[] hotbar = new ItemStack[ControlHotbarPayload.SLOTS];
        int selected;
        /** The block being broken, and what it was when it started, so a different block starts over. */
        @Nullable
        BlockPos breaking;
        @Nullable
        BlockState breakingState;
        int breakDelay;
        int attackCooldown;
        int useCooldown;
        boolean useWasHeld;
        boolean handWasFull;
        /** The hive's best weapon is already in the hand for the mob being hit, so it is not taken again with every blow. */
        boolean weaponHeld;
        /** A tool from the hive is in the hand for the block being broken, so an empty hand means it broke. */
        boolean toolHeld;
        List<ItemStack> lastSent = List.of();
        List<Integer> lastCounts = List.of();
        int lastSentSelected = -1;

        Session(UUID scoutId) {
            this.scoutId = scoutId;
            java.util.Arrays.fill(hotbar, ItemStack.EMPTY);
        }
    }

    // ---- starting and stopping ----

    @Nullable
    private static HiveScout ownScout(ServerPlayer player, int entityId) {
        return HivemindManager.findById(player, entityId) instanceof HiveScout scout && scout.isAlive()
                && player.getUUID().equals(scout.ownerId()) ? scout : null;
    }

    /** The scout this player controls, or null. */
    @Nullable
    public static HiveScout controlled(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session == null || !(player.serverLevel().getEntity(session.scoutId) instanceof HiveScout scout) ? null : scout;
    }

    public static boolean isControlling(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /** The client asked to take control of a scout, or (-1) to let go. */
    public static void request(ServerPlayer player, int scoutId) {
        if (scoutId < 0) {
            stop(player);
            return;
        }
        HivemindData data = HivemindManager.get(player);
        if (data.stage() != HivemindStage.HIVE || data.normalInventory()) {
            return;
        }
        HiveScout scout = ownScout(player, scoutId);
        HiveHeart heart = HivemindManager.findHeart(player);
        if (scout == null || heart == null || scout.level() != player.level()) {
            player.displayClientMessage(Component.translatable("message.projecthivemind.control_failed"), true);
            return;
        }
        Session existing = SESSIONS.get(player.getUUID());
        if (existing != null) {
            if (existing.scoutId.equals(scout.getUUID())) {
                return;
            }
            stop(player);
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        // Whatever the scout was doing is dropped: from here on it does what the player does.
        scout.setAction(null);
        scout.getNavigation().stop();
        Session session = new Session(scout.getUUID());
        session.inputTime = player.level().getGameTime();
        fillHotbar(session, heart);
        SESSIONS.put(player.getUUID(), session);
        scout.setDrive(new HiveScout.Drive(0.0F, 0.0F, false, false, false, scout.getYRot(), scout.getXRot()));
        player.setCamera(scout);
        syncHotbar(player, session, heart, true);
    }

    /** Let go: the scout goes back to its own ways, and the camera goes to where the scout is. */
    public static void stop(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return;
        }
        Entity found = player.serverLevel().getEntity(session.scoutId);
        player.setCamera(null);
        if (found instanceof HiveScout scout) {
            clearCracks(scout, session);
            scout.setDrive(null);
            if (scout.isAlive()) {
                // Back to the strategy view, a little way off the scout, looking at it.
                HivemindManager.focusUnit(player, scout.getId());
                return;
            }
        }
        // The scout is gone: the camera rises over the spot, looking down at it.
        player.teleportTo(player.serverLevel(), player.getX(), player.getY() + 6.0D, player.getZ(), player.getYRot(), 35.0F);
    }

    /** The player left the game: nothing keeps controlling a scout for them. */
    public static void onLogout(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null && player.serverLevel().getEntity(session.scoutId) instanceof HiveScout scout) {
            clearCracks(scout, session);
            scout.setDrive(null);
        }
    }

    // ---- input ----

    /** What the client pressed this tick. Taken as it is, within sane limits. */
    public static void input(ServerPlayer player, ControlInputPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        float yaw = Float.isFinite(payload.yaw()) ? payload.yaw() : 0.0F;
        float pitch = Float.isFinite(payload.pitch()) ? Mth.clamp(payload.pitch(), -90.0F, 90.0F) : 0.0F;
        float forward = Float.isFinite(payload.forward()) ? Mth.clamp(payload.forward(), -1.0F, 1.0F) : 0.0F;
        float strafe = Float.isFinite(payload.strafe()) ? Mth.clamp(payload.strafe(), -1.0F, 1.0F) : 0.0F;
        session.input = new ControlInputPayload(forward, strafe, payload.flags(), yaw, pitch);
        session.inputTime = player.level().getGameTime();
    }

    /** The player picked a hotbar slot. */
    public static void select(ServerPlayer player, int slot) {
        Session session = SESSIONS.get(player.getUUID());
        HiveHeart heart = HivemindManager.findHeart(player);
        if (session == null || heart == null || slot < 0 || slot >= ControlHotbarPayload.SLOTS) {
            return;
        }
        equip(session, heart, slot);
        if (player.serverLevel().getEntity(session.scoutId) instanceof HiveScout scout) {
            scout.refreshHand();
        }
        syncHotbar(player, session, heart, false);
    }

    // ---- the tick ----

    /** Every tick, for each player: keep their scout going as they say. */
    public static void tick(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        HiveHeart heart = HivemindManager.findHeart(player);
        HivemindData data = HivemindManager.get(player);
        if (!(player.serverLevel().getEntity(session.scoutId) instanceof HiveScout scout) || !scout.isAlive() || heart == null
                || data.stage() != HivemindStage.HIVE || data.normalInventory() || player.getCamera() != scout) {
            boolean died = !(player.serverLevel().getEntity(session.scoutId) instanceof HiveScout alive) || !alive.isAlive();
            stop(player);
            if (died) {
                player.displayClientMessage(Component.translatable("message.projecthivemind.control_ended"), true);
            }
            return;
        }
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        ControlInputPayload in = now - session.inputTime > STALE_INPUT_TICKS
                ? new ControlInputPayload(0.0F, 0.0F, 0, session.input.yaw(), session.input.pitch()) : session.input;
        // With a menu open (the hive's, a chest's) the player is not playing: the scout stands still and does nothing.
        boolean busy = player.containerMenu != player.inventoryMenu;
        if (busy) {
            in = new ControlInputPayload(0.0F, 0.0F, 0, in.yaw(), in.pitch());
        }
        scout.setDrive(new HiveScout.Drive(in.forward(), in.strafe(), in.has(ControlInputPayload.JUMP), in.has(ControlInputPayload.SNEAK),
                in.has(ControlInputPayload.SPRINT), in.yaw(), in.pitch()));

        keepHand(session, heart, scout);
        if (session.breakDelay > 0) {
            session.breakDelay--;
        }
        if (session.attackCooldown > 0) {
            session.attackCooldown--;
        }
        if (session.useCooldown > 0) {
            session.useCooldown--;
        }

        Vec3 eye = scout.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(in.pitch(), in.yaw());
        BlockHitResult blockHit = level.clip(new ClipContext(eye, eye.add(look.scale(BLOCK_REACH)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, scout));
        boolean blockFound = blockHit.getType() == HitResult.Type.BLOCK;
        EntityHitResult entityHit = pickEntity(level, scout, eye, look, blockFound ? Math.sqrt(blockHit.getLocation().distanceToSqr(eye)) : BLOCK_REACH);

        boolean attacking = in.has(ControlInputPayload.ATTACK) && !busy;
        boolean working = false;
        boolean hittingMob = false;
        if (attacking) {
            if (entityHit != null) {
                stopBreaking(scout, session);
                working = attack(session, heart, scout, entityHit.getEntity());
                hittingMob = working;
            } else if (blockFound) {
                working = breakBlock(session, heart, scout, level, blockHit.getBlockPos());
            } else {
                stopBreaking(scout, session);
            }
        } else {
            stopBreaking(scout, session);
        }
        if (!hittingMob) {
            session.weaponHeld = false;
        }
        scout.setControlWork(working);

        boolean using = in.has(ControlInputPayload.USE) && !busy;
        if (using) {
            boolean fresh = !session.useWasHeld;
            boolean repeat = session.useCooldown <= 0 && heart.scoutHand().getItem(0).getItem() instanceof BlockItem;
            if (fresh || repeat) {
                use(session, heart, scout, player, level, blockFound ? blockHit : null, entityHit, eye, look, in.has(ControlInputPayload.SNEAK));
                session.useCooldown = USE_REPEAT_TICKS;
            }
        }
        session.useWasHeld = using;

        if (now % HOTBAR_SYNC_INTERVAL == 0) {
            syncHotbar(player, session, heart, false);
        }
    }

    // ---- hitting things ----

    @Nullable
    private static EntityHitResult pickEntity(ServerLevel level, HiveScout scout, Vec3 eye, Vec3 look, double blockDistance) {
        double reach = Math.min(ENTITY_REACH, blockDistance);
        Vec3 end = eye.add(look.scale(reach));
        AABB box = scout.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0D);
        return ProjectileUtil.getEntityHitResult(scout, eye, end, box,
                entity -> !entity.isSpectator() && entity.isPickable() && !HiveAttacks.spares(entity), reach * reach);
    }

    /** Hit the mob with the hive's best weapon, as often as the weapon allows. True while there is something to hit. */
    private static boolean attack(Session session, HiveHeart heart, HiveScout scout, Entity target) {
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (!session.weaponHeld) {
            scout.holdBestWeapon(heart);
            session.weaponHeld = true;
        }
        if (session.attackCooldown <= 0) {
            ItemStack weapon = scout.getMainHandItem();
            scout.swing(InteractionHand.MAIN_HAND);
            boolean hit = scout.doHurtTarget(living);
            heart.food().exhaust(HiveFood.ATTACK);
            if (hit && !weapon.isEmpty()) {
                weapon.getItem().postHurtEnemy(weapon, living, scout);
            }
            session.attackCooldown = Math.max(4, (int) Math.round(20.0D / HiveEquipment.attackSpeed(weapon)));
        }
        return true;
    }

    // ---- breaking blocks ----

    /**
     * Work at a block as a player does: the hive's best tool for it in hand, progress by the same formula (a tool the block needs, slower
     * in the air and under water), the block's drops going to the hive. True while there is something being broken.
     */
    private static boolean breakBlock(Session session, HiveHeart heart, HiveScout scout, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            stopBreaking(scout, session);
            return false;
        }
        if (session.breakDelay > 0) {
            return true; // still at it: the tool stays in the hand between one block and the next
        }
        boolean toolBroke = session.toolHeld && scout.getMainHandItem().isEmpty();
        if (!pos.equals(session.breaking) || session.breakingState == null || session.breakingState.getBlock() != state.getBlock() || toolBroke) {
            stopBreaking(scout, session);
            session.breaking = pos.immutable();
            session.breakingState = state;
            scout.holdBestToolFor(heart, state);
            session.toolHeld = scout.holdsHiveTool();
        }
        ItemStack tool = scout.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float hardness = state.getDestroySpeed(level, pos);
        float speed = HiveEquipment.miningSpeed(tool, state, level.registryAccess());
        if (!scout.onGround()) {
            speed /= 5.0F;
        }
        if (scout.isEyeInFluid(FluidTags.WATER)) {
            speed /= 5.0F;
        }
        float progress = heart.addDigProgress(pos, speed / hardness / (correct ? 30.0F : 100.0F));
        if (scout.tickCount % 4 == 0) {
            scout.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(scout.getId(), pos, Math.min(9, (int) (progress * 10.0F)));
        if (progress >= 1.0F) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (correct) {
                HiveDrops.store(level, heart, pos, state, blockEntity, scout, tool);
            }
            level.destroyBlock(pos, false, scout);
            heart.food().exhaust(HiveFood.BREAK_BLOCK);
            tool.hurtAndBreak(1, scout, EquipmentSlot.MAINHAND);
            heart.clearDigProgress(pos);
            clearCracks(scout, session);
            session.breaking = null;
            session.breakingState = null;
            session.breakDelay = BREAK_DELAY_TICKS;
        }
        return true;
    }

    private static void stopBreaking(HiveScout scout, Session session) {
        if (session.breaking != null) {
            // The progress is kept in the Heart (workers share it), so it is wiped so the next try starts from nothing, as a player's does.
            HiveHeart heart = scout.findHeart();
            if (heart != null) {
                heart.clearDigProgress(session.breaking);
            }
            clearCracks(scout, session);
            session.breaking = null;
            session.breakingState = null;
        }
    }

    private static void clearCracks(HiveScout scout, Session session) {
        if (session.breaking != null && scout.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(scout.getId(), session.breaking, -1);
        }
    }

    // ---- using things ----

    /** One right click: what is looked at is opened or traded with, or else what the scout holds is used on it (or in the air). */
    private static void use(Session session, HiveHeart heart, HiveScout scout, ServerPlayer player, ServerLevel level,
            @Nullable BlockHitResult block, @Nullable EntityHitResult entity, Vec3 eye, Vec3 look, boolean sneaking) {
        if (entity != null && entity.getEntity() instanceof AbstractVillager villager) {
            if (HiveAccess.canTrade(villager)) {
                HiveAccess.openTrade(player, heart, scout, villager);
            }
            return;
        }
        if (entity != null) {
            return;
        }
        ItemStack held = heart.scoutHand().getItem(0);
        if (block != null) {
            BlockPos pos = block.getBlockPos();
            Direction face = block.getDirection();
            // A block with an inventory opens, unless the player is sneaking (to put something against it instead).
            if (!sneaking && HiveAccess.canOpen(level, pos)) {
                HiveAccess.openContainer(player, heart, scout, level, pos);
                return;
            }
            if (ScoutItems.usable(held)) {
                ScoutItems.use(level, heart, scout, player, pos, face);
            }
            return;
        }
        // Nothing in reach: only what is used in the air does anything (a pearl, an eye of ender, a book).
        if (ScoutItems.usable(held) && ScoutItems.usedFromAfar(held)) {
            Vec3 far = eye.add(look.scale(16.0D));
            ScoutItems.use(level, heart, scout, player, BlockPos.containing(far), Direction.UP);
        }
    }

    // ---- the hotbar ----

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    /** At the start: the item in the hand first, then what the storage holds, one slot to each kind of item. */
    private static void fillHotbar(Session session, HiveHeart heart) {
        int next = 0;
        ItemStack hand = heart.scoutHand().getItem(0);
        if (!hand.isEmpty()) {
            session.hotbar[next++] = hand.copyWithCount(1);
        }
        for (int i = 0; i < heart.getStorage().getContainerSize() && next < session.hotbar.length; i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (stack.isEmpty() || HiveEquipment.isToolOrWeapon(stack) || HiveEquipment.link(stack) != null) {
                continue;
            }
            boolean listed = false;
            for (int j = 0; j < next; j++) {
                listed |= same(session.hotbar[j], stack);
            }
            if (!listed) {
                session.hotbar[next++] = stack.copyWithCount(1);
            }
        }
        session.selected = 0;
    }

    /** How many of this item the hive has, in its storage and in the scout's hand. */
    private static int countOf(HiveHeart heart, ItemStack template) {
        int total = 0;
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (same(stack, template)) {
                total += stack.getCount();
            }
        }
        ItemStack hand = heart.scoutHand().getItem(0);
        return same(hand, template) ? total + hand.getCount() : total;
    }

    /**
     * Put the hand's item back into the storage and take the slot's item out of it, a stack's worth. False if the storage has no room to
     * take the hand's item back (nothing changes then).
     */
    private static boolean equip(Session session, HiveHeart heart, int slot) {
        ItemStack template = session.hotbar[slot];
        ItemStack hand = heart.scoutHand().getItem(0);
        session.selected = slot;
        if (same(hand, template) || (hand.isEmpty() && template.isEmpty())) {
            return true;
        }
        if (!hand.isEmpty()) {
            ItemStack left = heart.getStorage().addItem(hand.copy());
            if (!left.isEmpty()) {
                // No room: put back what did fit, keep the rest in hand.
                hand.setCount(left.getCount());
                heart.scoutHand().setChanged();
                return false;
            }
            heart.scoutHand().setItem(0, ItemStack.EMPTY);
        }
        if (!template.isEmpty()) {
            ItemStack taken = ItemStack.EMPTY;
            int got = 0;
            int limit = template.getMaxStackSize();
            for (int i = 0; i < heart.getStorage().getContainerSize() && got < limit; i++) {
                ItemStack stack = heart.getStorage().getItem(i);
                if (!same(stack, template)) {
                    continue;
                }
                if (taken.isEmpty()) {
                    taken = stack.copyWithCount(1);
                }
                int move = Math.min(limit - got, stack.getCount());
                got += move;
                stack.shrink(move);
                if (stack.isEmpty()) {
                    heart.getStorage().setItem(i, ItemStack.EMPTY);
                }
            }
            if (got > 0) {
                taken.setCount(got);
                heart.scoutHand().setItem(0, taken);
            }
        }
        heart.getStorage().setChanged();
        return true;
    }

    /**
     * Keep the selected slot and the hand in step. What the hand holds that the slot does not stand for (the player put it there from the hive
     * menu) becomes what the slot stands for; and a hand that was emptied by using the last of it is filled again if the hive has more.
     */
    private static void keepHand(Session session, HiveHeart heart, HiveScout scout) {
        ItemStack hand = heart.scoutHand().getItem(0);
        if (!hand.isEmpty() && !same(hand, session.hotbar[session.selected])) {
            session.hotbar[session.selected] = hand.copyWithCount(1);
        }
        if (hand.isEmpty() && session.handWasFull && !session.hotbar[session.selected].isEmpty()
                && countOf(heart, session.hotbar[session.selected]) > 0) {
            equip(session, heart, session.selected);
            scout.refreshHand();
        }
        session.handWasFull = !heart.scoutHand().getItem(0).isEmpty();
    }

    private static void syncHotbar(ServerPlayer player, Session session, HiveHeart heart, boolean force) {
        List<ItemStack> shown = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (ItemStack template : session.hotbar) {
            shown.add(template.copy());
            counts.add(template.isEmpty() ? 0 : countOf(heart, template));
        }
        // An item the hive has run out of is still shown, with a count of 0.
        boolean changed = force || session.lastSentSelected != session.selected || !session.lastCounts.equals(counts)
                || !ItemStack.listMatches(shown, session.lastSent);
        if (changed) {
            session.lastSent = shown;
            session.lastCounts = counts;
            session.lastSentSelected = session.selected;
            PacketDistributor.sendToPlayer(player, new ControlHotbarPayload(shown, counts, session.selected));
        }
    }
}
