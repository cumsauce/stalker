package io.github.cumsauce.stalker.world;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The 48-candidate cover search (plan §3): 16 compass directions × radii
 * {4, 7, 10}, each tried over a ±3 block Y spread. A candidate is cover when
 * the player's eye cannot see the candidate's eye position (clip blocked) and
 * the block below is solid. Score = distance from villager + weight ×
 * distance from player; the lowest score wins. Static and side-effect free;
 * throttling (one search per 2 s per villager) is the caller's job.
 */
public final class CoverSearch {

	/** Radii searched around the villager, per the plan. */
	public static final int[] RADII = {4, 7, 10};

	/** Y offsets tried per horizontal candidate (±3, top-down so high ground wins ties). */
	public static final int[] Y_SPREAD = {0, 1, 2, 3, -1, -2, -3};

	/** Number of compass directions. */
	public static final int DIRECTIONS = 16;

	/** Weight of the player-distance term in the score (plan §3). */
	public static final double PLAYER_DISTANCE_WEIGHT = 0.5;

	private CoverSearch() {
	}

	/** Lateral eye offsets (blocks) the cover must also block: cover that only hides the villager
	 * from the player's exact current eye breaks the moment the player sidesteps, which read as the
	 * villager being "stuck in plain sight". */
	public static final double[] LATERAL_SAMPLES = {-1.5, 0.0, 1.5};

	/**
	 * Finds the best cover point for a villager fleeing a player, using the
	 * plan defaults (radii {4, 7, 10}, ±3 Y, weight 0.5). Occlusion is sampled
	 * from the player's eye and two points 1.5 blocks either side, so the cover
	 * survives a sidestep; candidates beyond the villager relative to the
	 * player are preferred.
	 *
	 * @return the chosen cover block position, or empty when nothing qualifies
	 *         (the caller then acts normal, plan §3).
	 */
	public static Optional<BlockPos> findCover(ServerLevel level, Vec3 villagerPos, Vec3 playerEye) {
		Vec3 away = villagerPos.subtract(playerEye).multiply(1.0, 0.0, 1.0);
		Vec3 side = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(-away.z, 0.0, away.x).normalize();
		Vec3[] eyes = new Vec3[LATERAL_SAMPLES.length];
		for (int i = 0; i < LATERAL_SAMPLES.length; i++) {
			eyes[i] = playerEye.add(side.scale(LATERAL_SAMPLES[i]));
		}
		Vec3 fleeDir = away.lengthSqr() < 1.0E-4 ? Vec3.ZERO : away.normalize();
		return search(level, villagerPos, eyes, RADII, Y_SPREAD, PLAYER_DISTANCE_WEIGHT, true, fleeDir);
	}

	/**
	 * Relocation-flavoured search (plan §7): finds an out-of-sight point in a
	 * ring {@code [minRadius, maxRadius]} around an anchor (the player), with
	 * no villager-distance term. Returns empty if no LOS-blocked point exists.
	 */
	public static Optional<BlockPos> findRelocationPoint(ServerLevel level, Vec3 anchor, int minRadius, int maxRadius) {
		int[] radii = {minRadius, (minRadius + maxRadius) / 2, maxRadius};
		return search(level, anchor, new Vec3[] {anchor}, radii, Y_SPREAD, 0.0, false, Vec3.ZERO);
	}

	/**
	 * The search core. {@code fleeWeighted} adds the distance-from-anchor
	 * (player) term to the score when true; otherwise score is distance from
	 * the ring centre only.
	 *
	 * @param centre   the position the ring of candidates surrounds
	 * @param viewerEye the eye the candidates must be hidden from
	 */
	public static Optional<BlockPos> search(ServerLevel level, Vec3 centre, Vec3[] viewerEyes, int[] radii,
			int[] ySpread, double fleeWeight, boolean fleeWeighted, Vec3 fleeDirection) {
		Optional<BlockPos> best = Optional.empty();
		double bestScore = Double.MAX_VALUE;
		for (int i = 0; i < DIRECTIONS; i++) {
			double angle = (Math.PI * 2.0 * i) / DIRECTIONS;
			double dx = Math.cos(angle);
			double dz = Math.sin(angle);
			for (int radius : radii) {
				double cx = centre.x + dx * radius;
				double cz = centre.z + dz * radius;
				int x = BlockPos.containing(cx, 0.0, cz).getX();
				int z = BlockPos.containing(cx, 0.0, cz).getZ();
				for (int dy : ySpread) {
					BlockPos pos = new BlockPos(x, BlockPos.containing(0.0, centre.y, 0.0).getY() + dy, z);
					if (!isCover(level, pos, viewerEyes)) {
						continue;
					}
					Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5);
					double score = center.distanceTo(centre)
						+ (fleeWeighted ? fleeWeight * center.distanceTo(viewerEyes[0]) : 0.0);
					// Prefer candidates roughly beyond the villager from the player: cover between
					// the two means the villager must double back through the player's view.
					if (fleeWeighted && fleeDirection.lengthSqr() > 1.0E-4) {
						Vec3 toCover = center.subtract(centre).multiply(1.0, 0.0, 1.0);
						if (toCover.lengthSqr() > 1.0E-4) {
							score += 2.0 * (1.0 - fleeDirection.normalize().dot(toCover.normalize()));
						}
					}
					if (score < bestScore) {
						bestScore = score;
						best = Optional.of(pos);
					}
				}
				// One horizontal candidate: the Y loop already picked its best spot;
				// continue to the next radius.
			}
		}
		return best;
	}

	/**
	 * A candidate is cover when the viewer's eye cannot see an eye placed at
	 * the candidate position (clip blocked), the block below is solid, and the
	 * candidate cell is actually standable. Without the passability test a block
	 * buried in a wall counts as cover (the clip to an eye inside the wall is
	 * blocked and the block below is solid), so the villager paths into a wall.
	 */
	public static boolean isCover(ServerLevel level, BlockPos pos, Vec3[] viewerEyes) {
		BlockPos below = pos.below();
		if (!level.getBlockState(below).isSolid()) {
			return false;
		}
		if (!isPassable(level, pos) || !isPassable(level, pos.above())) {
			return false;
		}
		Vec3 candidateEye = Vec3.atLowerCornerOf(pos).add(0.5, 1.6, 0.5);
		for (Vec3 eye : viewerEyes) {
			if (Senses.canSee(level, eye, candidateEye)) {
				return false;
			}
		}
		return true;
	}

	/** True when a villager can stand in this cell (no collision). */
	private static boolean isPassable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
