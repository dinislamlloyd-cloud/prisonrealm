package com.prisonrealm.prison;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;

/**
 * /prison arrest <игрок> [минуты]
 * /prison release <игрок>
 * /prison time
 * /prison list
 */
public final class PrisonCommands {
	private PrisonCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("prison")
						.then(CommandManager.literal("arrest")
								.requires(s -> s.hasPermissionLevel(2))
								.then(CommandManager.argument("player", EntityArgumentType.player())
										.executes(ctx -> arrest(ctx, 5))
										.then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440))
												.executes(ctx -> arrest(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))))
						.then(CommandManager.literal("release")
								.requires(s -> s.hasPermissionLevel(2))
								.then(CommandManager.argument("player", EntityArgumentType.player())
										.executes(PrisonCommands::release)))
						.then(CommandManager.literal("time")
								.executes(PrisonCommands::time))
						.then(CommandManager.literal("list")
								.requires(s -> s.hasPermissionLevel(2))
								.executes(PrisonCommands::list))));
	}

	private static int arrest(CommandContext<ServerCommandSource> ctx, int minutes) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		if (PrisonManager.arrest(target, minutes * 60)) {
			ctx.getSource().sendFeedback(() -> Text.translatable("prisonrealm.cmd.arrested", target.getDisplayName(), minutes), true);
			return 1;
		}
		ctx.getSource().sendError(Text.translatable("prisonrealm.cmd.cannot_arrest", target.getDisplayName()));
		return 0;
	}

	private static int release(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
		if (!PrisonManager.isPrisoner(target)) {
			ctx.getSource().sendError(Text.translatable("prisonrealm.cmd.not_prisoner", target.getDisplayName()));
			return 0;
		}
		PrisonManager.release(target, false);
		ctx.getSource().sendFeedback(() -> Text.translatable("prisonrealm.cmd.released", target.getDisplayName()), true);
		return 1;
	}

	private static int time(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		PrisonState.Entry e = PrisonState.get(p.server).get(p.getUuid());
		if (e == null) {
			ctx.getSource().sendFeedback(() -> Text.translatable("prisonrealm.cmd.free"), false);
			return 0;
		}
		String left = PrisonManager.formatTicks(e.ticksLeft);
		ctx.getSource().sendFeedback(() -> Text.translatable("prisonrealm.time_left", left), false);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		PrisonState state = PrisonState.get(ctx.getSource().getServer());
		if (state.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.translatable("prisonrealm.cmd.empty"), false);
			return 0;
		}
		for (Map.Entry<UUID, PrisonState.Entry> me : state.all().entrySet()) {
			ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(me.getKey());
			String name = p != null ? p.getName().getString() : me.getKey().toString();
			String left = PrisonManager.formatTicks(me.getValue().ticksLeft);
			ctx.getSource().sendFeedback(() -> Text.literal(name + " - " + left), false);
		}
		return state.all().size();
	}
}
