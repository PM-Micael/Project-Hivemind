package com.projecthivemind.entity;

import com.projecthivemind.ProjectHivemind;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * How the rest of the world treats hive units. They are built on hostile mobs (a zombie, a skeleton...), which is why the game
 * would otherwise treat them as monsters. What is wanted instead:
 * <ul>
 * <li>Peaceful and neutral mobs (iron golems, snow golems, wolves...) leave them alone, unless a unit hurt them first.</li>
 * <li>Hostile mobs attack them, like they attack a player.</li>
 * </ul>
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID)
public final class UnitRelations {
    private UnitRelations() {
    }

    /** A mob that would target a hive unit and is not hostile gives up on it, unless that unit was the one that hurt it. */
    @SubscribeEvent
    static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (target instanceof HiveUnit && !(event.getEntity() instanceof Enemy) && event.getEntity().getLastHurtByMob() != target) {
            event.setCanceled(true);
        }
    }

    /** Every hostile mob that is not one of ours gets a goal to go after hive units. */
    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || !(mob instanceof Enemy) || mob instanceof HiveUnit) {
            return;
        }
        mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 10, true, false, other -> other instanceof HiveUnit));
    }
}
