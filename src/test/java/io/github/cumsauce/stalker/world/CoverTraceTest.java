package io.github.cumsauce.stalker.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cover constellation bookkeeping: add, query, expire, bound, and seeded style variety — all
 * pure against {@link BlockPos} and tick counts, so no level is needed here.
 */
class CoverTraceTest {

	/** Fresh constellation per test: the bookkeeping is static by design (like {@code Strikes}). */
	@BeforeEach
	void resetConstellation() {
		CoverTrace.reset();
	}

	@Test
	@DisplayName("mark adds one entry; activeCount() returns 1")
	void markAddsOneEntry() {
		BlockPos pos = new BlockPos(3, 64, -2);
		CoverTrace.Trace trace = CoverTrace.mark(pos, 100L, RandomSource.create(1L));
		assertEquals(1, CoverTrace.activeCount());
		assertEquals(pos, trace.pos());
		assertEquals(100L, trace.createdTick());
	}

	@Test
	@DisplayName("isMarked sees the marked position and nothing else")
	void isMarkedMatchesOnlyTracedPositions() {
		BlockPos marked = new BlockPos(3, 64, -2);
		CoverTrace.mark(marked, 100L, RandomSource.create(1L));
		assertTrue(CoverTrace.isMarked(marked));
		assertFalse(CoverTrace.isMarked(new BlockPos(4, 64, -2)), "a different position is unmarked");
		assertFalse(CoverTrace.isMarked(marked.above()), "the trace belongs to the exact cell");
	}

	@Test
	@DisplayName("an entry older than LIFETIME_TICKS is dropped; younger ones survive")
	void expireDropsStaleTraces() {
		CoverTrace.mark(new BlockPos(0, 64, 0), 100L, RandomSource.create(1L));
		CoverTrace.mark(new BlockPos(1, 64, 0), 900L, RandomSource.create(2L));
		assertEquals(0, CoverTrace.expire(100L + CoverTrace.LIFETIME_TICKS - 1), "one tick before its lifetime runs out");
		assertEquals(2, CoverTrace.activeCount());
		assertEquals(1, CoverTrace.expire(100L + CoverTrace.LIFETIME_TICKS), "dropped on the tick it outlives its lifetime");
		assertEquals(1, CoverTrace.activeCount());
		assertTrue(CoverTrace.isMarked(new BlockPos(1, 64, 0)), "the younger trace is untouched");
	}

	@Test
	@DisplayName("the trace list is bounded: exceeding MAX_TRACKED keeps the most recent MAX_TRACKED")
	void boundedToMaxTracked() {
		int marks = CoverTrace.MAX_TRACKED + 6;
		for (int i = 0; i < marks; i++) {
			CoverTrace.mark(new BlockPos(i, 64, 0), 1000L + i, RandomSource.create(i));
		}
		assertEquals(CoverTrace.MAX_TRACKED, CoverTrace.activeCount());
		int evicted = marks - CoverTrace.MAX_TRACKED;
		for (int i = 0; i < evicted; i++) {
			assertFalse(CoverTrace.isMarked(new BlockPos(i, 64, 0)), "the oldest entries are evicted first");
		}
		for (int i = evicted; i < marks; i++) {
			assertTrue(CoverTrace.isMarked(new BlockPos(i, 64, 0)), "the most recent entries survive");
		}
	}

	@Test
	@DisplayName("style is picked deterministically from a seeded random")
	void styleIsDeterministic() {
		RandomSource first = RandomSource.create(42L);
		RandomSource second = RandomSource.create(42L);
		for (int i = 0; i < 12; i++) {
			assertEquals(CoverTrace.pickStyle(first), CoverTrace.pickStyle(second),
				"the same seed draws the same style sequence");
		}
		// The seeded rule is the one mark() uses too.
		CoverTrace.Trace trace = CoverTrace.mark(new BlockPos(1, 70, 1), 500L, RandomSource.create(7L));
		assertEquals(CoverTrace.pickStyle(RandomSource.create(7L)), trace.style());
	}

	@Test
	@DisplayName("recommitting to the same cover refreshes its trace instead of duplicating it")
	void recommitRefreshes() {
		BlockPos pos = new BlockPos(8, 64, 8);
		CoverTrace.mark(pos, 100L, RandomSource.create(1L));
		CoverTrace.Trace refreshed = CoverTrace.mark(pos, 1100L, RandomSource.create(2L));
		assertEquals(1, CoverTrace.activeCount(), "one position holds one trace");
		assertEquals(1100L, refreshed.createdTick(), "the fresh commitment restarts the trace's life");
		assertEquals(0, CoverTrace.expire(100L + CoverTrace.LIFETIME_TICKS + 1),
			"the trace no longer dies with the first commitment's clock");
		assertEquals(1, CoverTrace.activeCount());
	}
}
