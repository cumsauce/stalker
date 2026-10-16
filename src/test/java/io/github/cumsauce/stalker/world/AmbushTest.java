package io.github.cumsauce.stalker.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The patience trigger, play feedback as rules: close, no villager witness, and a still victim are
 * what it takes to start; then it must hold for a few seconds before it commits.
 */
class AmbushTest {
	private static final double RANGE = 2.2;

	@Test
	void holdsWhenCloseUnobservedAndStill() {
		assertTrue(Ambush.conditionsHold(RANGE, RANGE, 0, true));
		assertTrue(Ambush.conditionsHold(RANGE * Ambush.RANGE_FACTOR, RANGE, 0, true));
	}

	@Test
	void aWitnessStopsIt() {
		assertFalse(Ambush.conditionsHold(1.0, RANGE, 1, true));
	}

	@Test
	void aMovingPlayerStopsIt() {
		assertFalse(Ambush.conditionsHold(1.0, RANGE, 0, false));
	}

	@Test
	void outOfRangeDoesNotHold() {
		assertFalse(Ambush.conditionsHold(RANGE * Ambush.RANGE_FACTOR + 0.01, RANGE, 0, true));
	}

	@Test
	void itMustBePatient() {
		assertFalse(Ambush.ready(0));
		assertFalse(Ambush.ready(Ambush.HOLD_TICKS - 1));
		assertTrue(Ambush.ready(Ambush.HOLD_TICKS));
		assertTrue(Ambush.ready(Ambush.HOLD_TICKS + 100));
	}
}
