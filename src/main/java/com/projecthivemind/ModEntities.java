package com.projecthivemind;

import com.projecthivemind.entity.HiveCollector;
import com.projecthivemind.entity.HiveFeeder;
import com.projecthivemind.entity.HiveHeart;
import com.projecthivemind.entity.HiveScout;
import com.projecthivemind.entity.HiveSoldier;
import com.projecthivemind.entity.HiveWorker;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ProjectHivemind.MODID);

    // MISC so they never spawn naturally and don't count toward the monster mob cap.
    public static final DeferredHolder<EntityType<?>, EntityType<HiveHeart>> HIVE_HEART = ENTITY_TYPES.register("hive_heart",
            () -> EntityType.Builder.of(HiveHeart::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(10)
                    .build("hive_heart"));

    public static final DeferredHolder<EntityType<?>, EntityType<HiveScout>> HIVE_SCOUT = ENTITY_TYPES.register("hive_scout",
            () -> EntityType.Builder.of(HiveScout::new, MobCategory.MISC)
                    // A player's hitbox and eye height.
                    .sized(0.6F, 1.8F)
                    .eyeHeight(1.62F)
                    .passengerAttachments(1.8625F)
                    .ridingOffset(-0.6F)
                    .clientTrackingRange(8)
                    .build("hive_scout"));

    public static final DeferredHolder<EntityType<?>, EntityType<HiveWorker>> HIVE_WORKER = ENTITY_TYPES.register("hive_worker",
            () -> EntityType.Builder.of(HiveWorker::new, MobCategory.MISC)
                    .sized(0.6F, 1.99F)
                    .eyeHeight(1.74F)
                    .ridingOffset(-0.7F)
                    .clientTrackingRange(8)
                    .build("hive_worker"));

    public static final DeferredHolder<EntityType<?>, EntityType<HiveSoldier>> HIVE_SOLDIER = ENTITY_TYPES.register("hive_soldier",
            () -> EntityType.Builder.of(HiveSoldier::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .passengerAttachments(2.0125F)
                    .ridingOffset(-0.7F)
                    .clientTrackingRange(8)
                    .build("hive_soldier"));

    public static final DeferredHolder<EntityType<?>, EntityType<HiveCollector>> HIVE_COLLECTOR = ENTITY_TYPES.register("hive_collector",
            () -> EntityType.Builder.of(HiveCollector::new, MobCategory.MISC)
                    .sized(0.4F, 0.3F)
                    .eyeHeight(0.13F)
                    .passengerAttachments(0.2125F)
                    .clientTrackingRange(8)
                    .build("hive_collector"));

    public static final DeferredHolder<EntityType<?>, EntityType<HiveFeeder>> HIVE_FEEDER = ENTITY_TYPES.register("hive_feeder",
            () -> EntityType.Builder.of(HiveFeeder::new, MobCategory.MISC)
                    .sized(0.7F, 0.6F)
                    .eyeHeight(0.3F)
                    .clientTrackingRange(8)
                    .build("hive_feeder"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.projecthivemind.entity.HiveGolem>> HIVE_GOLEM = ENTITY_TYPES.register("hive_golem",
            () -> EntityType.Builder.of(com.projecthivemind.entity.HiveGolem::new, MobCategory.MISC)
                    .sized(1.4F, 2.7F)
                    .eyeHeight(2.2F)
                    .clientTrackingRange(10)
                    .build("hive_golem"));

    private ModEntities() {
    }
}
