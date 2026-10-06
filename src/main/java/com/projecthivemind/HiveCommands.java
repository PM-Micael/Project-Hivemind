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
                        .then(Commands.literal("down").executes(context -> set(context, hiveOf(context) == null ? 0 : hiveOf(context).hiveLevel() - 1)))));
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
