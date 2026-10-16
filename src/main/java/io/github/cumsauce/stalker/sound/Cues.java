package io.github.cumsauce.stalker.sound;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.cumsauce.stalker.config.StalkerConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * All diegetic cues, per PLAN.md §5. Every sound is positional
 * ({@code level.playSound(null, x, y, z, ...)} with {@code null} except so no
 * player is excluded); volumes and pitches follow the §5 table. No custom assets.
 *
 * <p><b>The lie layer.</b> The design principle is that the player should never really know
 * whether they got away, so a cue must not confirm the stalker's position. The recurring ambient
 * dread cues (mumble, footstep, both heartbeats) therefore play from a <em>stale</em> origin — a
 * sample of where the stalker was one to two seconds ago, nudged sideways — never from where it
 * is now; and occasionally a scheduled ambient cue is swapped for a <em>phantom</em>: the identical
 * sound fired from the oldest remembered position while the real cue is suppressed. Sound alone
 * can neither triangulate the live position nor rule out that the sound was real. Event tells
 * (strike tell, ambush windup, dash, peek, the bind hint, stun, bribes, the death chime) stay
 * true: the player must be able to react to those, and their accuracy is the gameplay contract
 * rather than a leak. The lies need position memory, so {@link #noteStalkerPosition} has to be
 * called once per tick per stalker.
 */
public final class Cues {

	/**
	 * Ticks of stalker position memory kept per stalker (40 ticks = 2 s): the depth of the lie.
	 * The cue origin is always drawn from this window, so the player hears where the stalker
	 * <em>was</em>, and phantoms fire at the far edge of it, where the stalker may no longer be
	 * anywhere near.
	 */
	private static final int HISTORY_TICKS = 40;

	/**
	 * Freshest a cue origin may sit in the history (20 ticks = 1 s): even a barely-moving stalker
	 * is displaced in time, so a cue can never betray a live position.
	 */
	private static final int MIN_STALE_TICKS = 20;

	/** Stalest a cue origin may sit (40 ticks = 2 s): "a couple of seconds ago", per the design. */
	private static final int MAX_STALE_TICKS = HISTORY_TICKS;

	/**
	 * Chance (10 %) that a scheduled ambient cue becomes a phantom with the real cue suppressed.
	 * Inside the 8–12 % band the design calls for: rare enough that most cues are real, common
	 * enough that the player can never trust one.
	 */
	private static final double DECOY_CHANCE = 0.10;

	/**
	 * Minimum lateral jitter of a cue origin (blocks). Non-zero on purpose: a stale sample the
	 * stalker stood still on would otherwise point straight at it, and the jitter is what makes
	 * the <em>direction</em> of a sound wrong as well as its age.
	 */
	private static final double MIN_LATERAL_OFFSET = 0.5;

	/**
	 * Maximum lateral jitter of a cue origin (blocks): a small wrongness — enough that ear
	 * triangulation drifts, not enough that a cue sounds unrelated to the stalker.
	 */
	private static final double MAX_LATERAL_OFFSET = 1.5;

	/**
	 * Bound on tracked per-stalker histories. Pushes are access-ordered, so the entry of a stalker
	 * that stopped being fed (unbound, removed) ages out first instead of leaking on a long-lived
	 * server; an evicted stalker falls back to the shared history, which degrades to slightly
	 * mixed samples rather than to no lies at all.
	 */
	private static final int MAX_TRACKED_STALKERS = 8;

	/**
	 * Position memory for the unkeyed {@link #noteStalkerPosition(Vec3)}: the single-stalker path.
	 * Servers where several players are stalked at once must key their pushes per villager
	 * ({@link #noteStalkerPosition(UUID, Vec3)}) so the stalkers cannot cross-contaminate each
	 * other's memories.
	 */
	private static final PositionHistory SHARED_HISTORY = new PositionHistory(HISTORY_TICKS);

	/** Per-stalker position memory, keyed by villager UUID. */
	private static final Map<UUID, PositionHistory> HISTORIES =
		new LinkedHashMap<>(MAX_TRACKED_STALKERS, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<UUID, PositionHistory> eldest) {
				return size() > MAX_TRACKED_STALKERS;
			}
		};

	private Cues() {
	}

	/** The volumes below are the plan §5 defaults, read live so /stalker reload takes effect. */
	private static StalkerConfig cfg() {
		return StalkerConfig.get();
	}

	private static void play(Level level, Vec3 at, SoundEvent sound, SoundSource source, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch);
	}

	// ------------------------------------------------------------- lie layer

	/**
	 * Feeds one tick of stalker movement into the lie layer. Call once per tick from the tick loop
	 * (e.g. {@code tickStalker}) before any cue can fire; the history is what ambient cues lie
	 * from, so without it they fall back to the live position. This overload feeds the shared,
	 * single-stalker history — on a server where several players are stalked at once, use
	 * {@link #noteStalkerPosition(UUID, Vec3)} instead.
	 */
	public static void noteStalkerPosition(Vec3 pos) {
		SHARED_HISTORY.add(pos);
	}

	/**
	 * Keyed variant of {@link #noteStalkerPosition(Vec3)}: the integrator hook for multiplayer,
	 * where each stalker keeps its own memory ({@code Cues.noteStalkerPosition(villager.getUUID(),
	 * villager.position())} once per tick per stalker).
	 */
	public static void noteStalkerPosition(UUID stalkerId, Vec3 pos) {
		HISTORIES.computeIfAbsent(stalkerId, id -> new PositionHistory(HISTORY_TICKS)).add(pos);
	}

	/**
	 * Where a cue for this villager should play from: never its live position while any memory
	 * exists — a sample one to two seconds old, jittered sideways, so sound cannot pin the
	 * stalker's true location. Falls back to the live position only before the first sample lands.
	 */
	public static Vec3 originFor(Villager villager) {
		return originFor(historyFor(villager.getUUID()), villager.position(), villager.getRandom());
	}

	/**
	 * The pure core of {@link #originFor(Villager)}: a stale sample from {@code history} — its age
	 * drawn uniformly from [{@code MIN_STALE_TICKS}, {@code MAX_STALE_TICKS}] so consecutive cues
	 * are not all equally stale — plus a small lateral jitter so the direction is slightly wrong
	 * too. Returns {@code livePos} unchanged only when the history is empty (the first tick).
	 */
	public static Vec3 originFor(PositionHistory history, Vec3 livePos, RandomSource random) {
		if (history.isEmpty()) {
			return livePos;
		}
		int staleTicks = MIN_STALE_TICKS + random.nextInt(MAX_STALE_TICKS - MIN_STALE_TICKS + 1);
		return jitter(history.ticksAgo(staleTicks), random);
	}

	/**
	 * Whether the ambient cue about to fire should instead be a phantom with the real cue
	 * suppressed. Deterministic given the caller's {@link RandomSource} — tests drive a seeded one,
	 * live code the villager's own — at the {@link #DECOY_CHANCE} rate.
	 */
	public static boolean shouldFireDecoy(RandomSource random) {
		return random.nextDouble() < DECOY_CHANCE;
	}

	/**
	 * Where a phantom cue fires: the oldest remembered position, jittered. The stalker left there
	 * up to two seconds ago, so the sound is plausible as a stalker but confirms nothing about
	 * where it is now. With an empty history there is nothing to lie from and the phantom degrades
	 * to the live position (indistinguishable from a real cue that tick — harmless).
	 */
	public static Vec3 phantomOrigin(PositionHistory history, Vec3 livePos, RandomSource random) {
		if (history.isEmpty()) {
			return livePos;
		}
		return jitter(history.oldest(), random);
	}

	/**
	 * Displaces {@code at} sideways by a uniform 0.5–1.5 blocks at a uniform angle (Y untouched,
	 * so the cue stays on the ground plane the stalker walks). The wrongness in bearing is the
	 * point: even a perfectly remembered sample would otherwise give the player a true direction.
	 */
	private static Vec3 jitter(Vec3 at, RandomSource random) {
		double angle = random.nextDouble() * Math.PI * 2.0;
		double radius = MIN_LATERAL_OFFSET + random.nextDouble() * (MAX_LATERAL_OFFSET - MIN_LATERAL_OFFSET);
		return at.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
	}

	/** The memory a cue for this stalker lies from: its own when keyed pushes exist, else the shared one. */
	private static PositionHistory historyFor(UUID stalkerId) {
		return HISTORIES.getOrDefault(stalkerId, SHARED_HISTORY);
	}

	/**
	 * The lie layer's edge: an ambient dread cue, played from a stale origin — or, when the decoy
	 * roll fires, the identical sound played from the phantom origin with the real cue suppressed.
	 * The two cases are indistinguishable by design: the player can neither trust a sound nor rule
	 * it out.
	 */
	private static void playAmbient(Villager villager, SoundEvent sound, SoundSource source, float volume, float pitch) {
		RandomSource random = villager.getRandom();
		PositionHistory history = historyFor(villager.getUUID());
		Vec3 live = villager.position();
		Vec3 at = shouldFireDecoy(random)
			? phantomOrigin(history, live, random)
			: originFor(history, live, random);
		play(villager.level(), at, sound, source, volume, pitch);
	}

	/** Stalking mumble: {@code entity.villager.ambient}, pitch 0.5–0.6, volume 0.25 — played from a
	 * stale origin, with the phantom-decoy chance applied, so it never confirms the stalker. */
	public static void stalkingMumble(Villager villager) {
		float pitch = 0.5f + ThreadLocalRandom.current().nextFloat() * 0.1f;
		playAmbient(villager, SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, (float) cfg().mumbleVolume(), pitch);
	}

	/** Stalking footstep: {@code block.grass.step}, pitch 1.0, volume 0.6 — played from a stale
	 * origin, with the phantom-decoy chance applied, so it never confirms the stalker. */
	public static void footstep(Villager villager) {
		playAmbient(villager, SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, (float) cfg().footstepVolume(), 1.0f);
	}

	/**
	 * Bind: one distant, low mumble as the pair is made. Deliberately quiet — a hint that one
	 * villager's routine has changed, not an announcement.
	 */
	public static void bindStart(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.4f, 0.55f);
	}

	/** Peek: a soft, high, curious noise as it leans out of cover to look at the player. */
	public static void peek(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.15f, 1.4f);
	}

	/**
	 * Close-quarters dread: a low heartbeat while it is hidden and very near — the tell that something
	 * is right there without giving away exactly where. Played from a stale origin, with the
	 * phantom-decoy chance applied: the "something" is never confirmable, and sometimes there is
	 * nothing there at all.
	 */
	public static void closeHeartbeat(Villager villager, double baseVolume) {
		playAmbient(villager, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, (float) (baseVolume * 0.5), 1.0f);
	}

	/**
	 * Ambush windup: the patience strike's tell, played once a second before the strike lands —
	 * a slow, low stare sting ahead of the strike tell proper.
	 */
	public static void ambushWindup(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.6f, 0.7f);
	}

	/** Spotted dash: {@code block.grass.step} ×2, pitch 1.2, volume 0.7. */
	public static void dashForCover(Villager villager) {
		Vec3 at = villager.position();
		play(villager.level(), at, SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, 0.7f, 1.2f);
		play(villager.level(), at, SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, 0.7f, 1.2f);
	}

	/** Chase heartbeat: {@code entity.warden.heartbeat}, pitch 1.0, volume 0.8 — played from a stale
	 * origin, with the phantom-decoy chance applied, so it trails behind the chase instead of
	 * pointing at the chaser. */
	public static void chaseHeartbeat(Villager villager) {
		playAmbient(villager, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, (float) cfg().chaseHeartbeatVolume(), 1.0f);
	}

	/** Strike tell sting: {@code entity.enderman.scream} pitch 0.65 + {@code entity.warden.heartbeat}, volume 1.0. */
	public static void strikeTell(Villager villager) {
		Vec3 at = villager.position();
		play(villager.level(), at, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.0f, 0.65f);
		play(villager.level(), at, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.0f, 1.0f);
	}

	/** Snatch teleport: {@code entity.enderman.teleport} at both ends (loud at the origin, softer at the landing). */
	public static void snatchTeleport(Level level, Vec3 from, Vec3 to) {
		play(level, from, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 1.0f);
		play(level, to, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8f, 0.8f);
	}

	/** Stun: {@code entity.villager.no}, pitch 1.5, volume 1.0. */
	public static void stun(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.5f);
	}

	/** Bribe accepted (real or pretend — identical sounds is the deception): trade + yes, 1.0/1.0. */
	public static void bribeAccepted(Villager villager) {
		Vec3 at = villager.position();
		play(villager.level(), at, SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 1.0f, 1.0f);
		play(villager.level(), at, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f);
	}

	/** Player died during the chase: the satisfied {@code entity.villager.yes}, once. */
	public static void playerDied(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f);
	}

	/**
	 * The stalker's recent positions — the memory the lie layer plays cues from: one sample per
	 * tick, the oldest dropped once full, so a cue can be drawn from anywhere in the last couple
	 * of seconds and a phantom from its far edge. Pure data (a bounded ring of {@link Vec3}s, no
	 * level or entity access) so the ring, the stale pick and the phantom pick are unit-testable.
	 */
	public static final class PositionHistory {

		/** The ring: {@code ring[head]} is the next slot to write. */
		private final Vec3[] ring;

		/** Next write slot; walks around {@link #ring}. */
		private int head;

		/** Samples currently held, capped at the ring length. */
		private int size;

		/**
		 * @param capacity ticks of memory to keep; once full, each {@link #add} drops the oldest
		 *                  sample so the window slides with the stalker
		 */
		public PositionHistory(int capacity) {
			if (capacity < 1) {
				throw new IllegalArgumentException("capacity must be positive: " + capacity);
			}
			this.ring = new Vec3[capacity];
		}

		/** Records the position observed this tick, dropping the oldest sample when full. */
		public void add(Vec3 sample) {
			ring[head] = sample;
			head = (head + 1) % ring.length;
			if (size < ring.length) {
				size++;
			}
		}

		/** Samples currently held — the ring's capacity minus however many ticks have yet to pass. */
		public int size() {
			return size;
		}

		/** True before the first sample: cues then have nothing stale to play from and use the live position. */
		public boolean isEmpty() {
			return size == 0;
		}

		/** The newest sample — where the stalker was this tick. */
		public Vec3 newest() {
			return at(0);
		}

		/** The oldest sample — where the stalker was up to capacity ticks ago; the phantom origin. */
		public Vec3 oldest() {
			return at(size - 1);
		}

		/**
		 * The sample from {@code ticks} ticks ago (0 = newest), clamped to the oldest held sample:
		 * early on there is less memory than the lie wants, and the oldest available is still a lie.
		 */
		public Vec3 ticksAgo(int ticks) {
			if (size == 0) {
				throw new IllegalStateException("empty history");
			}
			return at(Math.min(Math.max(ticks, 0), size - 1));
		}

		/** The sample {@code fromNewest} back from the newest (0 = newest, {@code size - 1} = oldest). */
		private Vec3 at(int fromNewest) {
			return ring[Math.floorMod(head - 1 - fromNewest, ring.length)];
		}
	}
}
