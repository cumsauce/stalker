package io.github.cumsauce.stalker.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The approach-planning rules: progress, sight-safe legs, nearest-first ordering. */
class ApproachPlannerTest {

	/** Everything visible — only short legs may be used. */
	private static final java.util.function.Predicate<Vec3> ALL_VISIBLE = p -> true;
	/** Nothing visible — long legs are fine. */
	private static final java.util.function.Predicate<Vec3> NOTHING_VISIBLE = p -> false;
	/** Visible only inside an x band (a patch of open ground). */
	private static final java.util.function.Predicate<Vec3> VISIBLE_IN_BAND = p -> p.x > 5.0 && p.x < 12.0;

	@Test
	@DisplayName("hops are consumed in the caller's preference order, always making progress")
	void plansThroughCover() {
		Vec3 from = new Vec3(0, 0, 0);
		Vec3 to = new Vec3(20, 0, 0);
		// Preference order: 6 (best hiddenness-vs-distance), then 13; 2 is last and never fits.
		List<Vec3> candidates = List.of(new Vec3(6, 0, 0), new Vec3(13, 0, 0), new Vec3(2, 0, 0));
		List<Vec3> plan = ApproachPlanner.plan(from, to, candidates, NOTHING_VISIBLE);
		assertEquals(List.of(new Vec3(6, 0, 0), new Vec3(13, 0, 0)), plan);
	}

	@Test
	@DisplayName("hops that would cross visible ground are rejected when no cover is between")
	void skipsSightCrossingHops() {
		Vec3 from = new Vec3(0, 0, 0);
		Vec3 to = new Vec3(20, 0, 0);
		// Only candidate sits mid-line; its leg midpoint (x=7) is inside the visible band.
		List<Vec3> candidates = List.of(new Vec3(14, 0, 0));
		List<Vec3> plan = ApproachPlanner.plan(from, to, candidates, VISIBLE_IN_BAND);
		assertTrue(plan.isEmpty(), "the leg crosses visible ground and is too long to dash");
	}

	@Test
	@DisplayName("short visible legs are allowed: one quick dash beats a detour")
	void shortVisibleLegAllowed() {
		Vec3 from = new Vec3(0, 0, 0);
		Vec3 to = new Vec3(20, 0, 0);
		// Leg length 5 <= MAX_VISIBLE_LEG_LENGTH even though fully visible.
		List<Vec3> candidates = List.of(new Vec3(5, 0, 0));
		List<Vec3> plan = ApproachPlanner.plan(from, to, candidates, ALL_VISIBLE);
		assertEquals(List.of(new Vec3(5, 0, 0)), plan);
	}

	@Test
	@DisplayName("candidates behind the villager never make the plan")
	void backwardsCandidatesRejected() {
		Vec3 from = new Vec3(0, 0, 0);
		Vec3 to = new Vec3(20, 0, 0);
		List<Vec3> candidates = List.of(new Vec3(-5, 0, 0));
		List<Vec3> plan = ApproachPlanner.plan(from, to, candidates, NOTHING_VISIBLE);
		assertTrue(plan.isEmpty());
	}

	@Test
	@DisplayName("candidates too close to the current position are skipped")
	void tooCloseCandidatesSkipped() {
		Vec3 from = new Vec3(0, 0, 0);
		Vec3 to = new Vec3(20, 0, 0);
		List<Vec3> candidates = List.of(new Vec3(1, 0, 0), new Vec3(9, 0, 0));
		List<Vec3> plan = ApproachPlanner.plan(from, to, candidates, NOTHING_VISIBLE);
		assertEquals(List.of(new Vec3(9, 0, 0)), plan);
	}

	@Test
	@DisplayName("the plan is bounded at four hops")
	void boundedLength() {
		Vec3 from = new Vec3(0, 0, 0);
		Vec3 to = new Vec3(40, 0, 0);
		List<Vec3> candidates = List.of(
			new Vec3(4, 0, 0), new Vec3(9, 0, 0), new Vec3(15, 0, 0), new Vec3(22, 0, 0),
			new Vec3(30, 0, 0), new Vec3(37, 0, 0));
		List<Vec3> plan = ApproachPlanner.plan(from, to, candidates, NOTHING_VISIBLE);
		assertEquals(4, plan.size());
	}

	@Test
	@DisplayName("leg sight is judged at the midpoint")
	void legJudgedAtMidpoint() {
		Vec3 from = Vec3.ZERO;
		Vec3 to = new Vec3(20, 0, 0);
		// A wall of sight at exactly x=10 (the midpoint): long leg rejected.
		Set<Double> visibleAt = Set.of(10.0);
		java.util.function.Predicate<Vec3> wallVisible = p -> visibleAt.contains(p.x);
		assertTrue(ApproachPlanner.legCrossesSight(from, to, wallVisible));
		// Midpoint clear: not crossing.
		java.util.function.Predicate<Vec3> clearMid = p -> !visibleAt.contains(p.x);
		assertFalse(ApproachPlanner.legCrossesSight(from, to, clearMid));
	}

	// ---------------------------------------------------------------- plan target

	@Test
	@DisplayName("visible + moving: the plan aims ahead of the player, not at them")
	void visibleMovingPlansAhead() {
		Vec3 playerPos = new Vec3(10, 0, 0);
		Vec3 velocity = new Vec3(1, 0, 0); // one block per tick
		Vec3 target = ApproachPlanner.planTarget(playerPos, velocity, null, true, 8.0);
		assertEquals(new Vec3(18, 0, 0), target);
	}

	@Test
	@DisplayName("visible but the player is stationary: plan at their live position")
	void visibleStillPlansAtPlayer() {
		Vec3 playerPos = new Vec3(10, 0, 0);
		Vec3 target = ApproachPlanner.planTarget(playerPos, Vec3.ZERO, null, true, 8.0);
		assertEquals(playerPos, target);
	}

	@Test
	@DisplayName("first tick, no velocity estimate yet: plan at the live position")
	void nullVelocityPlansAtPlayer() {
		Vec3 playerPos = new Vec3(10, 0, 0);
		Vec3 target = ApproachPlanner.planTarget(playerPos, null, null, true, 8.0);
		assertEquals(playerPos, target);
	}

	@Test
	@DisplayName("unseen: the plan aims at where the stalker last saw the player, not the live one")
	void unseenPlansAtLastSeen() {
		Vec3 livePos = new Vec3(50, 0, 0);
		Vec3 lastSeen = new Vec3(10, 0, 0);
		Vec3 velocity = new Vec3(1, 0, 0); // even with a velocity, an unseen player is not extrapolated
		Vec3 target = ApproachPlanner.planTarget(livePos, velocity, lastSeen, false, 8.0);
		assertEquals(lastSeen, target, "stale memory — the stalker's model is out of date on purpose");
	}

	@Test
	@DisplayName("unseen with no memory yet: fall back to the live position")
	void unseenWithoutMemoryPlansAtPlayer() {
		Vec3 playerPos = new Vec3(10, 0, 0);
		Vec3 target = ApproachPlanner.planTarget(playerPos, Vec3.ZERO, null, false, 8.0);
		assertEquals(playerPos, target);
	}
}
