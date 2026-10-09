package com.projecthivemind;

import java.util.List;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.network.OpenBookPayload;
import com.projecthivemind.network.OpenSignPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the scout does with the item in its hand. The scout is how the hivemind does the things a player does with the
 * items in the player's own inventory, which the hivemind does not have: it places single blocks, reads a book, puts up
 * and writes on a sign, throws an ender pearl or an eye of ender.
 *
 * <p>Blocks and items only know how to be used by a player, so it is done by a fake player standing where the scout
 * stands, holding the scout's item; what is left of the item afterwards goes back into the scout's hand slot.
 */
public final class ScoutItems {
    /** How far from the clicked block a scout stands to use an item on it, in blocks. */
    public static final double REACH = 4.0D;
    /** The signs scouts have just put up and the player has yet to write on: sign position per owner, the latest only. */
    private static final java.util.Map<java.util.UUID, BlockPos> PENDING_SIGNS = new java.util.HashMap<>();

    private ScoutItems() {
    }

    /** True if using this item does not need the scout to be anywhere near the block it was ordered on. */
    public static boolean usedFromAfar(ItemStack stack) {
        return stack.is(Items.WRITTEN_BOOK) || stack.is(Items.WRITABLE_BOOK) || stack.is(Items.ENDER_PEARL) || stack.is(Items.ENDER_EYE);
    }

