package io.github.cumsauce.stalker.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.cumsauce.stalker.bribe.BribeTables;
import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.world.Attachments;
import io.github.cumsauce.stalker.world.Phases;
import io.github.cumsauce.stalker.world.StalkerManager;
import io.github.cumsauce.stalker.world.VillagerSpawner;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@code /stalker} (permission level 2): the test and tuning surface of PLAN.md section 8 — force binds, phases and
 * strikes, read status, dump a stalker's bribe table, reload the config, toggle debug particles. The optional
 * {@code [player]} argument defaults to the executing player.
 */
public final class StalkerCommands {
	private static final SuggestionProvider<CommandSourceStack> PHASES = (context, builder) -> {
		for (Phases.Phase phase : Phases.Phase.values()) builder.suggest(phase.name().toLowerCase(Locale.ROOT));
		return builder.buildFuture();
	};

	private StalkerCommands() {}

	/** Registers {@code /stalker}; called once from the mod initializer. */
	public static void initialize() {
		CommandRegistrationCallback.EVENT.register(StalkerCommands::register);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
			Commands.CommandSelection selection) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("stalker")
				// 26.3 permission levels: GAMEMASTERS is vanilla op level 2.
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(target("start", StalkerCommands::start))
				.then(target("stop", StalkerCommands::stop))
				.then(target("status", StalkerCommands::status))
				.then(Commands.literal("phase")
						.then(Commands.argument("phase", StringArgumentType.word())
								.suggests(PHASES)
								.executes(context -> phase(context, source(context)))
								.then(Commands.argument("player", EntityArgument.player())
										.executes(context -> phase(context, EntityArgument.getPlayer(context, "player"))))))
				.then(target("spot", StalkerCommands::spot))
				.then(target("strike", StalkerCommands::strike))
				.then(target("bribe", StalkerCommands::bribe))
				.then(Commands.literal("reload").executes(context -> {
					StalkerConfig.reload();
					return reply(context.getSource(), "Stalker config reloaded from config/stalker.json.", true);
				}))
				.then(target("debug", StalkerCommands::debug))
				.then(Commands.literal("spawn")
						.executes(context -> reply(context.getSource(), spawn(source(context), 12), true))
						.then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
								.executes(context -> reply(context.getSource(),
										spawn(source(context),
												com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count")),
												true))));
		dispatcher.register(root);
	}

	/**
	 * Debug helper ({@code /stalker spawn [count]}): scatters {@code count} adult villagers with
	 * random professions on standable ground in a ring around the player, so a village does not have
	 * to be found or built by hand to exercise the mod. Jobs are drawn from the profession registry
	 * (nitwits and the unemployed filtered out); ground is taken from the motion-blocking heightmap.
	 */
	private static String spawn(ServerPlayer player, int count) {
		if (!(player.level() instanceof ServerLevel level)) {
			return "not in a server level.";
		}
		List<String> professions = VillagerSpawner.spawnRing(level, player.position(), count, level.getRandom());
		return "Spawned " + professions.size() + " villager(s)" + (professions.size() < count ? " (wanted " + count + ")" : "")
				+ " in a ring around " + player.getName().getString()
				+ (professions.isEmpty() ? "" : ": " + String.join(", ", professions));
	}

	/** A subcommand with the default-target form and an optional {@code [player]} argument form. */
	private static LiteralArgumentBuilder<CommandSourceStack> target(String name,
			Function<ServerPlayer, String> action) {
		return Commands.literal(name)
				.executes(context -> reply(context.getSource(), action.apply(source(context)), true))
				.then(Commands.argument("player", EntityArgument.player())
						.executes(context -> reply(context.getSource(),
								action.apply(EntityArgument.getPlayer(context, "player")), true)));
	}

	private static ServerPlayer source(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return context.getSource().getPlayerOrException();
	}

	private static int reply(CommandSourceStack source, String message, boolean success) {
		if (success) source.sendSuccess(() -> Component.literal(message), false);
		else source.sendFailure(Component.literal(message));
		return success ? Command.SINGLE_SUCCESS : 0;
	}

	private static String start(ServerPlayer player) {
		String failure = StalkerManager.bindFailure(player);
		return failure == null
				? "Bound a stalker to " + player.getName().getString() + "."
				: "Could not bind: " + failure;
	}

	private static String stop(ServerPlayer player) {
		StalkerManager.unbind(player);
		return "Cleared " + player.getName().getString() + "'s stalker.";
	}

	private static String status(ServerPlayer player) {
		return player.getName().getString() + ": " + StalkerManager.status(player);
	}

	private static int phase(CommandContext<CommandSourceStack> context, ServerPlayer player) {
		Phases.Phase phase;
		try {
			phase = Phases.Phase.valueOf(StringArgumentType.getString(context, "phase").toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return reply(context.getSource(), "Unknown phase (stalk|cover|chase|stun).", false);
		}
		StalkerManager.forcePhase(player, phase);
		return reply(context.getSource(), player.getName().getString() + " forced into " + phase + ".", true);
	}

	private static String spot(ServerPlayer player) {
		StalkerManager.forceSpot(player);
		return "Forced the spotted condition for " + player.getName().getString() + "'s stalker.";
	}

	private static String strike(ServerPlayer player) {
		StalkerManager.forceStrike(player);
		return "Forced a strike on " + player.getName().getString() + ".";
	}

	private static String bribe(ServerPlayer player) {
		UUID bound = Attachments.boundVillager(player).orElse(null);
		if (bound == null) return "§c" + player.getName().getString() + " is not stalked by anyone.";
		if (!(player.level() instanceof ServerLevel level) || !(level.getEntity(bound) instanceof Villager villager)) {
			return "§cThe bound villager is not loaded right now.";
		}
		StalkerConfig config = StalkerConfig.get();
		String profession = BribeTables.professionId(villager);
		return player.getName().getString() + "'s stalker (" + profession + ", threshold " + config.bribeThreshold()
				+ ", emerald " + config.emeraldValue() + ", nitwit accepts emerald: " + config.nitwitAcceptsEmerald()
				+ "): " + BribeTables.describe(profession);
	}

	private static String debug(ServerPlayer player) {
		StalkerManager.toggleDebug(player);
		return "Toggled debug particles for " + player.getName().getString() + ".";
	}
}
