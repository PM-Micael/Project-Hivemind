package com.projecthivemind.entity;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.level.Level;

/**
 * A shulker bullet fired by the Hive Heart. It is the game's own (it homes in on its target, hurts it, and makes it float), except that it
 * only ever hits hostile mobs that are not the hive's: it passes through the hive's own units (which are hostile mobs by type), the Heart, and
 * everything that is not hostile.
 */
public class HiveShulkerBullet extends ShulkerBullet {
    public HiveShulkerBullet(Level level, LivingEntity shooter, Entity target, Direction.Axis axis) {
        super(level, shooter, target, axis);
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return entity instanceof Enemy && !HiveAttacks.spares(entity) && super.canHitEntity(entity);
    }
}