    /**
     * The same, for an order on this block: an eye of ender is only thrown in the air when the block is not an end portal frame, because
     * on a frame it is placed in it, which takes the scout standing next to the frame.
     */
    public static boolean usedFromAfar(ItemStack stack, net.minecraft.world.level.Level level, BlockPos pos) {
        return usedFromAfar(stack) && !(stack.is(Items.ENDER_EYE) && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.END_PORTAL_FRAME));
    }

    /** True if the scout can do something with this item (otherwise it is only being carried). */
    public static boolean usable(ItemStack stack) {
        return !stack.isEmpty();
    }

    /**
     * Use the scout's item on this face of this block now. Returns whether it did anything. What is left of the item
     * is put back in the hand slot, and the player is shown what a book or a sign needs showing.
     */
    public static boolean use(ServerLevel level, HiveHeart heart, HiveScout scout, ServerPlayer owner, BlockPos pos, Direction face) {
        ItemStack held = heart.scoutHeld();
        if (held.isEmpty()) {
            return false;
        }

        // A written book is read by the player, on their own screen.
        if (held.is(Items.WRITTEN_BOOK)) {
            PacketDistributor.sendToPlayer(owner, new OpenBookPayload(held.copy()));
            scout.swing(InteractionHand.MAIN_HAND);
            return true;
        }

        // A book and quill is written in, on the player's own book editor.
        if (held.is(Items.WRITABLE_BOOK)) {
            return openBookEditor(level, heart, scout, owner, held);
        }

        FakePlayer fake = FakePlayerFactory.get(level, HiveActions.FAKE_PROFILE);
        Vec3 hitPoint = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        // The fake player stands where the scout does, looking at the spot, so that what depends on which way the
        // user faces (stairs, logs, a thrown pearl) goes the right way.
        Vec3 eye = scout.getEyePosition();
        double dx = hitPoint.x - eye.x;
        double dy = hitPoint.y - eye.y;
        double dz = hitPoint.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        fake.moveTo(scout.getX(), scout.getY(), scout.getZ(), yaw, pitch);
        scout.setYRot(yaw);
        scout.setYHeadRot(yaw);
        // Sneaking, so that a block with its own use (a chest, a door) does not take the click instead of the item.
        fake.setShiftKeyDown(held.getItem() instanceof BlockItem);
        fake.setItemInHand(InteractionHand.MAIN_HAND, held.copy());

        InteractionResult result = InteractionResult.PASS;
        if (!usedFromAfar(held, level, pos)) {
            BlockHitResult hit = new BlockHitResult(hitPoint, face, pos, false);
            result = fake.gameMode.useItemOn(fake, level, fake.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        }
        if (!result.consumesAction()) {
            // Not something that is used on a block (or not on that one): use it in the air, like a thrown pearl.
            ItemStack inHand = fake.getMainHandItem();
            InteractionResultHolder<ItemStack> used = inHand.use(level, fake, InteractionHand.MAIN_HAND);
            if (used.getResult().consumesAction()) {
                fake.setItemInHand(InteractionHand.MAIN_HAND, used.getObject());
                result = used.getResult();
            }
        }
        if (!result.consumesAction()) {
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return false;
        }

        // A thrown pearl takes whoever threw it with it: that should be the scout, not the fake player.
        for (ThrownEnderpearl pearl : level.getEntitiesOfClass(ThrownEnderpearl.class, scout.getBoundingBox().inflate(3.0D),
                candidate -> candidate.getOwner() == fake)) {
            pearl.setOwner(scout);
        }

        // What is left of the item goes back in the hand slot.
        ItemStack left = fake.getMainHandItem();
        heart.setScoutHeld(left.isEmpty() ? ItemStack.EMPTY : left.copy());
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        fake.setShiftKeyDown(false);
        scout.swing(InteractionHand.MAIN_HAND);

        // A sign that has just gone up is written on by the player.
        BlockPos placed = pos.relative(face);
        BlockEntity blockEntity = level.getBlockEntity(placed);
        if (blockEntity instanceof SignBlockEntity) {
            PENDING_SIGNS.put(owner.getUUID(), placed.immutable());
            PacketDistributor.sendToPlayer(owner, new OpenSignPayload(placed.immutable()));
        }
        return true;
    }

    /**
     * A book being written by the player, for the scout: the slot of the player's own inventory the book was put in, the
     * book as it was put there, and when to give up waiting for it to be written.
     */
    private record BookSession(int slot, ItemStack original, long expires) {
    }

    private static final java.util.Map<java.util.UUID, BookSession> BOOK_SESSIONS = new java.util.HashMap<>();
    private static final long BOOK_SESSION_TICKS = 3600L;

    /**
     * The game's book editor works on the book in the player's hand, and sends back what was written for the player's
     * own inventory slot. The hivemind has no hands, so the scout's book is put in the camera's selected slot for the
     * time it is being written, and what comes of it is taken back into the scout's hand by {@link #tickBook}.
     */
    private static boolean openBookEditor(ServerLevel level, HiveHeart heart, HiveScout scout, ServerPlayer owner, ItemStack held) {
        net.minecraft.world.entity.player.Inventory inventory = owner.getInventory();
        int slot = inventory.selected;
        if (!inventory.getItem(slot).isEmpty()) {
            return false;
        }
        inventory.setItem(slot, held.copy());
        owner.inventoryMenu.broadcastChanges();
        BOOK_SESSIONS.put(owner.getUUID(), new BookSession(slot, held.copy(), level.getGameTime() + BOOK_SESSION_TICKS));
        owner.connection.send(new net.minecraft.network.protocol.game.ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
        scout.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Take back what was written in the scout's book, once it has been, or clear the slot if it never is. */
    public static void tickBook(HiveHeart heart, ServerPlayer owner) {
        BookSession session = BOOK_SESSIONS.get(owner.getUUID());
        if (session == null) {
            return;
        }
        ItemStack now = owner.getInventory().getItem(session.slot());
        boolean written = !now.isEmpty() && !ItemStack.matches(now, session.original());
        if (written && (now.is(Items.WRITABLE_BOOK) || now.is(Items.WRITTEN_BOOK))) {
            heart.setScoutHeld(now.copy());
        }
        if (written || now.isEmpty() || owner.level().getGameTime() >= session.expires()) {
            if (!now.isEmpty() && ItemStack.matches(now, session.original()) || written) {
                owner.getInventory().setItem(session.slot(), ItemStack.EMPTY);
                owner.inventoryMenu.broadcastChanges();
            }
            BOOK_SESSIONS.remove(owner.getUUID());
        }
    }

    /** The player wrote on the sign a scout put up: set the text, if that is still the sign and the scout is near it. */
    public static void writeSign(ServerPlayer owner, BlockPos pos, List<String> lines) {
        BlockPos pending = PENDING_SIGNS.get(owner.getUUID());
        if (pending == null || !pending.equals(pos)) {
            return;
        }
        HiveHeart heart = HivemindManager.findHeart(owner);
        ServerLevel level = owner.serverLevel();
        if (heart == null || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return;
        }
        // Only while one of the hive's scouts is by the sign.
        boolean scoutNear = false;
        for (java.util.UUID id : HivemindManager.get(owner).allUnits()) {
            if (level.getEntity(id) instanceof HiveScout scout && scout.isAlive() && scout.distanceToSqr(Vec3.atCenterOf(pos)) <= 12.0D * 12.0D) {
                scoutNear = true;
                break;
            }
        }
        if (!scoutNear) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.sign_too_far"), true);
            return;
        }
        SignText text = sign.getFrontText();
        for (int i = 0; i < 4; i++) {
            String line = i < lines.size() ? lines.get(i) : "";
            text = text.setMessage(i, Component.literal(line.length() > 90 ? line.substring(0, 90) : line));
        }
        sign.setText(text, true);
        sign.setChanged();
        level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), 3);
        PENDING_SIGNS.remove(owner.getUUID());
    }
}
