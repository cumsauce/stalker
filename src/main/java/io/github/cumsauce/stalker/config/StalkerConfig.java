package io.github.cumsauce.stalker.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.cumsauce.stalker.Stalker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Stalker's tunables, kept in {@code config/stalker.json}. One field per row of the Tuning table in PLAN.md, plus the
 * cue-cadence and pretend-bribe-delay values from PLAN.md sections 5 and 6. A missing or unreadable value falls back
 * to its default, so an old or hand-edited file still loads; the file is written with defaults when absent.
 */
public record StalkerConfig(
		double bindChancePerMinute,
		double bindMaxDistance,
		boolean requireNight,
		boolean requireAlone,
		double spottedRange,
		double spottedAngleDeg,
		double strikeRange,
		double teleportChance,
		double teleportDistance,
		double teleportMaxLift,
		int chaseDurationTicks,
		double chaseSpeed,
		double meleeRange,
		int hitCooldownTicks,
		double hitDamage,
		int poisonAmplifierCap,
		int poisonDurationTicks,
		int stunDurationTicks,
		double stunEscapeDistance,
		int bribeThreshold,
		int emeraldValue,
		boolean nitwitAcceptsEmerald,
		int blacklistTicks,
		int relocateBehindTicks,
		double relocateRingMin,
		double relocateRingMax,
		boolean crossDimensionFollow,
		int brainResetTicks,
		int mumbleMinIntervalTicks,
		int mumbleMaxIntervalTicks,
		double mumbleVolume,
		int footstepIntervalTicks,
		double footstepVolume,
		double footstepMinDistance,
		double footstepMaxDistance,
		int chaseHeartbeatIntervalTicks,
		double chaseHeartbeatVolume,
		int pretendChaseMinDelayTicks,
		int pretendChaseMaxDelayTicks,
		boolean foleyVoices) {
	public static final StalkerConfig DEFAULT = new StalkerConfig(
			0.004, 48, false, false,
			28, 25,
			2.2,
			0.15, 100, 3,
			180, 1.15, 1.6, 22, 2, 2, 200,
			160, 40,
			8, 10, false,
			12000,
			600, 48, 64,
			true, 40,
			600, 1400, 0.25,
			100, 0.6, 8, 20,
			30, 0.8,
			40, 80, true);

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("stalker.json");
	}

	/** The settings currently in effect, loading them from disk on first use if needed. */
	public static StalkerConfig get() {
		if (loaded == null) load();
		return loaded;
	}

	private static StalkerConfig loaded;

	/** Reads {@code config/stalker.json}, writing it with the defaults if it does not exist yet. */
	public static void load() {
		Path file = file();
		if (Files.notExists(file)) {
			loaded = DEFAULT;
			DEFAULT.save(file);
			return;
		}
		loaded = load(file);
	}

	/** Re-reads {@code config/stalker.json} from disk ({@code /stalker reload}). */
	public static void reload() {
		load();
	}

	/** The settings in {@code file}, or the defaults if there is none or it can't be read. */
	public static StalkerConfig load(Path file) {
		try {
			JsonElement json = JsonParser.parseString(Files.readString(file));
			return json.isJsonObject() ? fromJson(json.getAsJsonObject()) : DEFAULT;
		} catch (NoSuchFileException e) {
			return DEFAULT;
		} catch (IOException | JsonParseException e) {
			Stalker.LOGGER.warn("Couldn't read {}, so Stalker uses its default settings", file, e);
			return DEFAULT;
		}
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			// Atomic write: write to a sibling .tmp file, then move into place. A power cut mid-write
			// would otherwise leave a half-written config that {@link #load} falls back to defaults on
			// — silently wiping the player's tuning. The {@code Files.move} with {@code ATOMIC_MOVE}
			// is at-least atomic within the JVM; on POSIX the rename(2) it lands on is atomic too.
			Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(toJson()) + "\n");
			try {
				Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Stalker.LOGGER.warn("Couldn't save Stalker's settings to {}", file, e);
		}
	}

	static StalkerConfig fromJson(JsonObject json) {
		StalkerConfig d = DEFAULT;
		return new StalkerConfig(
				num(json, "bindChancePerMinute", d.bindChancePerMinute),
				num(json, "bindMaxDistance", d.bindMaxDistance),
				flag(json, "requireNight", d.requireNight),
				flag(json, "requireAlone", d.requireAlone),
				num(json, "spottedRange", d.spottedRange),
				num(json, "spottedAngleDeg", d.spottedAngleDeg),
				num(json, "strikeRange", d.strikeRange),
				num(json, "teleportChance", d.teleportChance),
				num(json, "teleportDistance", d.teleportDistance),
				num(json, "teleportMaxLift", d.teleportMaxLift),
				integer(json, "chaseDurationTicks", d.chaseDurationTicks),
				num(json, "chaseSpeed", d.chaseSpeed),
				num(json, "meleeRange", d.meleeRange),
				integer(json, "hitCooldownTicks", d.hitCooldownTicks),
				num(json, "hitDamage", d.hitDamage),
				integer(json, "poisonAmplifierCap", d.poisonAmplifierCap),
				integer(json, "poisonDurationTicks", d.poisonDurationTicks),
				integer(json, "stunDurationTicks", d.stunDurationTicks),
				num(json, "stunEscapeDistance", d.stunEscapeDistance),
				integer(json, "bribeThreshold", d.bribeThreshold),
				integer(json, "emeraldValue", d.emeraldValue),
				flag(json, "nitwitAcceptsEmerald", d.nitwitAcceptsEmerald),
				integer(json, "blacklistTicks", d.blacklistTicks),
				integer(json, "relocateBehindTicks", d.relocateBehindTicks),
				num(json, "relocateRingMin", d.relocateRingMin),
				num(json, "relocateRingMax", d.relocateRingMax),
				flag(json, "crossDimensionFollow", d.crossDimensionFollow),
				integer(json, "brainResetTicks", d.brainResetTicks),
				integer(json, "mumbleMinIntervalTicks", d.mumbleMinIntervalTicks),
				integer(json, "mumbleMaxIntervalTicks", d.mumbleMaxIntervalTicks),
				num(json, "mumbleVolume", d.mumbleVolume),
				integer(json, "footstepIntervalTicks", d.footstepIntervalTicks),
				num(json, "footstepVolume", d.footstepVolume),
				num(json, "footstepMinDistance", d.footstepMinDistance),
				num(json, "footstepMaxDistance", d.footstepMaxDistance),
				integer(json, "chaseHeartbeatIntervalTicks", d.chaseHeartbeatIntervalTicks),
				num(json, "chaseHeartbeatVolume", d.chaseHeartbeatVolume),
				integer(json, "pretendChaseMinDelayTicks", d.pretendChaseMinDelayTicks),
				integer(json, "pretendChaseMaxDelayTicks", d.pretendChaseMaxDelayTicks),
				flag(json, "foleyVoices", d.foleyVoices()));
	}

	JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("bindChancePerMinute", bindChancePerMinute);
		json.addProperty("bindMaxDistance", bindMaxDistance);
		json.addProperty("requireNight", requireNight);
		json.addProperty("requireAlone", requireAlone);
		json.addProperty("spottedRange", spottedRange);
		json.addProperty("spottedAngleDeg", spottedAngleDeg);
		json.addProperty("strikeRange", strikeRange);
		json.addProperty("teleportChance", teleportChance);
		json.addProperty("teleportDistance", teleportDistance);
		json.addProperty("teleportMaxLift", teleportMaxLift);
		json.addProperty("chaseDurationTicks", chaseDurationTicks);
		json.addProperty("chaseSpeed", chaseSpeed);
		json.addProperty("meleeRange", meleeRange);
		json.addProperty("hitCooldownTicks", hitCooldownTicks);
		json.addProperty("hitDamage", hitDamage);
		json.addProperty("poisonAmplifierCap", poisonAmplifierCap);
		json.addProperty("poisonDurationTicks", poisonDurationTicks);
		json.addProperty("stunDurationTicks", stunDurationTicks);
		json.addProperty("stunEscapeDistance", stunEscapeDistance);
		json.addProperty("bribeThreshold", bribeThreshold);
		json.addProperty("emeraldValue", emeraldValue);
		json.addProperty("nitwitAcceptsEmerald", nitwitAcceptsEmerald);
		json.addProperty("blacklistTicks", blacklistTicks);
		json.addProperty("relocateBehindTicks", relocateBehindTicks);
		json.addProperty("relocateRingMin", relocateRingMin);
		json.addProperty("relocateRingMax", relocateRingMax);
		json.addProperty("crossDimensionFollow", crossDimensionFollow);
		json.addProperty("brainResetTicks", brainResetTicks);
		json.addProperty("mumbleMinIntervalTicks", mumbleMinIntervalTicks);
		json.addProperty("mumbleMaxIntervalTicks", mumbleMaxIntervalTicks);
		json.addProperty("mumbleVolume", mumbleVolume);
		json.addProperty("footstepIntervalTicks", footstepIntervalTicks);
		json.addProperty("footstepVolume", footstepVolume);
		json.addProperty("footstepMinDistance", footstepMinDistance);
		json.addProperty("footstepMaxDistance", footstepMaxDistance);
		json.addProperty("chaseHeartbeatIntervalTicks", chaseHeartbeatIntervalTicks);
		json.addProperty("chaseHeartbeatVolume", chaseHeartbeatVolume);
		json.addProperty("pretendChaseMinDelayTicks", pretendChaseMinDelayTicks);
		json.addProperty("pretendChaseMaxDelayTicks", pretendChaseMaxDelayTicks);
		json.addProperty("foleyVoices", foleyVoices);
		return json;
	}

	private static double num(JsonObject json, String key, double fallback) {
		return json.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : fallback;
	}

	private static int integer(JsonObject json, String key, int fallback) {
		return json.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : fallback;
	}

	private static boolean flag(JsonObject json, String key, boolean fallback) {
		return json.get(key) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallback;
	}
}
