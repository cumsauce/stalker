package io.github.cumsauce.stalker.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.world.phys.Vec3;

/**
 * Plans a route toward the player as hops between cover, instead of a beeline.
 *
 * <p>Pure (no level access): the caller supplies the candidate points, the endpoints, and a
 * predicate for "the player can see this position". That keeps the plan logic unit-testable —
 * it is the piece that makes the villager look like it is thinking about its approach.
 *
 * <p>The rules, in order:
 * <ul>
 * <li>Every hop must make progress toward the player;</li>
 * <li>the leg between hops must stay out of the player's sight (the leg midpoint blocked), unless
 * the leg is short enough to cross as one quick dash;</li>
 * <li>hops are ordered nearest-first, so the villager walks the plan forward.</li>
 * </ul>
 */
public final class ApproachPlanner {

	/** Legs at most this long may cross visible ground: one quick dash is safer than a detour. */
	public static final double MAX_VISIBLE_LEG_LENGTH = 6.0;

	/** A hop must land at least this much closer to the player than the previous position. */
	public static final double MIN_PROGRESS_PER_HOP = 1.0;

	private ApproachPlanner() {
	}

	/**
	 * Builds an ordered plan of hops from {@code from} to {@code to} through the given candidates
	 * (points that are standable and out of sight). {@code visibleToPlayer} reports whether the
	 * player can see a position — used to test each leg's midpoint.
	 *
	 * @return the hops nearest-first, or an empty list when no candidate forms a safe leg
	 */
	public static List<Vec3> plan(Vec3 from, Vec3 to, List<Vec3> candidates,
			Predicate<Vec3> visibleToPlayer) {
		List<Vec3> plan = new ArrayList<>();
		Vec3 cursor = from;
		double remaining = cursor.distanceTo(to);
		// Greedy: repeatedly take the nearest candidate that progresses, is safe to walk to, and
		// lands meaningfully closer to the player than where we are.
		while (true) {
			Vec3 next = null;
			double nextDistance = Double.MAX_VALUE;
			for (Vec3 candidate : candidates) {
				double leg = cursor.distanceTo(candidate);
				if (leg < 2.0 || leg >= nextDistance) {
					continue;
				}
				double progress = remaining - candidate.distanceTo(to);
				if (progress < MIN_PROGRESS_PER_HOP) {
					continue;
				}
				if (legCrossesSight(cursor, candidate, visibleToPlayer)) {
					continue;
				}
				next = candidate;
				nextDistance = leg;
			}
			if (next == null) {
				break;
			}
			plan.add(next);
			cursor = next;
			remaining = cursor.distanceTo(to);
			if (plan.size() >= 4) {
				break;
			}
		}
		return plan;
	}

	/**
	 * Whether walking {@code from -> to} would cross ground the player can see: the leg midpoint
	 * is visible and the leg is too long to cross as one dash.
	 */
	public static boolean legCrossesSight(Vec3 from, Vec3 to, Predicate<Vec3> visibleToPlayer) {
		if (from.distanceTo(to) <= MAX_VISIBLE_LEG_LENGTH) {
			return false;
		}
		Vec3 mid = from.lerp(to, 0.5);
		return visibleToPlayer.test(mid);
	}
}
