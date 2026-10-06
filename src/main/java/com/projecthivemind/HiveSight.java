package com.projecthivemind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveUnit;

/**
 * The eyes of the hive: where each of its things that see is, and how far. They are all that is left of "hive sight": the client draws the
 * terrain fog from them. (Nothing is hidden by them any more, and no unit's work depends on them.)
 */
public final class HiveSight {
    /** One thing that sees: where from, and how far. */
    public record Eye(Vec3 position, double radius) {
    }

    private HiveSight() {
    }

    /** The eyes of the hive: the Heart's middle, and each of the owner's living units, each with its own radius. */
    public static List<Eye> eyes(ServerLevel level, ServerPlayer owner, HiveHeart heart) {
        HiveLevel hiveLevel = HiveLevels.get(heart.hiveLevel());
        List<Eye> eyes = new ArrayList<>();
        // The Heart only sees in its own dimension.
        if (heart.level() == level) {
            eyes.add(new Eye(heart.getBoundingBox().getCenter(), hiveLevel.heartSightRadius()));
        }
        for (UUID id : HivemindManager.get(owner).allUnits()) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive() && mob instanceof HiveUnit unit) {
                eyes.add(new Eye(mob.getEyePosition(), hiveLevel.sightRadius(unit.kind())));
            }
        }
        return eyes;
    }
}
