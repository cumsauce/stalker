package io.github.cumsauce.stalker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Config round-trip and fallback behaviour, earshot-style: a missing or hand-broken key falls back to its default,
 * and a saved file loads back equal.
 */
class StalkerConfigTest {
	@Test
	void tuningDefaultsMatchThePlan() {
		StalkerConfig d = StalkerConfig.DEFAULT;
		assertEquals(0.004, d.bindChancePerMinute());
		assertEquals(48, d.bindMaxDistance());
		assertFalse(d.requireNight());
		assertFalse(d.requireAlone());
		assertEquals(12, d.orbitInner());
		assertEquals(24, d.orbitOuter());
		assertEquals(28, d.spottedRange());
		assertEquals(25, d.spottedAngleDeg());
		assertEquals(2.2, d.strikeRange());
		assertEquals(0.15, d.teleportChance());
		assertEquals(100, d.teleportDistance());
		assertEquals(3, d.teleportMaxLift());
		assertEquals(180, d.chaseDurationTicks());
		assertEquals(1.15, d.chaseSpeed());
		assertEquals(1.6, d.meleeRange());
		assertEquals(22, d.hitCooldownTicks());
		assertEquals(2, d.hitDamage());
		assertEquals(2, d.poisonAmplifierCap());
		assertEquals(200, d.poisonDurationTicks());
		assertEquals(160, d.stunDurationTicks());
		assertEquals(40, d.stunEscapeDistance());
		assertEquals(8, d.bribeThreshold());
		assertEquals(10, d.emeraldValue());
		assertFalse(d.nitwitAcceptsEmerald());
		assertEquals(12000, d.blacklistTicks());
		assertEquals(600, d.relocateBehindTicks());
		assertEquals(48, d.relocateRingMin());
		assertEquals(64, d.relocateRingMax());
		assertTrue(d.crossDimensionFollow());
		assertEquals(40, d.brainResetTicks());
		assertEquals(40, d.pretendChaseMinDelayTicks());
		assertEquals(80, d.pretendChaseMaxDelayTicks());
		assertTrue(d.foleyVoices());
	}

	@Test
	void foleyVoicesDefaultsOnAndRoundTrips() {
		assertTrue(StalkerConfig.DEFAULT.foleyVoices(), "the Foley voice layer is on by default");
		assertTrue(StalkerConfig.fromJson(JsonParser.parseString("{}").getAsJsonObject()).foleyVoices(),
				"an absent key falls back to the default");
		StalkerConfig off = StalkerConfig.fromJson(
				JsonParser.parseString("{\"foleyVoices\": false}").getAsJsonObject());
		assertFalse(off.foleyVoices(), "an explicit false is read as false");
		assertFalse(off.toJson().get("foleyVoices").getAsBoolean(), "and written back out");
	}

	@Test
	void missingAndBadValuesFallBackToTheDefaults() {
		StalkerConfig config = StalkerConfig.fromJson(JsonParser.parseString(
				"{\"bindChancePerMinute\": 1.0, \"bribeThreshold\": \"eight\", \"teleportChance\": 1.0}")
				.getAsJsonObject());
		assertEquals(1.0, config.bindChancePerMinute());
		assertEquals(1.0, config.teleportChance());
		assertEquals(StalkerConfig.DEFAULT.bribeThreshold(), config.bribeThreshold());
		assertEquals(StalkerConfig.DEFAULT.chaseDurationTicks(), config.chaseDurationTicks());
	}

	@Test
	void savedSettingsLoadBackTheSame(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("stalker.json");
		assertEquals(StalkerConfig.DEFAULT, StalkerConfig.load(file), "no file: the defaults");
		assertTrue(Files.notExists(file), "loading a missing file does not write one");
		StalkerConfig custom = new StalkerConfig(
				0.5, 16, true, true,
				6, 30,
				20, 40,
				3.0,
				1.0, 64, 2,
				100, 1.3, 2.0, 10, 4, 1, 100,
				60, 24,
				4, 5, true,
				600,
				100, 32, 40,
				false, 80,
				200, 400, 0.5,
				50, 0.9, 4, 16,
				15, 1.0,
				20, 60, false);
		custom.save(file);
		assertEquals(custom, StalkerConfig.load(file));
		Files.writeString(file, "not json {");
		assertEquals(StalkerConfig.DEFAULT, StalkerConfig.load(file), "unreadable: the defaults");
	}

	@Test
	void everyKeyIsWritten() {
		JsonObject json = StalkerConfig.DEFAULT.toJson();
		assertEquals(StalkerConfig.class.getRecordComponents().length, json.size(),
				"one JSON key per record component");
	}
}
