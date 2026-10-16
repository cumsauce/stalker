package io.github.cumsauce.stalker.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cumsauce.stalker.sound.Cues;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lie layer in {@link io.github.cumsauce.stalker.sound.Cues}: the position-history ring
 * buffer, the stale cue origin, and the phantom-decoy decision. Everything under test is the pure
 * core — plain {@link Vec3}s, tick counts and a seeded {@link RandomSource} — so a failure means
 * the mechanism lies wrong, not that Minecraft failed to boot.
 */
class CuesTest {

	/** History depth in ticks the lie layer keeps (40 ticks = 2 s). */
	private static final int HISTORY_TICKS = 40;

	/** Fresh/stale bounds of a cue origin, in ticks (1–2 s behind the stalker). */
	private static final int MIN_STALE_TICKS = 20;
	private static final int MAX_STALE_TICKS = 40;

	/** Lateral jitter bounds of a cue origin, in blocks. */
	private static final double MIN_LATERAL_OFFSET = 0.5;
	private static final double MAX_LATERAL_OFFSET = 1.5;

	/** A history of the first {@code count} positions of a stalker walking 1 block/tick along +x. */
	private static Cues.PositionHistory walkingHistory(int count) {
		Cues.PositionHistory history = new Cues.PositionHistory(count);
		for (int i = 0; i < count; i++) {
			history.add(new Vec3(i, 0, 0));
		}
		return history;
	}

	// ------------------------------------------------------------------ ring buffer

	@Test
	@DisplayName("the ring holds at most N samples and drops the oldest")
	void ringHoldsAtMostCapacity() {
		Cues.PositionHistory history = new Cues.PositionHistory(HISTORY_TICKS);
		for (int i = 0; i < 50; i++) {
			history.add(new Vec3(i, 0, 0));
		}
		assertEquals(HISTORY_TICKS, history.size(), "kept to the last 40 ticks of memory");
		assertEquals(new Vec3(49, 0, 0), history.newest());
		assertEquals(new Vec3(10, 0, 0), history.oldest(), "samples 0..9 aged out of the ring");
	}

	@Test
	@DisplayName("oldest-first eviction and clamped lookups hold at any capacity")
	void ringEvictionAndClamping() {
		Cues.PositionHistory history = new Cues.PositionHistory(3);
		history.add(new Vec3(0, 0, 0));
		history.add(new Vec3(1, 0, 0));
		history.add(new Vec3(2, 0, 0));
		history.add(new Vec3(3, 0, 0));
		assertEquals(3, history.size());
		assertEquals(new Vec3(3, 0, 0), history.newest());
		assertEquals(new Vec3(1, 0, 0), history.oldest(), "the fourth sample dropped the oldest");
		assertEquals(new Vec3(3, 0, 0), history.ticksAgo(0));
		assertEquals(new Vec3(2, 0, 0), history.ticksAgo(1));
		assertEquals(new Vec3(1, 0, 0), history.ticksAgo(99), "older than memory clamps to the oldest sample");
	}

	// ------------------------------------------------------------------ stale origin

	@Test
	@DisplayName("a cue origin is a stale, jittered sample — never the live position")
	void staleOriginIsNotLivePosition() {
		Cues.PositionHistory history = walkingHistory(HISTORY_TICKS);
		Vec3 live = new Vec3(40, 0, 0); // the stalker kept walking past its memory
		Vec3 origin = Cues.originFor(history, live, RandomSource.create(1L));
		assertNotEquals(live, origin);
		assertTrue(origin.distanceTo(live) > 5.0, "the origin sits seconds behind the live position");
		// It is one of the 20-40-tick-old samples, displaced by at most the lateral jitter.
		double nearestStale = Double.MAX_VALUE;
		for (int ticksAgo = MIN_STALE_TICKS; ticksAgo <= MAX_STALE_TICKS; ticksAgo++) {
			nearestStale = Math.min(nearestStale, origin.distanceTo(history.ticksAgo(ticksAgo)));
		}
		assertTrue(nearestStale <= MAX_LATERAL_OFFSET, "a stale sample plus at most the lateral jitter");
	}

	@Test
	@DisplayName("same history, same seed, same lie: origins are reproducible")
	void staleOriginReproducible() {
		Cues.PositionHistory history = walkingHistory(HISTORY_TICKS);
		Vec3 live = new Vec3(40, 0, 0);
		assertEquals(
			Cues.originFor(history, live, RandomSource.create(7L)),
			Cues.originFor(history, live, RandomSource.create(7L)));
	}

	// ------------------------------------------------------------------ decoys

	@Test
	@DisplayName("the decoy decision is reproducible from a seeded RandomSource")
	void decoyDecisionReproducible() {
		RandomSource first = RandomSource.create(4242L);
		RandomSource second = RandomSource.create(4242L);
		for (int i = 0; i < 100; i++) {
			assertEquals(Cues.shouldFireDecoy(first), Cues.shouldFireDecoy(second), "roll " + i + " diverged");
		}
	}

	@Test
	@DisplayName("phantoms fire in the 5-20% band: often enough to doubt, rarely enough to trust")
	void decoyRateWithinBand() {
		RandomSource random = RandomSource.create(20261016L);
		int trials = 1000;
		int decoys = 0;
		for (int i = 0; i < trials; i++) {
			if (Cues.shouldFireDecoy(random)) {
				decoys++;
			}
		}
		double fraction = decoys / (double) trials;
		assertTrue(fraction >= 0.05 && fraction <= 0.20,
			"decoy fraction " + fraction + " outside the 5-20% band (a 0% or 100% bug)");
	}

	@Test
	@DisplayName("a phantom fires at the oldest remembered position, jittered 0.5-1.5 blocks")
	void phantomOriginIsOldestPlusJitter() {
		Cues.PositionHistory history = walkingHistory(HISTORY_TICKS);
		RandomSource random = RandomSource.create(99L);
		for (int i = 0; i < 25; i++) {
			Vec3 phantom = Cues.phantomOrigin(history, new Vec3(40, 0, 0), random);
			double jitter = phantom.distanceTo(history.oldest());
			assertTrue(jitter >= MIN_LATERAL_OFFSET - 1.0E-9 && jitter <= MAX_LATERAL_OFFSET + 1.0E-9,
				"jitter " + jitter + " outside the lateral bounds");
		}
	}

	// ------------------------------------------------------------------ empty history

	@Test
	@DisplayName("first tick, no history yet: the cue falls back to the live position")
	void emptyHistoryFallsBackToLivePosition() {
		Cues.PositionHistory history = new Cues.PositionHistory(HISTORY_TICKS);
		Vec3 live = new Vec3(12.5, 64.0, -3.0);
		assertEquals(live, Cues.originFor(history, live, RandomSource.create(1L)));
		assertEquals(live, Cues.phantomOrigin(history, live, RandomSource.create(1L)));
	}
}
