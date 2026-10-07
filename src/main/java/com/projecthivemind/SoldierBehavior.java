package com.projecthivemind;

import net.minecraft.nbt.CompoundTag;

/**
 * How one soldier behaves when it has no orders. Each soldier has its own settings, edited from its page of the hive menu.
 *
 * <p>The two fighting options only look at mobs inside the hive border (the fixed area around the Heart, whose edge shows as
 * particles), wherever the soldier itself is. A soldier in a team only acts on them while the team's scout is inside the border.
 * Soldiers in a team also fight any mob that is hostile to another unit of the team: that is not an option, it is what the team is.
 *
 * @param allInHiveArea     attack every mob that is not part of a hive inside the hive border
 * @param hostileInHiveArea attack hostile mobs inside the hive border
 * @param stayInside        when not selected, always try to be inside the hive area: this wins over everything else
 * @param wander            walk about at random while idle, instead of standing still
 * @param stayAtHeart       when idle inside the border (and not wandering), walk to the Hive Heart and stand in it
 */
public record SoldierBehavior(boolean allInHiveArea, boolean hostileInHiveArea, boolean stayInside, boolean wander, boolean stayAtHeart) {
    /** A hive defends itself by default: hostile mobs in its area. */
    public static final SoldierBehavior DEFAULT = new SoldierBehavior(false, true, false, false, true);
    // The checkboxes as bits, for sending them in one number. (The values are those the soldier's settings were saved with before.)
    private static final int ALL_IN_HIVE = 1;
    private static final int HOSTILE_IN_HIVE = 2;
    private static final int STAY_INSIDE = 32;
    private static final int WANDER = 64;
    /** Saved when the option is OFF, so that a soldier saved before the option existed has it on. */
    private static final int NOT_AT_HEART = 128;

    /** The checkboxes packed into one number. */
    public int flags() {
        return (allInHiveArea ? ALL_IN_HIVE : 0) | (hostileInHiveArea ? HOSTILE_IN_HIVE : 0)
                | (stayInside ? STAY_INSIDE : 0) | (wander ? WANDER : 0) | (stayAtHeart ? 0 : NOT_AT_HEART);
    }

    /** Soldiers have no radii any more; the menu still sends and shows four, all zero. */
    public int[] radii() {
        return new int[4];
    }

    public static SoldierBehavior from(int flags, int[] radii) {
        return new SoldierBehavior((flags & ALL_IN_HIVE) != 0, (flags & HOSTILE_IN_HIVE) != 0, (flags & STAY_INSIDE) != 0, (flags & WANDER) != 0, (flags & NOT_AT_HEART) == 0);
    }

    /** True if an idle soldier has anything to look for at all. Staying inside and wandering are not something to look for. */
    public boolean any() {
        return allInHiveArea || hostileInHiveArea;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Flags", flags());
        tag.putIntArray("Radii", radii());
        return tag;
    }

    public static SoldierBehavior load(CompoundTag tag) {
        return tag.contains("Flags") ? from(tag.getInt("Flags"), new int[4]) : DEFAULT;
    }
}
