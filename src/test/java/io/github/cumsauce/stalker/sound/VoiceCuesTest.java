package io.github.cumsauce.stalker.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cumsauce.stalker.sound.VoiceCues.VoiceCue;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure Foley voice core in {@link VoiceCues}: cue identifiers, the tuned pitch windows, the
 * laugh cadence, and the server-side gates. Everything under test is a static function driven by a
 * seeded {@link RandomSource} — no levels, entities or client types — so a failure means the cue
 * layer decided wrong, not that Minecraft failed to boot.
 */
class VoiceCuesTest {

	// ------------------------------------------------------------------ identities

	@Test
	@DisplayName("both cue sounds live in the stalker namespace")
	void cueIdentifiersAreNamespacedToStalker() {
		assertEquals("stalker", VoiceCues.SCREAM_SOUND.getNamespace(), "playable ids must be ours: Foley rejects foreign namespaces");
		assertEquals("voice_scream", VoiceCues.SCREAM_SOUND.getPath());
		assertEquals("stalker", VoiceCues.LAUGH_SOUND.getNamespace());
		assertEquals("voice_laugh", VoiceCues.LAUGH_SOUND.getPath());
		assertEquals(VoiceCues.SCREAM_SOUND, VoiceCues.soundFor(VoiceCue.SCREAM));
		assertEquals(VoiceCues.LAUGH_SOUND, VoiceCues.soundFor(VoiceCue.LAUGH));
		assertEquals("SCREAM", VoiceCue.SCREAM.name(), "the payload carries the enum name");
		assertEquals("LAUGH", VoiceCue.LAUGH.name());
	}

	// ------------------------------------------------------------------ pitch draws

	@Test
	@DisplayName("a seeded RandomSource replays the same pitch draws")
	void seededDrawsAreReproducible() {
		RandomSource first = RandomSource.create(42L);
		RandomSource second = RandomSource.create(42L);
		for (int i = 0; i < 16; i++) {
			assertEquals(VoiceCues.screamPitch(first), VoiceCues.screamPitch(second),
					"same seed, same scream pitch, draw " + i);
			assertEquals(VoiceCues.laughPitch(first, i % VoiceCues.LAUGH_CADENCE),
					VoiceCues.laughPitch(second, i % VoiceCues.LAUGH_CADENCE),
					"same seed, same laugh pitch, draw " + i);
		}
		RandomSource draws = RandomSource.create(1L);
		assertNotEquals(VoiceCues.screamPitch(draws), VoiceCues.screamPitch(draws),
				"successive draws must vary, or every shriek is identical");
	}

	@Test
	@DisplayName("scream pitch stays inside its documented raised window")
	void screamPitchStaysInBounds() {
		RandomSource random = RandomSource.create(7L);
		for (int i = 0; i < 10_000; i++) {
			float pitch = VoiceCues.screamPitch(random);
			assertTrue(pitch >= VoiceCues.SCREAM_PITCH_MIN && pitch <= VoiceCues.SCREAM_PITCH_MAX,
					"pitch " + pitch + " outside [" + VoiceCues.SCREAM_PITCH_MIN + ", " + VoiceCues.SCREAM_PITCH_MAX + "]");
		}
	}

	@Test
	@DisplayName("laugh pitch stays in bounds and descends beat by beat")
	void laughPitchStaysInBoundsAndDescends() {
		RandomSource random = RandomSource.create(99L);
		double[] mean = new double[VoiceCues.LAUGH_CADENCE];
		int drawsPerBeat = 2_000;
		for (int beat = 0; beat < VoiceCues.LAUGH_CADENCE; beat++) {
			for (int i = 0; i < drawsPerBeat; i++) {
				float pitch = VoiceCues.laughPitch(random, beat);
				assertTrue(pitch >= VoiceCues.LAUGH_PITCH_MIN && pitch <= VoiceCues.LAUGH_PITCH_MAX,
						"beat " + beat + " pitch " + pitch + " outside the laugh window");
				mean[beat] += pitch;
			}
			mean[beat] /= drawsPerBeat;
		}
		for (int beat = 1; beat < VoiceCues.LAUGH_CADENCE; beat++) {
			assertTrue(mean[beat - 1] > mean[beat],
					"beat " + (beat - 1) + " mean " + mean[beat - 1] + " must outrank beat " + beat + " mean " + mean[beat]
							+ ": a laugh descends");
		}
	}

