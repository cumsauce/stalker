package io.github.cumsauce.stalker.sound;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

/**
 * The pure core of the Foley voice layer: which cue plays as which sound, the tuned pitch windows,
 * the laugh's cadence, and the server-side gates. Foley's own API is only ever touched on the
 * client, and only after {@code isModLoaded("foley")} (see {@link StalkerFoley}); everything here
 * is plain data and {@link RandomSource} arithmetic, so it unit-tests without a world or an entity.
 *
 * <p>Two cues layer on top of — never in place of — the vanilla ones: a {@code SCREAM} fired from
 * the villager's <em>true</em> position at the strike moment and each chase melee hit (an event
 * tell, per the lie-layer contract), and a {@code LAUGH} of ambient dread fired during STALK/COVER
 * from {@link Cues}'s stale origin, so the player hears it from where the stalker was. Live draws
 * use {@link #newDraw()} rather than the villager's own random, so arming the layer never shifts
 * the vanilla cue cadences that share the villager's stream.
 */
public final class VoiceCues {

	/** Which synthesized voice cue to fire. The wire carries {@link Enum#name()}; this is the semantics. */
	public enum VoiceCue {
		/** The strike shriek: a true tell from the villager's real position. */
		SCREAM,
		/** Ambient dread from the lie layer's stale origin. */
		LAUGH
	}

	// ------------------------------------------------------------- scream (true tell)

	/** Foley's villager model the scream is synthesized from. */
	public static final String SCREAM_MOB = "villager";
	/** The {@code hurt} call, played raised to read as a shriek rather than a hit. */
	public static final String SCREAM_CALL = "hurt";
	/** Synthesized variants rendered under the scream id (Foley allows 1–64; tune after listening). */
	public static final int SCREAM_VARIANTS = 8;
	/** Lower bound of the documented scream window: already well above the vanilla call. */
	public static final float SCREAM_PITCH_MIN = 1.35f;
	/** Upper bound of the documented scream window: high enough to shriek, not a chipmunk. */
	public static final float SCREAM_PITCH_MAX = 1.5f;
	/** Full gain: the scream is an event the player must be able to react to. */
	public static final float SCREAM_VOLUME = 1.0f;

	// ------------------------------------------------------------- laugh (ambient dread)

	/** Foley's villager model the laugh is synthesized from. */
	public static final String LAUGH_MOB = "villager";
	/** The {@code ambient} call, descending beat by beat to read as a chuckle. */
	public static final String LAUGH_CALL = "ambient";
	/** Synthesized variants rendered under the laugh id (tune after listening). */
	public static final int LAUGH_VARIANTS = 8;
	/** Top of the laugh's pitch window: beat 0 sits just under it. */
	public static final float LAUGH_PITCH_MAX = 1.0f;
	/** Floor of the laugh's pitch window; no scheduled beat may descend past it. */
	public static final float LAUGH_PITCH_MIN = 0.7f;
	/** Random spread (± half this) around each beat's rung, so no two laughs are identical. */
	public static final float LAUGH_PITCH_JITTER = 0.06f;
	/** How far each beat of the cadence drops below the one before it. */
	public static final float LAUGH_BEAT_DROP = 0.1f;
	/** Ambient volume: layered under the vanilla cues, never louder than they are. */
	public static final float LAUGH_VOLUME = 0.8f;
	/** Beats in one laugh: three reads as a chuckle, not a cough. */
	public static final int LAUGH_CADENCE = 3;
	/** Ticks between cadence beats; the laugh plays over ~8 ticks rather than as a stack. */
	public static final int LAUGH_BEAT_GAP_TICKS = 4;
	/** Chance a scheduled ambient opportunity actually laughs (roughly once every few minutes). */
	public static final double LAUGH_CHANCE = 0.25;

	/** The playable id for a cue. Foley refuses foreign namespaces, so both are ours. */
	public static final Identifier SCREAM_SOUND = Identifier.fromNamespaceAndPath("stalker", "voice_scream");
	/** @see #SCREAM_SOUND */
	public static final Identifier LAUGH_SOUND = Identifier.fromNamespaceAndPath("stalker", "voice_laugh");

	private VoiceCues() {
	}

	/** Which registered id carries this cue. */
	public static Identifier soundFor(VoiceCue cue) {
		return switch (cue) {
			case SCREAM -> SCREAM_SOUND;
			case LAUGH -> LAUGH_SOUND;
		};
	}

	/**
	 * A fresh draw source for production call sites: seeded from the thread-local RNG, never from
	 * the villager's own stream. The villager's random drives the vanilla cue cadences, so draws
	 * made only for this layer must not sit in that stream — arming the layer cannot shift a
	 * vanilla client's mumble or footstep timings.
	 */
	public static RandomSource newDraw() {
		return RandomSource.create(ThreadLocalRandom.current().nextLong());
	}

	/**
	 * The scream's play pitch: uniform in the documented raised window
	 * [{@link #SCREAM_PITCH_MIN}, {@link #SCREAM_PITCH_MAX}]. A seeded random draws it
	 * reproducibly; live code passes {@link #newDraw()}.
	 */
	public static float screamPitch(RandomSource random) {
		return SCREAM_PITCH_MIN + random.nextFloat() * (SCREAM_PITCH_MAX - SCREAM_PITCH_MIN);
	}

	/**
	 * The laugh's play pitch for one beat of the cadence: the window top stepped down
	 * {@code beat} times by {@link #LAUGH_BEAT_DROP}, jittered by ± half {@link #LAUGH_PITCH_JITTER},
	 * clamped to [{@link #LAUGH_PITCH_MIN}, {@link #LAUGH_PITCH_MAX}]. Beats past the cadence
	 * clamp to the last rung rather than falling through the floor.
	 */
	public static float laughPitch(RandomSource random, int beat) {
		float base = LAUGH_PITCH_MAX - clampBeat(beat) * LAUGH_BEAT_DROP;
		float jitter = (random.nextFloat() - 0.5f) * LAUGH_PITCH_JITTER;
		return clampLaugh(base + jitter);
	}

	/**
	 * Derives a scheduled cadence beat from the pitch the server sent for beat 0: each later beat
	 * drops one {@link #LAUGH_BEAT_DROP} rung, clamped into the laugh window. Pure, so the client
	 * can space the chuckle without a second random draw.
	 */
	public static float laughBeatPitch(float firstBeatPitch, int beat) {
		return clampLaugh(firstBeatPitch - clampBeat(beat) * LAUGH_BEAT_DROP);
	}

	/**
	 * The server's ambient-laugh gate: false whenever the config option is false — short-circuiting
	 * before any draw — and otherwise {@link #LAUGH_CHANCE} per scheduled ambient opportunity.
	 * Null draws never laugh (a missing RNG is no decision at all).
	 */
	public static boolean shouldLaugh(boolean foleyVoicesEnabled, RandomSource random) {
		return foleyVoicesEnabled && random != null && random.nextDouble() < LAUGH_CHANCE;
	}

	/**
	 * The server's scream gate: the scream is an event tell, so when the layer is armed it always
	 * fires — there is no ambient opportunity to roll against.
	 */
	public static boolean shouldScream(boolean foleyVoicesEnabled) {
		return foleyVoicesEnabled;
	}

	private static int clampBeat(int beat) {
		return Math.min(Math.max(beat, 0), LAUGH_CADENCE - 1);
	}

	private static float clampLaugh(float pitch) {
		return Math.min(LAUGH_PITCH_MAX, Math.max(LAUGH_PITCH_MIN, pitch));
	}
}
