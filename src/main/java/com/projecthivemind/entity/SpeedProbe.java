package com.projecthivemind.entity;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * TEMPORARY, for tuning unit speeds: writes the real walking speed of a unit, in blocks per second, to the game log
 * while it is walking. Only whole two-second stretches of continuous walking are reported, so stops do not drag the
 * number down. Remove once the speeds are settled.
 */
public final class SpeedProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WINDOW_TICKS = 40;

    private final String label;
    private Vec3 windowStart;
    private int windowStartTick;
    private boolean windowValid;

    public SpeedProbe(String label) {
        this.label = label;
    }

    /** Call once per server tick. */
    public void tick(Mob mob) {
        // A stretch only counts if the mob was walking for the whole of it.
        if (mob.getNavigation().isDone()) {
            windowValid = false;
        }
        if (windowStart == null || mob.tickCount - windowStartTick >= WINDOW_TICKS) {
            if (windowStart != null && windowValid) {
                Vec3 now = mob.position();
                double blocks = Math.hypot(now.x - windowStart.x, now.z - windowStart.z);
                double seconds = (mob.tickCount - windowStartTick) / 20.0D;
                if (blocks > 1.0D) {
                    LOGGER.info("[hivemind speed] {}: {} blocks/s measured, movement speed attribute {}",
                            label, String.format("%.2f", blocks / seconds),
                            String.format("%.3f", mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
                }
            }
            windowStart = mob.position();
            windowStartTick = mob.tickCount;
            windowValid = !mob.getNavigation().isDone();
        }
    }
}