	@Test
	@DisplayName("derived cadence beats descend from the first beat's pitch and clamp at the floor")
	void derivedBeatPitchesDescendAndClamp() {
		float beat0 = VoiceCues.laughBeatPitch(1.0f, 0);
		float beat1 = VoiceCues.laughBeatPitch(1.0f, 1);
		float beat2 = VoiceCues.laughBeatPitch(1.0f, 2);
		assertEquals(VoiceCues.LAUGH_PITCH_MAX, beat0, "beat 0 is the pitch the server sent");
		assertTrue(beat0 > beat1 && beat1 > beat2, "each scheduled beat drops");
		assertEquals(VoiceCues.LAUGH_PITCH_MIN, VoiceCues.laughBeatPitch(0.5f, 2),
				"a low first beat still floors at the documented minimum");
		assertEquals(VoiceCues.LAUGH_PITCH_MIN, VoiceCues.laughBeatPitch(0.9f, 99),
				"a beat past the cadence clamps to the last rung, not past the floor");
	}

	@Test
	@DisplayName("NaN is not silently clamped: the caller must reject non-finite pitch first")
	void clampLaughPropagatesNaN() {
		// Java's Math.max/min propagate NaN, so clampLaugh(NaN) is NaN, not a window bound. This
		// pins the contract that makes the guards at the wire boundary load-bearing: handleVoiceCue
		// rejects !Float.isFinite(payload.pitch()) BEFORE any clamp runs, so NaN never reaches Foley.
		assertTrue(Float.isNaN(VoiceCues.clampLaugh(Float.NaN)),
				"clampLaugh must not invent a bound for NaN — silent clamping would hide a bad payload");
		assertTrue(Float.isNaN(VoiceCues.laughBeatPitch(Float.NaN, 1)),
				"a NaN first beat stays NaN through the cadence derivation too");
	}

	// ------------------------------------------------------------------ cadence

	@Test
	@DisplayName("the laugh is a three-beat cadence a few ticks apart")
	void laughCadenceIsThreeBeats() {
		assertEquals(3, VoiceCues.LAUGH_CADENCE, "three beats read as a chuckle, not a cough");
		assertTrue(VoiceCues.LAUGH_BEAT_GAP_TICKS >= 2, "beats a few ticks apart, not stacked");
		assertTrue(VoiceCues.LAUGH_BEAT_GAP_TICKS <= 10, "but still one cadence, not a phrase");
	}

	// ------------------------------------------------------------------ gates

	@Test
	@DisplayName("the ambient-laugh gate is false whenever the config flag is false")
	void ambientLaughGateObeysTheConfigFlag() {
		for (long seed = 0; seed < 64; seed++) {
			assertFalse(VoiceCues.shouldLaugh(false, RandomSource.create(seed)),
					"config off: no laugh may fire, seed " + seed);
		}
	}

	@Test
	@DisplayName("the gates open when the flag is on: the laugh eventually fires, the scream always does")
	void gatesOpenWhenTheFlagIsOn() {
		RandomSource random = RandomSource.create(5L);
		boolean fired = false;
		for (int i = 0; i < 10_000 && !fired; i++) {
			fired = VoiceCues.shouldLaugh(true, random);
		}
		assertTrue(fired, "with the flag on the laugh must actually fire at its chance rate");
		assertTrue(VoiceCues.shouldScream(true), "the scream is an event tell: always on when armed");
		assertFalse(VoiceCues.shouldScream(false), "config off: never");
		assertFalse(VoiceCues.shouldLaugh(true, null), "no random, no decision: null never laughs");
	}
}
