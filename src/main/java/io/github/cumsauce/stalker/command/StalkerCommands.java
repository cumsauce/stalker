package io.github.cumsauce.stalker.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.cumsauce.stalker.bribe.BribeTables;
import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.sound.StalkerSounds;
import io.github.cumsauce.stalker.strike.Strikes;
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
				.then(target("fart", StalkerCommands::fart))
				.then(target("bribe", StalkerCommands::bribe))
				.then(Commands.literal("reload").executes(context -> {
					StalkerConfig.reload();
					return reply(context.getSource(), "Stalker config reloaded from config/stalker.json.", true);
				}))
				.then(target("debug", StalkerCommands::debug))
				.then(Commands.literal("spawn")
						.executes(context -> reply(context.getSource(), spawn(source(context), 12), true))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
								.executes(context -> reply(context.getSource(),
										spawn(source(context),
												IntegerArgumentType.getInteger(context, "count")),
												true))))
				.then(Commands.literal("village")
						.executes(context -> reply(context.getSource(), village(source(context), 4), true))
						.then(Commands.argument("huts", IntegerArgumentType.integer(1, 8))
								.executes(context -> reply(context.getSource(),
										village(source(context),
												IntegerArgumentType.getInteger(context, "huts")),
												true))));
		dispatcher.register(root);
	}

	/**
	 * Debug helper ({@code /stalker village [huts]}): builds a bell, {@code huts} simple huts (door,
	 * bed, torch, roof) and one employed villager per hut around the player. Block-level, so it
	 * replaces what is there — meant for empty-ish ground, not for your base.
	 */
	private static String village(ServerPlayer player, int huts) {
		if (!(player.level() instanceof ServerLevel level)) {
			return "not in a server level.";
		}
		List<String> professions = VillagerSpawner.buildHamlet(level, player.position(), huts, level.getRandom());
		return "Built a hamlet (bell + " + huts + " hut(s)) around " + player.getName().getString()
				+ " with " + professions.size() + " villager(s)"
				+ (professions.isEmpty() ? "" : ": " + String.join(", ", professions));
	}

	/**
	 * Debug helper ({@code /stalker spawn [count]}): places a vanilla {@code village_plains} at the
	 * player's position via the {@code /place} command, scatters {@code count} (default 12) extra
	 * employed villagers in a ring (so the binding has a candidate even if the structure's own
	 * villagers land out of reach), and binds a stalker immediately. Returns a human-readable
	 * summary suitable for the chat reply.
	 */
	private static String spawn(ServerPlayer player, int count) {
		if (!(player.level() instanceof ServerLevel level)) {
			return "not in a server level.";
		}
		// 1. A real vanilla village at the player's position, via the vanilla /place command
		// dispatched programmatically (structure placement is not something to reimplement).
		var source = player.createCommandSourceStack().withSuppressedOutput();
		level.getServer().getCommands().performPrefixedCommand(source,
				"place structure minecraft:village_plains ~ ~ ~");
		// 2. A scatter of extra employed villagers so there is always a candidate even if the
		// structure's own villagers landed out of reach.
		List<String> professions = VillagerSpawner.spawnRing(level, player.position(), count, level.getRandom());
		// 3. Bind one of them right away.
		boolean bound = StalkerManager.bind(player);
		return "Placed a vanilla village at your position and spawned " + professions.size()
				+ " extra villager(s)" + (professions.isEmpty() ? "" : ": " + String.join(", ", professions))
				+ ". " + (bound ? "A stalker is bound — watch your back."
						: "Could not bind a stalker (already stalked, or no eligible villager nearby).");
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

	/**
	 * Debug helper ({@code /stalker fart}): plays the chase-start fart cue at the player right
	 * now. Deliberately ignores the {@code foleyVoices} gate and needs no bound villager, so the
	 * asset can be heard on demand — the one thing a build cannot verify on its own.
	 *
	 * <p>When a stalker <em>is</em> bound it also starts the real chase: blindness, Phase.CHASE and
	 * the villager's pursuit. That path goes through {@link Strikes#forceChase} rather than the
	 * normal strike so the 15 % snatch roll cannot turn this into a teleport, and so the cue is not
	 * played a second time by the chase entry. The already-chasing guard keeps a chase in progress
	 * from being restarted.
	 */
	private static String fart(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return "not in a server level.";
		}
		String name = player.getName().getString();
		if (Attachments.boundVillager(player).isEmpty()) {
			StalkerSounds.chaseFart(level, player.position());
			return "Played the fart cue for " + name + " (no stalker bound, so sound only).";
		}
		boolean chased = Strikes.forceChase(player);
		return (chased ? "Started the chase for " : "Played the fart cue for ") + name
				+ (chased ? " (already chasing or stunned)." : " (bound villager not loaded).");
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
