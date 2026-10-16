package io.github.cumsauce.stalker.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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

	/** Radii searched around the villager, per the plan. Private because the array contents are shared
	 * mutable state — exposing them invites an external mutation that would silently corrupt cover
	 * search rankings. */
	private static final int[] RADII = {4, 7, 10};

	/** Y offsets tried per horizontal candidate (±3, top-down so high ground wins ties). Same caveat
	 * as {@link #RADII}. */
	private static final int[] Y_SPREAD = {0, 1, 2, 3, -1, -2, -3};

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
		for (double f : ESCAPE_FRACTIONS) {
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

	/** Radii searched around a standable approach anchor: tighter than the cover ring because the
	 * villager is already near the player and the planner just needs a one-step hop. */
	private static final int[] APPROACH_RADII = {2, 3, 4};

	/** Y offsets tried per approach candidate — small spread, the planner is for short hops. */
	private static final int[] APPROACH_Y = {0, 1, -1, 2, -2};

	/** Three evenly-spaced fractions of the escape segment used to score cone exposure (0.25, 0.5, 0.75
	 * of the way to cover). Lifting it out of {@code coneExposure} saves a small array allocation per
	 * comparator invocation. */
	private static final double[] ESCAPE_FRACTIONS = {0.25, 0.5, 0.75};

	/** Single-eye overload shortcut: avoids a one-element Vec3[] allocation per cover candidate in the
	 * hot path of {@link #findApproachPoint}. Delegates to the no-array variant. */
	public static boolean isCover(ServerLevel level, BlockPos pos, Vec3 viewerEye) {
		return isCoverSingle(level, pos, viewerEye);
	}

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
			// Pre-floored Y of {@code around}: reused for every candidate so we don't re-floor on each
			// inner iteration of a 16×3×5 = 240 candidate sweep.
			int aroundY = Mth.floor(around.y);
			for (int radius : APPROACH_RADII) {
				int x = Mth.floor(around.x + dx * radius);
				int z = Mth.floor(around.z + dz * radius);
				for (int dy : APPROACH_Y) {
					BlockPos pos = new BlockPos(x, aroundY + dy, z);
					if (!isCover(level, pos, playerEye)) {
						continue;
					}
					Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5);
					double hiddenness = hiddennessOf(level, center.add(0.0, 1.6, 0.0), playerEye, playerPos);
					double score = center.distanceTo(around) + terrainCost(level, around, center)
						+ (1.0 - hiddenness) * EXPOSURE_PENALTY;
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
			// Pre-floored centre Y: the inner loop iterates {@code ySpread} over a single anchor.
			int centreY = Mth.floor(centre.y);
			for (int radius : radii) {
				int x = Mth.floor(centre.x + dx * radius);
				int z = Mth.floor(centre.z + dz * radius);
				for (int dy : ySpread) {
					BlockPos pos = new BlockPos(x, centreY + dy, z);
					if (!isCover(level, pos, viewerEyes)) {
						continue;
					}
					Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5);
					double score = center.distanceTo(centre) + terrainCost(level, centre, center)
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
	 * the candidate position (clip blocked), the block below is solid, the
	 * candidate cell is actually standable, and the cell is fluid-free. Without
	 * the passability test a block buried in a wall counts as cover (the clip to
	 * an eye inside the wall is blocked and the block below is solid), so the
	 * villager paths into a wall. Without the fluid test a water or lava cell
	 * counts as cover too (fluids have no collision shape) and the villager
	 * wades into it, which is slow, conspicuous and lethal (play feedback: avoid
	 * water).
	 */
	public static boolean isCover(ServerLevel level, BlockPos pos, Vec3[] viewerEyes) {
		if (!isStandableCover(level, pos)) {
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

	/** Shared standability pre-test (solid below, passable cells, no fluid). The full multi-eye
	 * {@link #isCover(ServerLevel, BlockPos, Vec3[])} and the single-eye shorthand both gate on it. */
	private static boolean isStandableCover(ServerLevel level, BlockPos pos) {
		BlockPos below = pos.below();
		if (!level.getBlockState(below).isSolid()) {
			return false;
		}
		if (!isPassable(level, pos) || !isPassable(level, pos.above())) {
			return false;
		}
		return !isFluid(level, pos) && !isFluid(level, pos.above()) && !isFluid(level, below);
	}

	/** Single-eye overload: inlines {@link #isCover(ServerLevel, BlockPos, Vec3[])} without the
	 * one-element array allocation the hot path of {@link #findApproachPoint} would otherwise eat on
	 * every candidate (~240 candidates × 2 radii × the per-tick rebuild). */
	private static boolean isCoverSingle(ServerLevel level, BlockPos pos, Vec3 viewerEye) {
		if (!isStandableCover(level, pos)) {
			return false;
		}
		Vec3 candidateEye = Vec3.atLowerCornerOf(pos).add(0.5, 1.6, 0.5);
		return !Senses.canSee(level, viewerEye, candidateEye);
	}

	/** True when the cell holds any fluid (water or lava, waterlogged included): somewhere the
	 * villager would wade or sink rather than walk. */
	private static boolean isFluid(ServerLevel level, BlockPos pos) {
		return !level.getFluidState(pos).isEmpty();
	}

	/** Ticks' worth of extra travel charged for one sampled slow block on a leg. */
	public static final double SLOW_LEG_PENALTY = 4.0;

	/** Maximum total leg penalty, so a single unavoidable stream does not veto every candidate. */
	private static final double MAX_LEG_PENALTY = 24.0;

	/**
	 * A distance-equivalent cost for the leg {@code from -> to}: fluid is slow to wade and other
	 * blocks (cobweb, powder snow, soul sand, mud) bog a villager down, so a detour over solid dry
	 * ground should win. Returns 0 for a clean leg. Each step probes the feet cell and the cells
	 * below and above it: a flat leg at standing height passes over a one-block-deep stream at
	 * feet-below level, so probing only the feet cell would miss exactly the wade worth avoiding.
	 * Sampling is one probe per horizontal block at the interpolated height, so it is cheap enough
	 * to run per candidate.
	 */
	public static double terrainCost(ServerLevel level, Vec3 from, Vec3 to) {
		double horizontal = Math.hypot(to.x - from.x, to.z - from.z);
		int steps = Math.max(1, (int) Math.ceil(horizontal));
		double cost = 0.0;
		for (int i = 1; i <= steps; i++) {
			double f = (double) i / steps;
			double x = from.x + (to.x - from.x) * f;
			double y = from.y + (to.y - from.y) * f;
			double z = from.z + (to.z - from.z) * f;
			BlockPos feet = BlockPos.containing(x, y, z);
			if (isFluid(level, feet) || isFluid(level, feet.above()) || isFluid(level, feet.below())) {
				cost += SLOW_LEG_PENALTY;
			} else if (isBogged(level, feet.below()) || isBogged(level, feet)) {
				cost += SLOW_LEG_PENALTY * 0.5;
			}
			if (cost >= MAX_LEG_PENALTY) {
				return MAX_LEG_PENALTY;
			}
		}
		return cost;
	}

	/** Blocks that noticeably slow a walking villager down. */
	private static boolean isBogged(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.SOUL_SAND) || state.is(Blocks.MUD);
	}

	/** True when a villager can stand in this cell (no collision). */
	private static boolean isPassable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
