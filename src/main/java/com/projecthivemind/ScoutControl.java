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
        /** The block being broken, and what it was when it started, so a different block starts over. */
        @Nullable
        BlockPos breaking;
        @Nullable
        BlockState breakingState;
        int breakDelay;
        int attackCooldown;
        int useCooldown;
        boolean useWasHeld;
        /** The hive's best weapon is already in the hand for the mob being hit, so it is not taken again with every blow. */
        boolean weaponHeld;
        /** A tool from the hive is in the hand for the block being broken, so an empty hand means it broke. */
        boolean toolHeld;
        List<ItemStack> lastSent = List.of();
        List<Integer> lastCounts = List.of();
        int lastSentSelected = -1;

        Session(UUID scoutId) {
            this.scoutId = scoutId;
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
        heart.setScoutSelected(0);
        scout.refreshHand();
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
        HiveHeart ownHeart = HivemindManager.findHeart(player);
        if (ownHeart != null) {
            ownHeart.setScoutSelected(0);
        }
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
        HiveHeart logoutHeart = HivemindManager.findHeart(player);
        if (session != null && logoutHeart != null) {
            logoutHeart.setScoutSelected(0);
        }
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
        heart.setScoutSelected(slot);
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
        boolean fresh = !session.useWasHeld;
        if (busy && scout.isUsingItem()) {
            scout.stopUsingItem(); // a menu opened: the draw is called off, not fired
        }
        // A bow or crossbow is used unless the click is for a villager or something that opens (as for a player, those come first).
        boolean opensSomething = (entityHit != null && entityHit.getEntity() instanceof AbstractVillager)
                || (entityHit == null && blockFound && !in.has(ControlInputPayload.SNEAK) && HiveAccess.canOpen(level, blockHit.getBlockPos()));
        boolean bowInHand = scout.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem
                || scout.getMainHandItem().getItem() instanceof net.minecraft.world.item.CrossbowItem;
        boolean drawing = scout.isUsingItem();
        if (bowInHand || drawing) {
            ranged(heart, scout, level, using, fresh, !opensSomething);
        }
        if (using && !(bowInHand && (drawing || !opensSomething))) {
            boolean repeat = session.useCooldown <= 0 && heart.scoutHeld().getItem() instanceof BlockItem;
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
        ItemStack held = heart.scoutHeld();
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


    // ---- the bow ----

    /** The first of the hive's stored items that this bow can fire (arrows, say), or empty. It is the stack in the storage itself. */
    private static ItemStack findAmmo(HiveHeart heart, ItemStack bow) {
        java.util.function.Predicate<ItemStack> supported = ((net.minecraft.world.item.ProjectileWeaponItem) bow.getItem()).getAllSupportedProjectiles();
        for (int i = 0; i < heart.getStorage().getContainerSize(); i++) {
            ItemStack stack = heart.getStorage().getItem(i);
            if (!stack.isEmpty() && supported.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Drawing and letting go of a bow, as a player does it: pressing use starts drawing (if the hive has something to fire), the scout slows while
     * it draws, and letting go fires with the power of how long it was drawn, using up ammo from the hive's storage and wearing the bow.
     */
    private static void bow(HiveHeart heart, HiveScout scout, ServerLevel level, boolean using, boolean fresh, boolean mayDraw) {
        ItemStack bow = scout.getMainHandItem();
        if (!(bow.getItem() instanceof net.minecraft.world.item.BowItem bowItem)) {
            return;
        }
        if (!scout.isUsingItem()) {
            if (using && fresh && mayDraw && !findAmmo(heart, bow).isEmpty()) {
                scout.startUsingItem(InteractionHand.MAIN_HAND);
            }
            return;
        }
        if (using) {
            return;
        }
        float power = net.minecraft.world.item.BowItem.getPowerForTime(bow.getUseDuration(scout) - scout.getUseItemRemainingTicks());
        scout.releaseUsingItem();
        ItemStack ammo = power < 0.1F ? ItemStack.EMPTY : findAmmo(heart, bow);
        if (ammo.isEmpty()) {
            return;
        }
        List<ItemStack> fired = net.minecraft.world.item.ProjectileWeaponItem.draw(bow, ammo, scout);
        heart.getStorage().setChanged();
        if (!fired.isEmpty()) {
            bowItem.shoot(level, scout, InteractionHand.MAIN_HAND, bow, fired, power * 3.0F, 1.0F, power == 1.0F, null);
        }
        level.playSound(null, scout.getX(), scout.getY(), scout.getZ(), net.minecraft.sounds.SoundEvents.ARROW_SHOOT,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F / (level.getRandom().nextFloat() * 0.4F + 1.2F) + power * 0.5F);
    }


    /**
     * A crossbow, as a player uses it: pressing use with it empty starts loading (if the hive has an arrow), letting go once it is fully
     * loaded puts the arrow in, and pressing use with it loaded fires. The arrow comes out of the hive's storage and the crossbow wears.
     */
    private static void crossbow(HiveHeart heart, HiveScout scout, ServerLevel level, boolean using, boolean fresh, boolean mayDraw) {
        ItemStack crossbow = scout.getMainHandItem();
        if (!(crossbow.getItem() instanceof net.minecraft.world.item.CrossbowItem crossbowItem)) {
            return;
        }
        if (!scout.isUsingItem()) {
            if (!using || !fresh || !mayDraw) {
                return;
            }
            if (net.minecraft.world.item.CrossbowItem.isCharged(crossbow)) {
                net.minecraft.world.item.component.ChargedProjectiles loaded =
                        crossbow.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES, net.minecraft.world.item.component.ChargedProjectiles.EMPTY);
                if (loaded != null && !loaded.isEmpty()) {
                    float power = loaded.contains(net.minecraft.world.item.Items.FIREWORK_ROCKET) ? 1.6F : 3.15F;
                    crossbowItem.shoot(level, scout, InteractionHand.MAIN_HAND, crossbow, loaded.getItems(), power, 1.0F, true, null);
                }
            } else if (!findAmmo(heart, crossbow).isEmpty()) {
                scout.startUsingItem(InteractionHand.MAIN_HAND);
            }
            return;
        }
        if (using) {
            return;
        }
        int drawn = crossbow.getUseDuration(scout) - scout.getUseItemRemainingTicks();
        boolean full = drawn >= net.minecraft.world.item.CrossbowItem.getChargeDuration(crossbow, scout);
        scout.releaseUsingItem();
        ItemStack ammo = full && !net.minecraft.world.item.CrossbowItem.isCharged(crossbow) ? findAmmo(heart, crossbow) : ItemStack.EMPTY;
        if (ammo.isEmpty()) {
            return;
        }
        List<ItemStack> loaded = net.minecraft.world.item.ProjectileWeaponItem.draw(crossbow, ammo, scout);
        heart.getStorage().setChanged();
        if (!loaded.isEmpty()) {
            crossbow.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES, net.minecraft.world.item.component.ChargedProjectiles.of(loaded));
            level.playSound(null, scout.getX(), scout.getY(), scout.getZ(), net.minecraft.sounds.SoundEvents.CROSSBOW_LOADING_END,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F / (level.getRandom().nextFloat() * 0.5F + 1.0F) + 0.2F);
        }
    }

    private static void ranged(HiveHeart heart, HiveScout scout, ServerLevel level, boolean using, boolean fresh, boolean mayDraw) {
        if (scout.getMainHandItem().getItem() instanceof net.minecraft.world.item.CrossbowItem) {
            crossbow(heart, scout, level, using, fresh, mayDraw);
        } else {
            bow(heart, scout, level, using, fresh, mayDraw);
        }
    }

    // ---- the hotbar ----

    /**
     * The hotbar is the scouts' hotbar of the hive (nine slots, set up on the scouts' page of the hive menu): its first slot is the item the scouts
     * hold for the orders they are given, and the slot the player picks is the one in the scout's hand while it is controlled.
     */
    private static void syncHotbar(ServerPlayer player, Session session, HiveHeart heart, boolean force) {
        List<ItemStack> shown = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < ControlHotbarPayload.SLOTS; i++) {
            ItemStack stack = heart.scoutHand().getItem(i);
            shown.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
            counts.add(stack.getCount());
        }
        int selected = heart.scoutSelected();
        boolean changed = force || session.lastSentSelected != selected || !session.lastCounts.equals(counts)
                || !ItemStack.listMatches(shown, session.lastSent);
        if (changed) {
            session.lastSent = shown;
            session.lastCounts = counts;
            session.lastSentSelected = selected;
            PacketDistributor.sendToPlayer(player, new ControlHotbarPayload(shown, counts, selected));
        }
    }
}
