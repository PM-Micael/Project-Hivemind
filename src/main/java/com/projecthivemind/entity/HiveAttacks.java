package com.projecthivemind.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/**
 * The rule for everything the hive itself attacks with (the Heart's shulker bullets, and whatever comes later): it never touches the hive's
 * own units or Heart. New attacks should use {@link #spares} to pick what they may hit; anything that is a hive attack is also stopped
 * from damaging a hive unit at all, whatever path it takes (see CommonEvents#onHiveAttack).
 */
public final class HiveAttacks {
    private HiveAttacks() {
    }

    /** True for what a hive attack must leave alone: any hive unit, and any Heart. */
    public static boolean spares(Entity entity) {
        return entity instanceof HiveUnit || entity instanceof HiveHeart;
    }

    /** True if this damage comes from the hive's own attacks: its bullets, or anything the Heart itself did. */
    public static boolean isHiveAttack(DamageSource source) {
        return source.getDirectEntity() instanceof HiveShulkerBullet || source.getEntity() instanceof HiveHeart
                || (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow && arrow.getOwner() instanceof HiveUnit);
    }
}
