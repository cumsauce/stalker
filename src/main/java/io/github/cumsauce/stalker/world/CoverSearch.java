package io.github.cumsauce.stalker.world;

import java.util.ArrayList;
import java.util.List;
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
		return findCoverRanked(level, villagerPos, playerEye, Vec3.ZERO).stream().findFirst();
	}

	/**
	 * Ranked cover candidates, best first. Besides the lateral occlusion sampling, candidates whose
	 * escape segment crosses the player's view cone are penalised, so the villager slips behind the
	 * nearest thing rather than sprinting across the middle of the screen.
	 *
	 * @param playerLook the player's current look direction (cone centre); zero to disable the penalty
	 */
	public static List<BlockPos> findCoverRanked(ServerLevel level, Vec3 villagerPos, Vec3 playerEye, Vec3 playerLook) {
		Vec3 away = villagerPos.subtract(playerEye).multiply(1.0, 0.0, 1.0);
		Vec3 side = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(-away.z, 0.0, away.x).normalize();
		Vec3[] eyes = new Vec3[LATERAL_SAMPLES.length];
		for (int i = 0; i < LATERAL_SAMPLES.length; i++) {
			eyes[i] = playerEye.add(side.scale(LATERAL_SAMPLES[i]));
		}
		Vec3 fleeDir = away.lengthSqr() < 1.0E-4 ? Vec3.ZERO : away.normalize();
		List<BlockPos> ranked = new ArrayList<>();
		searchInto(ranked, level, villagerPos, eyes, RADII, Y_SPREAD, PLAYER_DISTANCE_WEIGHT, true, fleeDir);
		if (playerLook.lengthSqr() > 1.0E-4) {
			// Penalty per sample of the escape segment that sits inside the player's view cone.
			Vec3 look = playerLook.normalize();
			ranked.sort((a, b) -> Double.compare(coneExposure(a, villagerPos, playerEye, look),
				coneExposure(b, villagerPos, playerEye, look)));
		}
		return ranked;
	}

	/** Samples the escape segment for exposure to the player's view cone; lower is better. */
	private static double coneExposure(BlockPos cover, Vec3 villagerPos, Vec3 playerEye, Vec3 look) {
		double exposure = 0.0;
		Vec3 to = Vec3.atLowerCornerOf(cover).add(0.5, 1.0, 0.5);
		for (double f : new double[] {0.25, 0.5, 0.75}) {
			Vec3 sample = villagerPos.lerp(to, f).add(0.0, 1.0, 0.0);
			Vec3 rel = sample.subtract(playerEye);
			double len = rel.length();
			if (len < 1.0E-4) {
				continue;
			}
			if (rel.scale(1.0 / len).dot(look) > 0.7) {
				exposure += 1.0;
			}
		}
		return exposure;
	}

	/**
	 * An approach step (staged closing): a standable, LOS-blocked spot near
	 * {@code around}, which sits on the line toward the player. The stalker
	 * paths to these one at a time instead of beelining, so it advances by
	 * cover rather than in a straight dash across open ground.
	 */
	/** How strongly a visible approach spot is penalised relative to a fully hidden one. */
	public static final double EXPOSURE_PENALTY = 6.0;

	/** Eye-sample offsets around the player used to measure how hidden a spot is. */
	private static final double[] HIDDENNESS_SAMPLES = {0.0, 2.0, -2.0, 3.0, -3.0};

	/**
	 * An approach step (staged closing): a standable, LOS-blocked spot near
	 * {@code around}, which sits on the line toward the player. Scored by how
	 * hidden the spot is — rays to five points around the player — against the
	 * distance it adds, so the villager prefers spots that stay hidden even if
	 * the player shifts.
	 */
	public static Optional<BlockPos> findApproachPoint(ServerLevel level, Vec3 around, Vec3 playerEye, Vec3 playerPos) {
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int i = 0; i < DIRECTIONS; i++) {
			double angle = (Math.PI * 2.0 * i) / DIRECTIONS;
			double dx = Math.cos(angle);
			double dz = Math.sin(angle);
			for (int radius : new int[] {2, 3, 4}) {
				int x = BlockPos.containing(around.x + dx * radius, 0.0, 0.0).getX();
				int z = BlockPos.containing(0.0, 0.0, around.z + dz * radius).getZ();
				for (int dy : new int[] {0, 1, -1, 2, -2}) {
					BlockPos pos = new BlockPos(x, BlockPos.containing(0.0, around.y, 0.0).getY() + dy, z);
					if (!isCover(level, pos, new Vec3[] {playerEye})) {
						continue;
					}
					Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5);
					double hiddenness = hiddennessOf(level, center.add(0.0, 1.6, 0.0), playerEye, playerPos);
					double score = center.distanceTo(around) + (1.0 - hiddenness) * EXPOSURE_PENALTY;
					if (score < bestScore) {
						bestScore = score;
						best = pos;
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * How hidden an eye position is from the player: 1.0 when every sample ray to points around the
	 * player is blocked (fully concealed even if the player shifts), 0.0 when all visible. Used both
	 * for approach scoring and by the manager to rank hop candidates.
	 */
	public static double hiddennessOf(ServerLevel level, Vec3 eye, Vec3 playerEye, Vec3 playerPos) {
		Vec3 lateral = playerPos.subtract(eye).multiply(1.0, 0.0, 1.0);
		Vec3 side = lateral.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(-lateral.z, 0.0, lateral.x).normalize();
		int blocked = 0;
		int total = 0;
		for (double offset : HIDDENNESS_SAMPLES) {
			Vec3 sample = playerEye.add(side.scale(offset));
			total++;
			if (!Senses.canSee(level, eye, sample)) {
				blocked++;
			}
		}
		return total == 0 ? 0.0 : (double) blocked / total;
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
		List<BlockPos> ranked = new ArrayList<>();
		searchInto(ranked, level, centre, viewerEyes, radii, ySpread, fleeWeight, fleeWeighted, fleeDirection);
		return ranked.stream().findFirst();
	}

	/**
	 * The search core, collecting every qualifying candidate into {@code out}, best-scoring first.
	 * Score = distance from centre + optional weighted player distance, plus the beyond-the-villager
	 * preference when weighted: cover between the villager and the player means doubling back through
	 * the player's view, so it is deprioritised.
	 */
	public static void searchInto(List<BlockPos> out, ServerLevel level, Vec3 centre, Vec3[] viewerEyes,
			int[] radii, int[] ySpread, double fleeWeight, boolean fleeWeighted, Vec3 fleeDirection) {
		record Scored(BlockPos pos, double score) {}
		List<Scored> scored = new ArrayList<>();
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
					if (fleeWeighted && fleeDirection.lengthSqr() > 1.0E-4) {
						Vec3 toCover = center.subtract(centre).multiply(1.0, 0.0, 1.0);
						if (toCover.lengthSqr() > 1.0E-4) {
							score += 2.0 * (1.0 - fleeDirection.normalize().dot(toCover.normalize()));
						}
					}
					scored.add(new Scored(pos, score));
				}
			}
		}
		scored.sort((a, b) -> Double.compare(a.score(), b.score()));
		for (Scored entry : scored) {
			out.add(entry.pos());
		}
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
