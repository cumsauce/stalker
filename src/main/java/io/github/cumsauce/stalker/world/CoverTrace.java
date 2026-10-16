package io.github.cumsauce.stalker.world;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * The cover constellation: a faint, fading trace at every cover point the stalker commits to.
 *
 * <p>The stalker's approach is planned as hops between cover ({@link ApproachPlanner},
 * {@link CoverSearch}), but the planning is invisible — play feedback was that the stalker "doesn't
 * seem to do anything", because the thinking all happens off screen. Every commitment leaves a
 * subtle trace here that lingers for about a minute, so a player who backtracks can read the
 * approach history like tracks: where it stood, in what order — and never where it is now. The
 * lifetime is the design: long enough to be found on the backtrack, short enough that a fresh
 * constellation is always recent history and never the live position.
 *
 * <p>The bookkeeping (add, expire, bound, query) is pure against {@link BlockPos} and tick counts,
 * so it is unit-tested without a level; only the particle visuals touch {@link ServerLevel}.
 */
public final class CoverTrace {

	/** How long a trace lives: 60 seconds at 20 ticks per second. Long enough that a player
	 * backtracking a minute later still finds the trail, short enough that the constellation is
	 * always recent history — a trace that outlived its minute would start reading as current. */
	public static final int LIFETIME_TICKS = 1200;

	/** Most traces kept at once; past this the oldest is evicted. Bounding the list is what lets a
	 * stalker hop between cover indefinitely without the constellation growing without limit. */
	public static final int MAX_TRACKED = 64;

	/** Particles in the initial burst: enough to catch a second look, few enough that a mark never
	 * competes with the stalker for attention (design budget: 3–5 per mark). */
	public static final int PARTICLE_COUNT = 4;

	/** Ticks between the one-particle refreshes that keep a mark faintly visible. Every 2 seconds a
	 * trace reads as a lingering smudge over its whole life; any more often and it blinks like an
	 * effect instead of sitting there like a track. */
	private static final int REFRESH_INTERVAL_TICKS = 40;

	/** Particles per refresh: the whisper-sized echo of {@link #PARTICLE_COUNT}, so a mark's presence
	 * thins after the initial burst instead of repeating it. */
	private static final int REFRESH_PARTICLE_COUNT = 1;

	/** Half-width (blocks) of the burst scatter. A trace belongs to the cover point, so it never
	 * spreads beyond arm's reach of it. */
	private static final double SPREAD = 0.25;

	/** Height (blocks) of the burst above the cover point: on the ground the stalker stood on,
	 * because tracks are read on the floor, not at eye level. */
	private static final double TRACE_HEIGHT = 0.35;

	/** Initial velocity of every burst: zero. The chosen particles carry their own drift (ash
	 * settles, smoke rises), and a sprayed burst would read as an effect, not a trace. */
	private static final double BURST_SPEED = 0.0;

	/** Dim settled-dust gray for {@link Style#DUST_SPECK}, packed RGB. The color of a spot something
	 * stood in — visible when looked for, invisible when scanned past. */
	private static final int DUST_COLOR = 0x7A7A82;

	/** Size of each dust speck: small enough that the burst reads as disturbed dust, never as a
	 * point being made. */
	private static final float DUST_SCALE = 0.35f;

	/** The styles, pre-cloned for {@link #pickStyle}. Private because the array is shared mutable
	 * state — exposing it invites an external mutation that would silently flatten the variety. */
	private static final Style[] STYLES = Style.values();

	/** The live constellation, oldest creation tick first in practice but ordered only by
	 * insertion; age is always judged from {@link Trace#createdTick()}, never from list order. */
	private static final List<Trace> TRACES = new ArrayList<>();

	/**
	 * How one trace manifests. Three quiet readings of "something was here", so consecutive marks
	 * along an approach differ the way real tracks differ — the variety is what turns marks into a
	 * trail the player learns to read instead of a repeated effect they learn to ignore.
	 */
	public enum Style {
		/** A few settling ash motes: the faintest reading, like dust shaken loose and falling back. */
		ASH_MOTES,
		/** A small burst of dim dust specks: the settled-dust look of a spot something stood in. */
		DUST_SPECK,
		/** A thin slow smoke wisp: the strongest reading, still softer than any fire or magic cue. */
		SMOKE_WISP
	}

	/**
	 * One committed cover point: the atom of the constellation.
	 *
	 * @param pos the cover cell the stalker committed to
	 * @param createdTick world tick of the commitment — the only thing that ages a trace, so expiry
	 *                    works against any clock the caller passes in
	 * @param style the randomly picked look, so consecutive marks do not all look identical
	 */
	public record Trace(BlockPos pos, long createdTick, Style style) {
	}

	private CoverTrace() {
	}

