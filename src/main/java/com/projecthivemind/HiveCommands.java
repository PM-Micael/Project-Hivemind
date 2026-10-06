package com.projecthivemind;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.projecthivemind.entity.HiveHeart;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The mod's commands (for operators; they act on the hive of the player who runs them):
 * <pre>
 * /hivemind level get
 * /hivemind level set &lt;level&gt;
 * /hivemind level up
 * /hivemind level down
 * /hivemind creep get
 * /hivemind spawnguard on|off|stats
 * /hivemind gather
 * /hivemind creep radius &lt;blocks&gt;
 * </pre>
 */
@EventBusSubscriber(modid = ProjectHivemind.MODID)
public final class HiveCommands {
    private HiveCommands() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hivemind").requires(source -> source.hasPermission(2))
                .then(Commands.literal("level")
                        .then(Commands.literal("get").executes(context -> get(context)))
                        .then(Commands.literal("set").then(Commands.argument("level", IntegerArgumentType.integer(1, HiveLevels.maxLevel()))
                                .executes(context -> set(context, IntegerArgumentType.getInteger(context, "level")))))
                        .then(Commands.literal("up").executes(context -> set(context, hiveOf(context) == null ? 0 : hiveOf(context).hiveLevel() + 1)))
                        .then(Commands.literal("down").executes(context -> set(context, hiveOf(context) == null ? 0 : hiveOf(context).hiveLevel() - 1))))
                .then(Commands.literal("gather").executes(context -> {
                    HiveHeart heart = hiveOf(context);
                    if (heart == null || !(context.getSource().getEntity() instanceof ServerPlayer player) || !(heart.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                        return 0;
                    }
                    java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                    for (java.util.UUID id : HivemindManager.get(player).allUnits()) {
                        if (level.getEntity(id) instanceof net.minecraft.world.entity.PathfinderMob mob && mob instanceof com.projecthivemind.entity.HiveUnit unit && mob.isAlive()) {
                            String reason = com.projecthivemind.entity.GatherAtHeartGoal.diagnose(mob);
                            // Distances differ for every unit: they are not part of what is counted.
                            counts.merge(unit.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + reason.replaceAll(" \\(.*\\)", ""), 1, Integer::sum);
                        }
                    }
                    counts.forEach((reason, number) -> context.getSource().sendSuccess(() -> Component.literal(number + " x " + reason), false));
                    return counts.size();
                }))
                .then(Commands.literal("spawnguard")
                        .then(Commands.literal("on").executes(context -> {
                            HiveSpawnGuard.enabled = true;
                            context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.spawnguard.on"), true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            HiveSpawnGuard.enabled = false;
                            context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.spawnguard.off"), true);
                            return 1;
                        }))
                        .then(Commands.literal("stats").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.spawnguard.stats", HiveSpawnGuard.enabled ? "on" : "off",
                                    HiveSpawnGuard.blocked.get(), HiveSpawnGuard.allowed.get()), false);
                            return 1;
                        })))
                .then(Commands.literal("creep")
                        .then(Commands.literal("get").executes(context -> {
                            HiveHeart heart = hiveOf(context);
                            if (heart == null) {
                                return 0;
                            }
                            context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.creep.get", String.format(java.util.Locale.ROOT, "%.1f", heart.creepRadius())), false);
                            return (int) heart.creepRadius();
                        }))
                        .then(Commands.literal("radius").then(Commands.argument("blocks", IntegerArgumentType.integer(0, 256)).executes(context -> {
                            HiveHeart heart = hiveOf(context);
                            if (heart == null) {
                                return 0;
                            }
                            heart.setCreepRadius(IntegerArgumentType.getInteger(context, "blocks"));
                            context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.creep.set", IntegerArgumentType.getInteger(context, "blocks")), true);
                            return 1;
                        })))));
    }

    /** The hive of the player who ran the command, or null (and the player is told why). */
    private static HiveHeart hiveOf(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.translatable("command.projecthivemind.hive.player_only"));
            return null;
        }
        HiveHeart heart = HivemindManager.findHeart(player);
        if (heart == null) {
            context.getSource().sendFailure(Component.translatable("command.projecthivemind.hive.no_hive"));
        }
        return heart;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        HiveHeart heart = hiveOf(context);
        if (heart == null) {
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.level.get", heart.hiveLevel(), HiveLevels.maxLevel()), false);
        return heart.hiveLevel();
    }

    private static int set(CommandContext<CommandSourceStack> context, int level) throws CommandSyntaxException {
        HiveHeart heart = hiveOf(context);
        if (heart == null) {
            return 0;
        }
        if (level < 1 || level > HiveLevels.maxLevel()) {
            context.getSource().sendFailure(Component.translatable("command.projecthivemind.level.out_of_range", HiveLevels.maxLevel()));
            return 0;
        }
        // A smaller hive has a smaller storage: it is not shrunk while anything is in the part that would go.
        int slots = HiveLevels.get(level).storageSlots();
        for (int i = slots; i < heart.getStorage().getContainerSize(); i++) {
            if (!heart.getStorage().getItem(i).isEmpty()) {
                context.getSource().sendFailure(Component.translatable("command.projecthivemind.level.storage_in_use", level));
                return 0;
            }
        }
        ServerPlayer player = context.getSource().getPlayerOrException();
        HivemindManager.setLevel(heart, player, level);
        context.getSource().sendSuccess(() -> Component.translatable("command.projecthivemind.level.set", level), true);
        return level;
    }
}
