package com.projecthivemind;

import java.util.UUID;

import javax.annotation.Nullable;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveSoldier;

import net.minecraft.core.Holder;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;

/**
 * What the hive can equip its soldiers with. The Heart holds armor (one piece per slot) and five tool/weapon slots.
 * Each new soldier gets a copy of that gear, linked back to the original so wear on the copy wears the original.
 */
public final class HiveEquipment {
    /** Order of the four armor slots in the hive menu, top to bottom. */
    public static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    public static final int TOOL_SLOTS = 5;

    private HiveEquipment() {
    }

    /** True if this item is worn in the given armor slot. */
    public static boolean isArmorFor(ItemStack stack, EquipmentSlot slot) {
        Equipable equipable = Equipable.get(stack);
        return equipable != null && equipable.getEquipmentSlot() == slot;
    }

    /**
     * The numbers on an item's tooltip start from a bare-handed player: 1 attack damage and 4.0 attack speed. The
     * item's own modifiers are added to those, so a diamond sword shows 7 damage at 1.6 speed.
     */
    private static final double BASE_ATTACK_DAMAGE = 1.0D;
    private static final double BASE_ATTACK_SPEED = 4.0D;

    /** What a main-hand attribute comes to with this item held, applied the way the game applies modifiers. */
    private static double mainHandValue(ItemStack stack, Holder<Attribute> attribute, double base) {
        double[] added = {0.0D};
        double[] multipliedBase = {0.0D};
        double[] multipliedTotal = {1.0D};
        stack.getAttributeModifiers().forEach(EquipmentSlotGroup.MAINHAND, (modified, modifier) -> {
            if (!modified.is(attribute)) {
                return;
            }
            switch (modifier.operation()) {
                case ADD_VALUE -> added[0] += modifier.amount();
                case ADD_MULTIPLIED_BASE -> multipliedBase[0] += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> multipliedTotal[0] *= 1.0D + modifier.amount();
            }
        });
        double value = base + added[0];
        return (value + value * multipliedBase[0]) * multipliedTotal[0];
    }

    /** True if holding this item changes the attack damage at all, which is what makes something a weapon. */
    public static boolean isWeapon(ItemStack stack) {
        boolean[] weapon = {false};
        stack.getAttributeModifiers().forEach(EquipmentSlotGroup.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                weapon[0] = true;
            }
        });
        return weapon[0];
    }

    /**
     * Average damage per second with this item: damage per hit times hits per second (the attack speed). Enchantments
     * are not counted; this is the plain tool, as its tooltip describes it.
     */
    public static double averageDps(ItemStack stack) {
        double damage = mainHandValue(stack, Attributes.ATTACK_DAMAGE, BASE_ATTACK_DAMAGE);
        double speed = mainHandValue(stack, Attributes.ATTACK_SPEED, BASE_ATTACK_SPEED);
        return damage * speed;
    }

    /** The five slots hold tools and weapons. */
    public static boolean isToolOrWeapon(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof DiggerItem || stack.getItem() instanceof ShearsItem || isWeapon(stack));
    }

    @Nullable
    public static UUID link(ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(ModComponents.HIVE_LINK.get());
    }

    /** Copy the piece in a hive slot, first stamping the original so the two stay linked. */
    private static ItemStack linkedCopy(SimpleContainer container, int index) {
        ItemStack original = container.getItem(index);
        if (original.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (link(original) == null) {
            original.set(ModComponents.HIVE_LINK.get(), UUID.randomUUID());
            container.setChanged();
        }
        // A full copy: same enchantments, name, trim, durability, everything.
        return original.copy();
    }

    /**
     * Equip a newly spawned soldier from the hive's slots: a copy of each armor piece, and a copy of the tool or weapon
     * with the best average damage per second ({@link #averageDps}) in its main hand. The soldier never drops any of it.
     */
    public static void equipSoldier(HiveSoldier soldier, HiveHeart heart) {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack copy = linkedCopy(heart.getArmorGear(), i);
            if (!copy.isEmpty()) {
                soldier.setItemSlot(ARMOR_SLOTS[i], copy);
            }
        }

        // Only weapons compete (things that change attack damage); on a tie the earlier slot wins.
        int best = -1;
        double bestDps = 0.0D;
        for (int i = 0; i < TOOL_SLOTS; i++) {
            ItemStack candidate = heart.getToolGear().getItem(i);
            if (!isWeapon(candidate)) {
                continue;
            }
            double dps = averageDps(candidate);
            if (dps > bestDps) {
                bestDps = dps;
                best = i;
            }
        }
        if (best >= 0) {
            soldier.setItemSlot(EquipmentSlot.MAINHAND, linkedCopy(heart.getToolGear(), best));
        }

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            soldier.setDropChance(slot, 0.0F);
        }
    }
}
