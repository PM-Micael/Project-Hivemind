package com.projecthivemind.entity;

import java.util.UUID;

import com.projecthivemind.HiveEquipment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps a unit's gear and the originals in the Hive Heart in step. The gear on a unit is a copy of pieces in the hive;
 * whenever a copy loses durability, whether from damage taken or from being used, the same amount is charged to the
 * original. Call {@link #tick} every server tick.
 *
 * <p>Nothing here is saved: after a reload the current state simply becomes the new baseline.
 */
public final class GearMirror {
    private final UUID[] linkOf = new UUID[EquipmentSlot.values().length];
    private final int[] damageOf = new int[EquipmentSlot.values().length];
    private final int[] maxDamageOf = new int[EquipmentSlot.values().length];

    /**
     * Forget what each slot held. Call this just before deliberately changing a unit's gear, so the swap is not
     * mistaken for the old piece breaking or for damage.
     */
    public void reset() {
        java.util.Arrays.fill(linkOf, null);
    }

    public void tick(Mob mob, HiveHeart heart) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            int i = slot.ordinal();
            ItemStack stack = mob.getItemBySlot(slot);
            UUID link = HiveEquipment.link(stack);

            if (link != null && link.equals(linkOf[i])) {
                int lost = stack.getDamageValue() - damageOf[i];
                if (lost > 0) {
                    heart.damageLinked(link, lost);
                }
                damageOf[i] = stack.getDamageValue();
            } else {
                if (linkOf[i] != null && stack.isEmpty()) {
                    // The piece broke: the original gets whatever durability the copy still had.
                    heart.damageLinked(linkOf[i], maxDamageOf[i] - damageOf[i], false);
                }
                linkOf[i] = link;
                if (link != null) {
                    damageOf[i] = stack.getDamageValue();
                    maxDamageOf[i] = stack.getMaxDamage();
                }
            }
        }
    }
}
