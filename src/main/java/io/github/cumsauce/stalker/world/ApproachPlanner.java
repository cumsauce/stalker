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
	 * @return the hops in plan order, or an empty list when no candidate forms a safe leg
	 */
	public static List<Vec3> plan(Vec3 from, Vec3 to, List<Vec3> candidates,
			Predicate<Vec3> visibleToPlayer) {
		List<Vec3> plan = new ArrayList<>();
		Vec3 cursor = from;
		double remaining = cursor.distanceTo(to);
		// Greedy over the caller's preference order: the first candidate each round that progresses,
		// is safe to walk to, and lands meaningfully closer to the player than where we are.
		while (true) {
			Vec3 next = null;
			for (Vec3 candidate : candidates) {
				double leg = cursor.distanceTo(candidate);
				if (leg < 2.0) {
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
				break;
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

	/**
	 * Where the stalker's plan should aim — the only input that makes the approach look like it is
	 * thinking rather than chasing a marker on the player's position.
	 *
	 * <p>Two rules, chosen by whether the player is currently visible:
	 * <ul>
	 * <li><b>Visible — intercept.</b> Plan toward {@code playerPos + playerVelocity * interceptTicks}:
	 * the point the player will reach if they keep moving the way they are moving. A stalker that
	 * aims at the future cuts the player off instead of following in their footsteps.</li>
	 * <li><b>Not visible — stale memory.</b> Plan toward {@code lastSeenPlayerPos}, where the stalker
	 * last <em>saw</em> the player. Its model of the player is now out of date by however long the
	 * player has been out of sight, so the plan walks the wrong way — and the player cannot tell
	 * whether the stalker is coming for them or wandering off.</li>
	 * </ul>
	 *
	 * <p>Both rules make the plan target diverge from the player's live position on purpose. The
	 * divergence is the design: a stalker that always plans toward the live position is legible, and
	 * legibility is the opposite of the dread the mod is built on. Neither rule ever confirms the
	 * player got away — they only make the stalker's intentions ambiguous.
	 *
	 * @param playerPos the player's live position
	 * @param playerVelocity the player's smoothed velocity (blocks per tick), or null on the first tick
	 * @param lastSeenPlayerPos where the stalker last saw the player, or null if it never has
	 * @param canSeePlayer whether the stalker currently has line of sight to the player
	 * @param interceptTicks how many ticks of player movement to look ahead when visible
	 * @return the point the approach plan should aim at
	 */
	public static Vec3 planTarget(Vec3 playerPos, Vec3 playerVelocity, Vec3 lastSeenPlayerPos,
			boolean canSeePlayer, double interceptTicks) {
		if (!canSeePlayer) {
			return lastSeenPlayerPos != null ? lastSeenPlayerPos : playerPos;
		}
		return playerVelocity == null ? playerPos : playerPos.add(playerVelocity.scale(interceptTicks));
	}
}
