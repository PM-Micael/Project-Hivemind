package com.projecthivemind;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.menu.ScoutContainerMenu;
import com.projecthivemind.menu.ScoutTradeMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a scout can open for the hivemind: any block with an inventory (chests, barrels, furnaces, hoppers, shulker
 * boxes, brewing stands...) and any villager that has something to trade. The menus themselves are
 * {@link ScoutContainerMenu} and {@link ScoutTradeMenu}; this class finds the thing and opens them for the owner.
 */
public final class HiveAccess {
    private HiveAccess() {
    }

    /** The inventory of the block at this position, or null if it has none (or it is a chest with a block on top). */
    @Nullable
    public static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            // Null for a chest with a solid block on top, which cannot be opened in the real game either.
            return ChestBlock.getContainer(chest, state, level, pos, false);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    /** True if a scout could open the block at this position. */
    public static boolean canOpen(ServerLevel level, BlockPos pos) {
        Container container = containerAt(level, pos);
        return container != null && container.getContainerSize() <= ScoutContainerMenu.MAX_SLOTS;
    }

    /** True if this is a villager (or trader) that has offers to show. */
    public static boolean canTrade(AbstractVillager villager) {
        if (!villager.isAlive() || villager.isTrading() || villager.getOffers().isEmpty()) {
            return false;
        }
        if (villager instanceof Villager v) {
            return !v.isBaby() && v.getVillagerData().getProfession() != VillagerProfession.NITWIT;
        }
        return true;
    }

    /** Open the block's inventory for the owner, as seen by this scout. Returns false if it could not be opened. */
    public static boolean openContainer(ServerPlayer owner, HiveHeart heart, HiveScout scout, ServerLevel level, BlockPos pos) {
        Container container = containerAt(level, pos);
        if (container == null || container.getContainerSize() > ScoutContainerMenu.MAX_SLOTS) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.cannot_open"), true);
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof BaseContainerBlockEntity locked && !locked.canOpen(owner)) {
            return false;
        }
        // Opening a loot chest is what makes its loot appear.
        if (blockEntity instanceof RandomizableContainerBlockEntity loot) {
            loot.unpackLootTable(owner);
        }

        BlockState state = level.getBlockState(pos);
        Component title = state.getBlock() instanceof ChestBlock && container.getContainerSize() == 54
                ? Component.translatable("container.chestDouble")
                : blockEntity instanceof BaseContainerBlockEntity named ? named.getDisplayName() : state.getBlock().getName();
        int size = container.getContainerSize();
        return owner.openMenu(new SimpleMenuProvider(
                (id, inventory, player) -> new ScoutContainerMenu(id, container, heart.getStorage(), scout, heart, blockEntity, pos),
                title), buf -> {
            buf.writeVarInt(size);
            buf.writeVarInt(heart.getStorage().getContainerSize());
        }).isPresent();
    }

    /** Open the villager's trades for the owner, as seen by this scout. Returns false if it could not be opened. */
    public static boolean openTrade(ServerPlayer owner, HiveHeart heart, HiveScout scout, AbstractVillager villager) {
        if (!canTrade(villager)) {
            owner.displayClientMessage(Component.translatable("message.projecthivemind.cannot_trade"), true);
            return false;
        }
        ScoutTradeMenu[] opened = new ScoutTradeMenu[1];
        boolean ok = owner.openMenu(new SimpleMenuProvider((id, inventory, player) -> {
            opened[0] = new ScoutTradeMenu(id, heart.getStorage(), villager, scout, heart);
            return opened[0];
        }, villager.getDisplayName()), buf -> buf.writeVarInt(heart.getStorage().getContainerSize())).isPresent();
        if (ok && opened[0] != null) {
            opened[0].startTrading(owner);
            opened[0].sendOffers(owner);
        }
        return ok;
    }
}