	/**
	 * Records that the stalker committed to cover at {@code pos} and drops the trace there.
	 *
	 * <p>Called at the moment of commitment, not on arrival: the trail should mark where the stalker
	 * decided to be, so it reads as intent even when it never made it. The burst is deliberately
	 * small — a trace is something to notice on a second look, not something to point at.
	 *
	 * @return the recorded trace (useful for debug: shows which style was picked)
	 */
	public static Trace mark(ServerLevel level, BlockPos pos, RandomSource random) {
		Trace trace = mark(pos, level.getGameTime(), random);
		emit(level, trace, PARTICLE_COUNT);
		return trace;
	}

	/**
	 * Ages the constellation once per world tick: drops traces that outlived {@link #LIFETIME_TICKS}
	 * and whispers a one-particle refresh at each survivor, so a mark stays faintly visible for its
	 * whole life instead of flashing once and vanishing. Call once per world tick — the constellation
	 * is server-global (the stalker is bound to a single level), not per dimension.
	 */
	public static void tick(ServerLevel level) {
		long now = level.getGameTime();
		expire(now);
		for (Trace trace : TRACES) {
			long age = now - trace.createdTick();
			if (age > 0 && age % REFRESH_INTERVAL_TICKS == 0) {
				emit(level, trace, REFRESH_PARTICLE_COUNT);
			}
		}
	}

	/**
	 * How many traces are live right now. For tests and debug output; it is the size of the
	 * constellation the player could still read off the ground.
	 */
	public static int activeCount() {
		return TRACES.size();
	}

	/**
	 * Whether {@code pos} still carries a trace. For tests and debug — a "yes" means the stalker
	 * committed to this cover point within the last {@link #LIFETIME_TICKS} ticks.
	 */
	public static boolean isMarked(BlockPos pos) {
		for (Trace trace : TRACES) {
			if (trace.pos().equals(pos)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Pure bookkeeping half of {@link #mark(ServerLevel, BlockPos, RandomSource)}: records the trace
	 * and picks its style with no level access. Marking a position that already has a trace refreshes
	 * it (new creation tick and style) instead of appending a duplicate — a stalker that keeps
	 * committing to the same cover point would otherwise crowd the bounded list with copies of one
	 * position and evict real history.
	 */
	static Trace mark(BlockPos pos, long createdTick, RandomSource random) {
		Trace trace = new Trace(pos, createdTick, pickStyle(random));
		for (int i = 0; i < TRACES.size(); i++) {
			if (TRACES.get(i).pos().equals(pos)) {
				TRACES.set(i, trace);
				return trace;
			}
		}
		TRACES.add(trace);
		while (TRACES.size() > MAX_TRACKED) {
			evictOldest();
		}
		return trace;
	}

	/**
	 * Pure expiry: drops every trace that has outlived {@link #LIFETIME_TICKS} as of {@code nowTick}.
	 * A trace lives exactly its lifetime — it is dropped on the tick it reaches that age, so the
	 * constellation never shows anything older than the minute the player is meant to read.
	 *
	 * @return how many traces were dropped
	 */
	static int expire(long nowTick) {
		int before = TRACES.size();
		TRACES.removeIf(trace -> nowTick - trace.createdTick() >= LIFETIME_TICKS);
		return before - TRACES.size();
	}

	/**
	 * Picks the visual style for one trace from the shared random. A seeded random yields a seeded
	 * style: consecutive marks differ without the variety being noisy or patterned.
	 */
	static Style pickStyle(RandomSource random) {
		return STYLES[random.nextInt(STYLES.length)];
	}

	/**
	 * Clears the constellation. A test seam (fresh state per test) and a clean slate if the manager
	 * ever needs one; not part of the per-tick path.
	 */
	static void reset() {
		TRACES.clear();
	}

	/** Drops the trace with the smallest creation tick (earliest-added wins ties): oldest first, so
	 * a full constellation forgets the far end of the approach and never the recent one. */
	private static void evictOldest() {
		int oldest = 0;
		for (int i = 1; i < TRACES.size(); i++) {
			if (TRACES.get(i).createdTick() < TRACES.get(oldest).createdTick()) {
				oldest = i;
			}
		}
		TRACES.remove(oldest);
	}

	/** Spawns {@code count} particles for one trace — the only method that touches the level. */
	private static void emit(ServerLevel level, Trace trace, int count) {
		double x = trace.pos().getX() + 0.5;
		double y = trace.pos().getY() + TRACE_HEIGHT;
		double z = trace.pos().getZ() + 0.5;
		switch (trace.style()) {
			case ASH_MOTES -> level.sendParticles(ParticleTypes.ASH,
				x, y, z, count, SPREAD, SPREAD, SPREAD, BURST_SPEED);
			case DUST_SPECK -> level.sendParticles(new DustParticleOptions(DUST_COLOR, DUST_SCALE),
				x, y, z, count, SPREAD, SPREAD, SPREAD, BURST_SPEED);
			case SMOKE_WISP -> level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
				x, y, z, count, SPREAD, SPREAD, SPREAD, BURST_SPEED);
		}
	}
}
