package com.projecthivemind.mixin;

import com.projecthivemind.entity.HiveUnit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Spawners wake up when one of the hive's units comes within their range, as they do for a player. The game only counts players who
 * are not spectators, and the hivemind's camera always is one, so without this a unit could walk right up to a spawner and nothing
 * would happen.
 */
@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin {
    @Shadow
    private int requiredPlayerRange;

    @Inject(method = "isNearPlayer", at = @At("RETURN"), cancellable = true)
    private void projecthivemind$nearHiveUnit(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || level.isClientSide) {
            return;
        }
        double range = this.requiredPlayerRange;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        if (!level.getEntitiesOfClass(Mob.class, new AABB(x - range, y - range, z - range, x + range, y + range, z + range),
                mob -> mob instanceof HiveUnit && mob.isAlive() && mob.distanceToSqr(x, y, z) < range * range).isEmpty()) {
            cir.setReturnValue(true);
        }
    }
}
