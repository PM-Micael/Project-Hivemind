package com.projecthivemind.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The actual items a tower is built from, fixed when it is ordered: the items its walls and deck can be made of, and the
 * stairs. Several materials can be chosen at once; whenever a block is placed, the one of the right kind the hive has
 * the most of is used, so the stock is used up evenly. For wood this is one particular kind (oak, spruce...), so wood
 * on its own is not a patchwork.
 */
public record TowerSet(List<Item> walls, List<Item> stairs) {
    /** The bit that stands for a material in a chosen-materials number. */
    public static int bit(TowerMaterial material) {
        return 1 << material.ordinal();
    }

    public static boolean has(int materials, TowerMaterial material) {
        return (materials & bit(material)) != 0;
    }

    /**
     * Pick the items for the chosen materials (the wood the hive has the most of, or oak if it has none), or empty if no material is chosen. The
     * hive does not have to hold any of them yet.
     */
    public static Optional<TowerSet> choose(int materials, Container storage, int wallsNeeded, int stairsNeeded) {
        List<Item> walls = new ArrayList<>();
        List<Item> stairs = new ArrayList<>();
        for (TowerMaterial material : TowerMaterial.values()) {
            if (!has(materials, material)) {
                continue;
            }
            switch (material) {
                case COBBLESTONE -> {
                    walls.add(Items.COBBLESTONE);
                    stairs.add(Items.COBBLESTONE_STAIRS);
                }
                case DEEPSLATE -> {
                    walls.add(Items.COBBLED_DEEPSLATE);
                    walls.add(Items.DEEPSLATE);
                    stairs.add(Items.COBBLED_DEEPSLATE_STAIRS);
                }
                case WOOD -> {
                    // The wood the hive has the most of; with none at all, oak, which the builders then wait for.
                    Item[] wood = bestWood(storage, wallsNeeded, stairsNeeded);
                    if (wood == null) {
                        wood = new Item[] {Items.OAK_PLANKS, Items.OAK_STAIRS};
                    }
                    walls.add(wood[0]);
                    stairs.add(wood[1]);
                }
            }
        }
        TowerSet set = new TowerSet(walls, stairs);
        // The hive need not hold any of it yet: the builders start anyway, and wait where they run out.
        if (walls.isEmpty() || stairs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(set);
    }

    /** The planks and matching stairs of the wooden kind there is the most to build with, or null. */
    @Nullable
    private static Item[] bestWood(Container storage, int wallsNeeded, int stairsNeeded) {
        List<Item> seen = new ArrayList<>();
        Item[] best = null;
        double bestScore = -1.0D;
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.PLANKS) || seen.contains(stack.getItem())) {
                continue;
            }
            seen.add(stack.getItem());
            Item stairs = woodenStairsFor(stack.getItem());
            if (stairs == null) {
                continue;
            }
            double score = Math.min(count(storage, stack.getItem()) / (double) Math.max(1, wallsNeeded),
                    count(storage, stairs) / (double) Math.max(1, stairsNeeded));
            if (score > bestScore) {
                bestScore = score;
                best = new Item[] {stack.getItem(), stairs};
            }
        }
        return best;
    }

    /** The wooden stairs that go with these planks (oak planks with oak stairs), if the game has them. */
    @Nullable
    private static Item woodenStairsFor(Item planks) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(planks);
        if (!id.getPath().endsWith("_planks")) {
            return null;
        }
        ResourceLocation stairsId = id.withPath(id.getPath().replace("_planks", "_stairs"));
        return BuiltInRegistries.ITEM.getOptional(stairsId).filter(item -> item.getDefaultInstance().is(ItemTags.WOODEN_STAIRS)).orElse(null);
    }

    public static int count(Container container, Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int count(Container container, List<Item> items) {
        int total = 0;
        for (Item item : items) {
            total += count(container, item);
        }
        return total;
    }

    /** How many wall blocks the container holds, of any of the wall items. */
    public int wallCount(Container container) {
        return count(container, walls);
    }

    /** How many stairs the container holds, of any of the stair items. */
    public int stairCount(Container container) {
        return count(container, stairs);
    }

    /** The wall item the container has the most of, or null if it has none. */
    @Nullable
    public Item availableWall(Container container) {
        return mostOf(container, walls);
    }

    /** The stair item the container has the most of, or null if it has none. */
    @Nullable
    public Item availableStair(Container container) {
        return mostOf(container, stairs);
    }

    @Nullable
    private static Item mostOf(Container container, List<Item> items) {
        Item best = null;
        int bestCount = 0;
        for (Item item : items) {
            int count = count(container, item);
            if (count > bestCount) {
                bestCount = count;
                best = item;
            }
        }
        return best;
    }

    /** True if this block is one of the tower's wall blocks. */
    public boolean isWallBlock(BlockState state) {
        for (Item wall : walls) {
            if (wall instanceof BlockItem blockItem && state.is(blockItem.getBlock())) {
                return true;
            }
        }
        return false;
    }
}
