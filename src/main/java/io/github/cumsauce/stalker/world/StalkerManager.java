package io.github.cumsauce.stalker.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.world.ApproachPlanner;

import io.github.cumsauce.stalker.world.BindingRules.Owner;
import io.github.cumsauce.stalker.sound.Cues;
import io.github.cumsauce.stalker.strike.Strikes;

/**
 * The server-side orchestrator (plan §1–§4, §7): minute-roll binding, the
 * STALK/COVER/CHASE/STUN state machine, senses, cover decisions, peek and
 * freeze timing, strike triggers, stuck handling and relocation.
 *
 * <p>Runs on {@code ServerTickEvents.END_WORLD_TICK} per level. All mutable
 * state lives here keyed by the player's UUID, so nothing depends on the
 * villager staying loaded. Movement itself is delegated: each tick the chosen
 * move target is written into the villager via
 * {@link StalkerBrain#setTarget(Villager, Vec3, float)}; strike/chase/stun
 * execution lives in {@link Strikes}.
 */
public final class StalkerManager {

	/** Unspotted ticks required to leave COVER (plan §3). */
	private static final int UNSPOTTED_EXIT_TICKS = 100;

	/** Spotted latch duration (plan §3). */
	private static final int SPOTTED_LATCH_TICKS = 100;

	/** Freeze fallback duration (plan §3). */

	/** Peek duration (plan §3). */
	private static final int PEEK_TICKS = 30;

	/** Cover search throttle: one search per 2 s per villager (plan §3). */
	private static final int COVER_SEARCH_COOLDOWN = 40;

	/** Look-away dot below which approach is allowed in the 24–64 band (plan §2). */
	private static final double APPROACH_DOT = 0.35;

	/** Strike cooldown after a stun timeout, 5 s (plan §4; no table key — nearest is chaseDurationTicks scale). */
	private static final int STRIKE_COOLDOWN_AFTER_STUN = 100;

	/** Spotted dash speed (plan §3). */

	/** Closing speed (plan §2). */
	private static final float STALK_SPEED = 1.0F;

	/** Within this distance of its cover the villager counts as hidden (plan §3). */
	private static final double AT_COVER_DISTANCE = 1.5;

	/** Player proximity that forces a cover re-search (plan §3). */
	private static final double COVER_DISTURB_DISTANCE = 8.0;

	/** Peek only while the player is within this range (plan §3). */
	private static final double PEEK_PLAYER_RANGE = 32.0;

	/** Beyond this the villager counts as fallen too far behind (plan §7). */
	private static final double RELOCATE_DISTANCE = 128.0;

	/** A cover dash that takes longer than this has failed (unreachable point) — give up on it. */
	private static final int DASH_TIMEOUT_TICKS = 80;

	/** Ticks between stuck samples (plan §2 said 60; 40 reacts faster and still avoids false positives). */
	private static final int STUCK_SAMPLE_TICKS = 40;

	/** How long an "advance" decision stays committed before the unseen gate is re-evaluated.
	 * Without this the gate flips tick by tick as the player's view sweeps, and the villager
	 * starts and stops in bursts. */
	private static final int APPROACH_COMMIT_TICKS = 30;

	/** Fractions of the villager-to-player line used as staged approach points. */
	private static final double[] APPROACH_FRACTIONS = {0.66, 0.45, 0.28};

	/** Rebuild the approach plan when the player has moved this far from where it was planned. */
	private static final double PLAN_STALE_DISTANCE = 8.0;

	/** Rebuild the approach plan after this many ticks even if the player stayed put. */
	private static final int PLAN_MAX_AGE_TICKS = 200;

	/** Slower escape (was 1.25 — a full sprint): reads as scrambling, not teleporting. */
	private static final float COVER_SPRINT_SPEED = 0.95F;

	/** Ticks between pretend-normal idle glances (never at the player). */
	private static final int NORMAL_LOOK_INTERVAL_TICKS = 40;

	/** Ticks a pretend-normal wander drift lasts before re-evaluating. */
	private static final int NORMAL_WANDER_INTERVAL_TICKS = 80;

	/** Require-alone radius for binding (plan tuning: requireAlone = no other player within 64). */
	private static final double ALONE_RADIUS = 64.0;

	/** Ticks of total unresolved absence before an orphaned binding is dropped (plan §7 gives no key; 2× relocateBehindTicks). */
	private static final int ORPHAN_UNBIND_TICKS = 1200;

	/** Per-player stalker state. */
	private static final Map<UUID, State> STATES = new HashMap<>();

	/** Per-player debug particle toggle (/stalker debug). */
	private static final Map<UUID, Boolean> DEBUG_PLAYERS = new HashMap<>();

	private static boolean initialized = false;

	private StalkerManager() {
	}

	/** Registers the world-tick hook. Called once from the mod initializer. */
	public static synchronized void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		// 26.3 Fabric renamed END_WORLD_TICK to END_LEVEL_TICK (same callback shape).
		ServerTickEvents.END_LEVEL_TICK.register(StalkerManager::tickLevel);
		// Drop per-player bookkeeping when a player leaves: the tick loop only sees online players,
		// so a player who logs off mid-CHASE/STUN would otherwise leave their Strikes entry forever.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (player != null) {
				Strikes.clear(player);
			}
		});
	}

	// ------------------------------------------------------------------ tick

	private static void tickLevel(ServerLevel level) {
		StalkerConfig c = StalkerConfig.get();
		if (level.getGameTime() % 1200L == 0L) {
			rollRandomBinds(level, c);
		}
		for (ServerPlayer player : level.players()) {
			State state = STATES.computeIfAbsent(player.getUUID(), id -> fromAttachment(level, player));
			if (state != null) {
				tickStalker(level, player, state, c);
			}
		}
	}

	/** Rebuilds manager state from the persisted player-side attachment after a restart. */
	private static State fromAttachment(ServerLevel level, ServerPlayer player) {
		Optional<UUID> villagerId = Attachments.boundVillager(player);
		if (villagerId.isEmpty()) {
			return null;
		}
		State state = new State(villagerId.get());
		// After a reload the villager resumes vanilla-free stalking in STALK.
		state.phase = Phases.Phase.STALK;
		return state;
	}

	private static void tickStalker(ServerLevel level, ServerPlayer player, State state, StalkerConfig c) {
		state.tick(level, player, c);

		Villager villager = resolveVillager(level, player, state, c);
		if (villager == null) {
			return;
		}
		if (!villager.isAlive()) {
			// Villager died or was removed (zombie conversion, lightning, kill):
			// unbind + blacklist (plan §1).
			dropBinding(level, player, state, true, false);
			return;
		}

		// Creative/spectator cause instant unbind, no blacklist (plan §7). The villager side must
		// be cleared too, or STALKED_BY stays set and the villager can never bind again.
		if (player.isCreative() || player.isSpectator()) {
			dropBinding(level, player, state, false, true);
			return;
		}

		// Player death: during CHASE unbind + blacklist + satisfied cue; outside
		// CHASE the binding survives (plan §4, §7).
		if (player.isDeadOrDying()) {
			if (state.phase == Phases.Phase.CHASE) {
				dropBinding(level, player, state, true, true);
				Cues.playerDied(villager);
				StalkerBrain.setTarget(villager, null, 0.0F);
			}
			return;
		}

		double distance = villager.distanceTo(player);

		// Relocation accounting (plan §7).
		if (distance > RELOCATE_DISTANCE) {
			state.relocateTicks++;
		} else {
			state.relocateTicks = 0;
		}
		if (state.relocateTicks > c.relocateBehindTicks()) {
			if (relocate(villager, player, level, c)) {
				state.relocateTicks = 0;
				state.resetMovement();
				return;
			}
		}

		// Senses update (STALK and COVER both watch the spotted latch).
		if (state.phase == Phases.Phase.STALK || state.phase == Phases.Phase.COVER) {
			if (Senses.isSpotted(level, player, villager, c.spottedRange(), c.spottedAngleDeg())) {
				state.spottedTicks = SPOTTED_LATCH_TICKS;
			} else if (state.spottedTicks > 0) {
				state.spottedTicks--;
			}
		}

		if (state.strikeCooldown > 0) {
			state.strikeCooldown--;
		}

		switch (state.phase) {
			case STALK -> tickStalk(level, player, villager, state, c, distance);
			case COVER -> tickCover(level, player, villager, state, c, distance);
			case CHASE -> tickChase(level, player, villager, state, c);
			case STUN -> tickStun(level, player, villager, state, c, distance);
		}

		tickCues(villager, player, state, distance, c);
	}

	// ---------------------------------------------------------------- STALK

	private static void tickStalk(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c, double distance) {
		// Spotted: dash to cover (plan §3).
		if (state.spottedTicks > 0 && distance >= 4.0) {
			enterCover(level, player, villager, state, c);
			return;
		}

		// Strike trigger (plan §4): close, roughly behind, STALK/COVER only.
		if (distance < c.strikeRange() && state.strikeCooldown <= 0
			&& Senses.behindEnough(player, villager, c.strikeBehindDot())) {
			Strikes.strike(villager, player);
			state.resetMovement();
			return;
		}

		// Hysteresis on the unseen gate (plan §2): re-evaluated only when no advance is committed.
		// Evaluating it every tick made the target flip between advance and hold as the player's
		// view swept, and the villager moved in bursts.
		if (state.spottedTicks > 0) {
			// Being watched cancels a committed advance immediately.
			state.advanceTicks = 0;
		} else if (state.advanceTicks > 0) {
			state.advanceTicks--;
		} else if (isApproachAllowed(player, villager)) {
			state.advanceTicks = APPROACH_COMMIT_TICKS;
		}
		boolean advancing = state.advanceTicks > 0;

		Vec3 target;
		if (distance > RELOCATE_DISTANCE || distance > 64.0) {
			// > 64: close directly, stealth optional (catch-up).
			target = player.position();
		} else if (advancing) {
			// Committed advance: walk the committed hop plan (built through cover, legs out of
			// sight) and rebuild it only when it is spent, stale, or the player has moved away
			// from where it was planned. This is what makes the approach look thought out.
			target = nextPlanHop(level, player, villager, state);
			if (target == null) {
				target = player.position();
			}
		} else if (distance >= c.orbitInner()) {
			// Watched inside the orbit: seek cover and watch from it.
			Optional<BlockPos> cover = ensureCover(level, player, villager, state, c);
			target = cover
				.map(pos -> Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5))
				.filter(pos -> pos.distanceTo(villager.position()) > 2.0)
				.orElse(null);
		} else {
			target = null;
		}

		// In view with nowhere to go: mill about like an ordinary villager instead of standing
		// frozen and staring. This is what makes being watched survivable for the illusion.
		if (target == null && state.spottedTicks > 0) {
			actNormal(level, player, villager, state);
			return;
		}

		issueMovement(villager, state, target, STALK_SPEED);
	}

	/**
	 * The next hop of the committed approach plan, or null when there is none. The plan is a queue
	 * of cover hops built by {@link ApproachPlanner} — each hop makes progress toward the player
	 * and every leg between hops stays out of the player's sight (or is short enough to dash).
	 * It is rebuilt when spent, when the player strays from the anchor it was planned against,
	 * or after a while even if they did not (terrain may have changed).
	 */
	private static Vec3 nextPlanHop(ServerLevel level, ServerPlayer player, Villager villager, State state) {
		Vec3 playerPos = player.position();
		// Advance the queue past hops already reached.
		while (!state.plan.isEmpty() && villager.position().distanceTo(state.plan.peek()) < 2.0) {
			state.plan.poll();
		}
		boolean stale = state.plan.isEmpty()
			|| state.planAnchor == null
			|| state.planAnchor.distanceTo(playerPos) > PLAN_STALE_DISTANCE
			|| state.planAge++ > PLAN_MAX_AGE_TICKS;
		if (!stale) {
			Vec3 hop = state.plan.peek();
			return hop == null ? null : hop;
		}
		List<Vec3> candidates = new ArrayList<>();
		for (double fraction : APPROACH_FRACTIONS) {
			Vec3 sample = villager.position().lerp(playerPos, fraction);
			for (BlockPos pos : CoverSearch.findApproachPoint(level, sample, player.getEyePosition())
					.stream().limit(2).toList()) {
				candidates.add(Vec3.atLowerCornerOf(pos).add(0.5, 0.0, 0.5));
			}
		}
		Predicate<Vec3> seen = point -> Senses.canSee(level, player.getEyePosition(), point.add(0.0, 1.0, 0.0));
		List<Vec3> hops = ApproachPlanner.plan(villager.position(), playerPos, candidates, seen);
		state.plan.clear();
		state.plan.addAll(hops);
		state.planAnchor = playerPos;
		state.planAge = 0;
		Vec3 hop = state.plan.peek();
		return hop == null ? null : hop;
	}

	/** Unseen-approach gate (plan §2): player looking away (dot ≤ 0.35) or LOS blocked. */
	private static boolean isApproachAllowed(ServerPlayer player, Villager villager) {
		double dot = Senses.lookDot(player.getLookAngle(), player.getEyePosition(), villager.getEyePosition());
		return dot <= APPROACH_DOT || !Senses.canSee(player.level(), player, villager);
	}

	// ---------------------------------------------------------------- COVER

	private static void enterCover(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c) {
		state.phase = Phases.Phase.COVER;
		state.unspottedTicks = 0;
		state.peekCooldown = state.peekCooldown <= 0 ? 140 + level.getRandom().nextInt(121) : state.peekCooldown;
		state.coverPos = null;
		// A dash for cover is urgent: bypass the routine search throttle, otherwise a spotted
		// villager inside the cooldown window finds nothing and falls through to the freeze.
		state.coverSearchCooldown = 0;
		Cues.dashForCover(villager);
		findCoverNow(level, player, villager, state, c);
	}

	private static void tickCover(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c, double distance) {
		// In COVER within 4 blocks it lunges regardless of facing (plan §4).
		if (distance < 4.0 && state.strikeCooldown <= 0) {
			villager.setPose(Pose.STANDING);
			Strikes.strike(villager, player);
			state.resetMovement();
			return;
		}
		if (distance < c.strikeRange() && state.strikeCooldown <= 0
			&& Senses.behindEnough(player, villager, c.strikeBehindDot())) {
			villager.setPose(Pose.STANDING);
			Strikes.strike(villager, player);
			state.resetMovement();
			return;
		}

		// The player walked into the hiding spot: new cover or act normal (plan §3).
		if (state.coverPos == null
			|| Vec3.atLowerCornerOf(state.coverPos).add(0.5, 0.0, 0.5).distanceTo(player.position()) < COVER_DISTURB_DISTANCE) {
			if (!findCoverNow(level, player, villager, state, c)) {
				actNormal(level, player, villager, state);
				return;
			}
			state.dashTicks = 0;
		}

		Vec3 coverCenter = Vec3.atLowerCornerOf(state.coverPos).add(0.5, 0.0, 0.5);
		if (villager.position().distanceTo(coverCenter) > AT_COVER_DISTANCE) {
			// Sprint to the cover point; a dash that outlives its timeout is unreachable, so drop
			// the point (the next search picks a different candidate) and act normal meanwhile.
			villager.setPose(Pose.STANDING);
			state.dashTicks++;
			if (state.dashTicks > DASH_TIMEOUT_TICKS) {
				state.coverPos = null;
				state.resetMovement();
				StalkerBrain.setTarget(villager, null, 0.0F);
				actNormal(level, player, villager, state);
				return;
			}
			issueMovement(villager, state, coverCenter, COVER_SPRINT_SPEED);
			return;
		}
		state.dashTicks = 0;

		// Hidden at cover.
		StalkerBrain.setTarget(villager, null, 0.0F);
		villager.setPose(Pose.CROUCHING);

		// Peeking (plan §3): player near, not looking at the cover point.
		if (distance <= PEEK_PLAYER_RANGE && !Senses.lookingAtPos(player, coverCenter.add(0.0, 1.0, 0.0), c.spottedAngleDeg())) {
			if (state.peekTicks > 0) {
				state.peekTicks--;
				StalkerBrain.lookAt(villager, player);
				// Sidestep ~0.75 blocks out the open side, then back.
				Vec3 away = villager.position().subtract(player.position());
				Vec3 side = new Vec3(-away.z, 0.0, away.x).normalize().scale(0.75);
				Vec3 peekTarget = coverCenter.add(state.peekSide ? side : side.scale(-1.0));
				issueMovement(villager, state, peekTarget, STALK_SPEED);
				if (state.peekTicks == 0) {
					issueMovement(villager, state, coverCenter, STALK_SPEED);
				}
			} else {
				state.peekCooldown--;
				if (state.peekCooldown <= 0) {
					state.peekTicks = PEEK_TICKS;
					state.peekSide = level.getRandom().nextBoolean();
					state.peekCooldown = 140 + level.getRandom().nextInt(121);
				}
			}
		}

		// Unspotted for 100 ticks → back to STALK (plan §3).
		if (state.spottedTicks <= 0) {
			state.unspottedTicks++;
			if (state.unspottedTicks >= UNSPOTTED_EXIT_TICKS) {
				state.phase = Phases.Phase.STALK;
				villager.setPose(Pose.STANDING);
				state.coverPos = null;
				state.resetMovement();
			}
		} else {
			state.unspottedTicks = 0;
		}
	}

	/**
	 * The pretend-normal fallback (plan §3), rewritten from play feedback: the villager was caught
	 * in the open with no usable cover, so instead of standing frozen and staring it mills about
	 * like an ordinary villager — slow drifts away from the player, idle glances at random points,
	 * never a fixed stare. It stays in this mode while it is being watched (spotted) and resumes
	 * stalking through the normal unspotted exit.
	 */
	private static void actNormal(ServerLevel level, ServerPlayer player, Villager villager, State state) {
		villager.setPose(Pose.STANDING);

		// Drift: every so often wander a couple of blocks to a neutral point, biased away from the
		// player, then stop and idle.
		if (state.normalWanderTarget != null) {
			if (villager.position().distanceTo(state.normalWanderTarget) < 1.0) {
				state.normalWanderTarget = null;
				issueMovement(villager, state, null, STALK_SPEED);
			} else if (state.dashTicks++ > NORMAL_WANDER_INTERVAL_TICKS) {
				state.normalWanderTarget = null;
				issueMovement(villager, state, null, STALK_SPEED);
			}
		} else if (state.normalWanderTicks > 0) {
			state.normalWanderTicks--;
			if (state.normalWanderTicks == 0 && level.getRandom().nextInt(3) == 0) {
				Vec3 away = villager.position().subtract(player.position()).multiply(1.0, 0.0, 1.0);
				Vec3 dir = away.lengthSqr() < 1.0E-4
					? new Vec3(Math.cos(level.getRandom().nextDouble() * Math.PI * 2.0), 0.0,
						Math.sin(level.getRandom().nextDouble() * Math.PI * 2.0))
					: away.normalize();
				Vec3 side = new Vec3(-dir.z, 0.0, dir.x).scale((level.getRandom().nextDouble() - 0.5) * 2.0);
				Vec3 drift = villager.position().add(dir.scale(2.0 + level.getRandom().nextInt(3))).add(side);
				state.normalWanderTarget = drift;
				state.dashTicks = 0;
				issueMovement(villager, state, drift, 0.55F);
			}
		} else {
			state.normalWanderTicks = NORMAL_WANDER_INTERVAL_TICKS;
		}

		// Idle glances: random nearby points on a slow cadence, never straight at the player.
		if (state.normalLookTicks <= 0) {
			state.normalLookTicks = NORMAL_LOOK_INTERVAL_TICKS + level.getRandom().nextInt(NORMAL_LOOK_INTERVAL_TICKS);
			double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
			double dist = 3.0 + level.getRandom().nextInt(6);
			StalkerBrain.lookAt(villager, villager.position().add(
				Math.cos(angle) * dist, 1.0 + level.getRandom().nextDouble() * 1.5, Math.sin(angle) * dist));
		} else {
			state.normalLookTicks--;
		}
	}

	/**
	 * Runs one (throttled) cover search; returns true when a cover point is set. The top ranked
	 * candidates are each probed with a real pathfind (bounded to three probes per search), so the
	 * committed point is one the villager can actually reach — unreachable points used to be
	 * discovered only by dashing into them until the timeout.
	 */
	private static boolean findCoverNow(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c) {
		if (state.coverSearchCooldown > 0) {
			return state.coverPos != null;
		}
		state.coverSearchCooldown = COVER_SEARCH_COOLDOWN;
		var candidates = CoverSearch.findCoverRanked(level, villager.position(),
			player.getEyePosition(), player.getLookAngle());
		int probes = 0;
		for (BlockPos candidate : candidates) {
			if (probes >= 3) {
				break;
			}
			probes++;
			if (villager.getNavigation().createPath(candidate, 0) != null) {
				state.coverPos = candidate;
				return true;
			}
		}
		// Nothing reachable: fall back to the best-scoring candidate anyway (approaching it may
		// still improve the situation, and acting normal with zero cover is worse).
		state.coverPos = candidates.isEmpty() ? null : candidates.get(0);
		return state.coverPos != null;
	}

	private static Optional<BlockPos> ensureCover(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c) {
		if (state.coverSearchCooldown <= 0 || state.coverPos == null) {
			findCoverNow(level, player, villager, state, c);
		}
		return Optional.ofNullable(state.coverPos);
	}

	// ----------------------------------------------------------- CHASE / STUN

	private static void tickChase(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c) {
		state.chaseTicks++;
		Strikes.tickChase(villager, player);
		// The blindness timer IS the chase timer (plan §4): when it expires with
		// the player alive, the villager goes dazed. This edge is owned here alone (Strikes only
		// drops its bookkeeping), so clear that bookkeeping too: a player who cuts the blindness
		// short (milk) ends the chase before Strikes' tick counter would, and a surviving entry
		// would resume mid-count on the next chase.
		if (!player.hasEffect(MobEffects.BLINDNESS)) {
			Strikes.clear(player);
			state.phase = Phases.Phase.STUN;
			state.stunTicks = c.stunDurationTicks();
			state.chaseTicks = 0;
			// Applies the stun: NAUSEA, navigation stop, target clear, stun cue (plan §4).
			Strikes.stun(villager, player);
		}
	}

	private static void tickStun(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c, double distance) {
		// Escape window: player > escape distance → unbind + blacklist (plan §4). The villager side
		// must be cleared too, or STALKED_BY stays set and the villager can never bind again.
		if (distance > c.stunEscapeDistance()) {
			dropBinding(level, player, state, true, true);
			return;
		}
		Strikes.tickStun(villager, player);
		StalkerBrain.setTarget(villager, null, 0.0F);
		state.stunTicks--;
		if (state.stunTicks <= 0) {
			// Back to STALK; the 5 s strike cooldown keeps the escape window real.
			state.phase = Phases.Phase.STALK;
			state.strikeCooldown = STRIKE_COOLDOWN_AFTER_STUN;
		}
	}

	// ------------------------------------------------------------------ cues

	private static void tickCues(Villager villager, ServerPlayer player, State state, double distance, StalkerConfig c) {
		if (state.phase != Phases.Phase.STALK && state.phase != Phases.Phase.COVER) {
			return;
		}
		if (state.mumbleCooldown > 0) {
			state.mumbleCooldown--;
		} else {
			if (distance <= 32.0) {
				Cues.stalkingMumble(villager);
			}
			int span = Math.max(1, c.mumbleMaxIntervalTicks() - c.mumbleMinIntervalTicks() + 1);
			state.mumbleCooldown = c.mumbleMinIntervalTicks() + villager.getRandom().nextInt(span);
		}
		if (state.footstepCooldown > 0) {
			state.footstepCooldown--;
		} else if (state.lastTarget != null && distance >= c.footstepMinDistance() && distance <= c.footstepMaxDistance()
			&& !Senses.canSee(player.level(), player, villager)) {
			Cues.footstep(villager);
			state.footstepCooldown = c.footstepIntervalTicks();
		}
	}

	// ------------------------------------------------------------ movement io

	/** Re-issues the move target every 10 ticks or when it moved > 2 blocks (plan §2). */
	private static void issueMovement(Villager villager, State state, Vec3 target, float speed) {
		if (target != null) {
			boolean stale = state.lastTarget == null
				|| state.lastTarget.distanceToSqr(target) > 4.0
				|| state.ticksSinceIssue >= 10;
			if (stale) {
				StalkerBrain.setTarget(villager, target, speed);
				state.lastTarget = target;
				state.ticksSinceIssue = 0;
			} else {
				state.ticksSinceIssue++;
			}
		} else {
			StalkerBrain.setTarget(villager, null, 0.0F);
			state.lastTarget = null;
			state.ticksSinceIssue = 0;
		}
		// Stuck handling (plan §2): sampled every 60 ticks in the state.
		state.wantsToMove = target != null;
	}

	// ----------------------------------------------------------- stuck / move

	private static void tickStuckCheck(ServerLevel level, ServerPlayer player, Villager villager, State state, StalkerConfig c) {
		if (state.stuckSampleTicks++ < STUCK_SAMPLE_TICKS) {
			return;
		}
		state.stuckSampleTicks = 0;
		if (!state.wantsToMove) {
			state.stuckCycles = 0;
			return;
		}
		Vec3 now = villager.position();
		boolean movedEnough = state.lastSampledPos != null
			&& Math.hypot(now.x - state.lastSampledPos.x, now.z - state.lastSampledPos.z) >= 0.5;
		state.lastSampledPos = now;
		if (movedEnough) {
			state.stuckCycles = 0;
			return;
		}
		villager.stopRiding();
		if (villager.horizontalCollision) {
			villager.jumpFromGround();
		}
		// Drop the current path so a fresh one is computed for the same target; otherwise the
		// navigation keeps replaying a route the villager demonstrably cannot follow.
		villager.getNavigation().stop();
		state.stuckCycles++;
		if (state.stuckCycles >= 2) {
			// One-time catch-up teleport, same rules as relocation (plan §2).
			if (relocate(villager, player, level, c)) {
				state.stuckCycles = 0;
				state.resetMovement();
			}
		}
	}

	// ------------------------------------------------------------- relocation

	/**
	 * Teleports the villager to a silent, out-of-sight ring point around the
	 * player in the player's dimension (plan §7). Returns false when no point
	 * was found or cross-dimension follow is disabled.
	 */
	private static boolean relocate(Villager villager, ServerPlayer player, ServerLevel playerLevel, StalkerConfig c) {
		if (villager.level() != playerLevel && !c.crossDimensionFollow()) {
			dropVillagerSide(player);
			return false;
		}
		var point = CoverSearch.findRelocationPoint(playerLevel, player.position(),
			(int) c.relocateRingMin(), (int) c.relocateRingMax());
		if (point.isEmpty()) {
			return false;
		}
		BlockPos pos = point.get();
		int y = playerLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
		if (villager.level() != playerLevel) {
			// Cross-dimension: move the live entity into the player's dimension.
			// 26.3: teleportTo returns a success boolean, not the moved entity.
			return villager.teleportTo(
				playerLevel, pos.getX() + 0.5, y, pos.getZ() + 0.5, Set.of(), villager.getYRot(), 0.0F, false);
		}
		villager.teleportTo(pos.getX() + 0.5, y, pos.getZ() + 0.5);
		villager.setPose(Pose.STANDING);
		return true;
	}

	/**
	 * Resolves the bound villager for this tick (plan §7): exact-dimension
	 * lookup first, then any-dimension lookup with the relocation clock for
	 * unloaded / cross-dimension cases.
	 */
	private static Villager resolveVillager(ServerLevel level, ServerPlayer player, State state, StalkerConfig c) {
		Entity inLevel = level.getEntity(state.villagerId);
		if (inLevel instanceof Villager villager) {
			state.unloadedTicks = 0;
			tickStuckCheck(level, player, villager, state, c);
			return villager;
		}
		// Not in the player's dimension (or chunk-unloaded).
		state.unloadedTicks++;
		Entity anywhere = level.getEntityInAnyDimension(state.villagerId);
		if (anywhere instanceof Villager villager) {
			if (state.unloadedTicks > c.relocateBehindTicks() && relocate(villager, player, level, c)) {
				state.unloadedTicks = 0;
				return villager;
			}
			// Cross-dimension villager exists but is not due for relocation yet.
			return null;
		}
		// Fully unloaded (or converted/removed): the removal hook clears the
		// attachment; this clock is the quiet fallback for orphans.
		if (state.unloadedTicks > ORPHAN_UNBIND_TICKS) {
			dropPlayerSide(player);
			STATES.remove(player.getUUID());
		}
		return null;
	}

	// -------------------------------------------------------------- binding

	/** The every-minute random roll (plan §1). */
	private static void rollRandomBinds(ServerLevel level, StalkerConfig c) {
		for (ServerPlayer player : level.players()) {
			if (Attachments.boundVillager(player).isPresent()) {
				continue;
			}
			if (player.isCreative() || player.isSpectator()) {
				continue;
			}
			if (c.requireNight() && level.isBrightOutside()) {
				continue;
			}
			if (c.requireAlone() && !isAlone(level, player)) {
				continue;
			}
			if (level.getRandom().nextDouble() >= c.bindChancePerMinute()) {
				continue;
			}
			if (tryBindNearest(level, player, c)) {
				break;
			}
		}
	}

	/** True when no other player is within the alone radius. */
	private static boolean isAlone(ServerLevel level, ServerPlayer player) {
		return level.players().stream()
			.filter(other -> other != player)
			.noneMatch(other -> other.distanceTo(player) <= ALONE_RADIUS);
	}

	/** Binds the nearest eligible villager; used by the random roll and /stalker start. */
	private static boolean tryBindNearest(ServerLevel level, ServerPlayer player, StalkerConfig c) {
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager villager : level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(c.bindMaxDistance()))) {
			if (!isEligible(level, player, villager)) {
				continue;
			}
			double dist = villager.distanceTo(player);
			if (dist < bestDist) {
				bestDist = dist;
				best = villager;
			}
		}
		if (best == null) {
			return false;
		}
		performBind(level, best, player);
		return true;
	}

	/**
	 * Eligibility per plan §1: adult, unbound, not blacklisted. Range and dimension checked by the caller.
	 *
	 * <p>A one-sided binding — {@code STALKED_BY} set on a villager whose player no longer claims it
	 * back (left over from an older save, an interrupted teardown, or a deleted player) — would
	 * otherwise make the villager ineligible forever, since nothing else clears it. That is treated
	 * as unbound and healed here.
	 */
	private static boolean isEligible(ServerLevel level, ServerPlayer player, Villager villager) {
		boolean isBound = Attachments.isBound(villager);
		Owner owner = isBound ? ownerState(level, villager) : Owner.UNKNOWN;
		// A stale one-sided binding is healed rather than left to make the villager ineligible forever.
		if (isBound && !BindingRules.bindingIsLive(owner)) {
			Attachments.unbindVillager(villager);
		}
		return BindingRules.isEligible(villager.isBaby(), isBound,
			owner, Attachments.isBlacklisted(level, player, villager.getUUID()));
	}

	/**
	 * What the villager's recorded owner looks like: gone (UNKNOWN), offline (binding kept, plan §7),
	 * online and claiming it back, or online but no longer pointing at it (stale).
	 */
	private static Owner ownerState(ServerLevel level, Villager villager) {
		UUID ownerId = Attachments.boundPlayer(villager).orElse(null);
		if (ownerId == null) {
			return Owner.UNKNOWN;
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null) {
			return Owner.OFFLINE;
		}
		return Attachments.boundVillager(owner).filter(villager.getUUID()::equals).isPresent()
			? Owner.CLAIMS_BACK
			: Owner.DISOWNED;
	}

	/**
	 * Attempts a bind, returning null on success or a short reason on failure — the reason is what
	 * makes {@code /stalker start} diagnosable in game.
	 */
	public static String bindFailure(ServerPlayer player) {
		if (Attachments.boundVillager(player).isPresent()) {
			return player.getName().getString() + " is already stalked.";
		}
		if (player.isCreative() || player.isSpectator()) {
			return "creative/spectator players are never bound.";
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return "the player is not in a server level.";
		}
		StalkerConfig c = StalkerConfig.get();
		int adults = 0;
		int bound = 0;
		int blacklisted = 0;
		Villager best = null;
		double bestDist = Double.MAX_VALUE;
		for (Villager villager : level.getEntitiesOfClass(Villager.class,
				player.getBoundingBox().inflate(c.bindMaxDistance()))) {
			if (villager.isBaby()) {
				continue;
			}
			adults++;
			if (Attachments.isBound(villager) && BindingRules.bindingIsLive(ownerState(level, villager))) {
				bound++;
				continue;
			}
			if (Attachments.isBlacklisted(level, player, villager.getUUID())) {
				blacklisted++;
				continue;
			}
			double dist = villager.distanceTo(player);
			if (dist < bestDist) {
				bestDist = dist;
				best = villager;
			}
		}
		if (best == null) {
			return "no eligible villager within " + (int) c.bindMaxDistance() + " blocks (found " + adults
					+ " adult(s): " + bound + " already bound, " + blacklisted + " blacklisted).";
		}
		performBind(level, best, player);
		return null;
	}

	/** Executes a bind: attachments, persistence, dismount, brain stop, state. */
	private static void performBind(ServerLevel level, Villager villager, ServerPlayer player) {
		Attachments.bind(villager, player);
		villager.setPersistenceRequired();
		villager.stopRiding();
		villager.getBrain().stopAll(level, villager);
		State state = new State(villager.getUUID());
		state.phase = Phases.Phase.STALK;
		STATES.put(player.getUUID(), state);
	}

	// ------------------------------------------------------- command surface

	/** Forces a bind for the player (/stalker start). True when one was made. */
	public static boolean bind(ServerPlayer player) {
		if (Attachments.boundVillager(player).isPresent()) {
			return false;
		}
		// Creative/spectator players are never bound (plan §7). Without this, /stalker start on a
		// creative player binds and is dropped again the next tick, orphaning the villager's side.
		if (player.isCreative() || player.isSpectator()) {
			return false;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}
		return tryBindNearest(level, player, StalkerConfig.get());
	}

	/** Clears the player's binding (/stalker stop), without blacklisting. */
	public static void unbind(ServerPlayer player) {
		unbind(player, false);
	}

	/**
	 * Clears the player's binding. {@code blacklist} writes the 10-minute immunity for the pair
	 * (plan §1/§4/§6: it got what it wanted).
	 *
	 * <p>Callers that both unbind and blacklist must use this one method: doing
	 * {@code unbind(player); blacklistAfterSuccess(player);} does not work, because the first call
	 * removes the state and the player-side attachment that the second one reads the villager UUID from.
	 */
	public static void unbind(ServerPlayer player, boolean blacklist) {
		State state = STATES.remove(player.getUUID());
		// The villager UUID comes from the state, falling back to the player-side attachment: after a
		// reload the state is rebuilt lazily, so /stalker stop on a fresh session would otherwise
		// clear only the player's side and leave the villager bound (and ineligible) forever.
		UUID villagerId = state != null ? state.villagerId : Attachments.boundVillager(player).orElse(null);
		if (blacklist && villagerId != null && player.level() instanceof ServerLevel blacklistLevel) {
			Attachments.blacklist(blacklistLevel, player, villagerId, StalkerConfig.get().blacklistTicks());
		}
		Attachments.unbindPlayer(player);
		Strikes.clear(player);
		if (villagerId != null && player.level() instanceof ServerLevel level) {
			if (level.getEntityInAnyDimension(villagerId) instanceof Villager villager) {
				Attachments.unbindVillager(villager);
				StalkerBrain.release(villager);
			}
		}
	}

	/** Human-readable status line (/stalker status). */
	public static String status(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		if (state == null) {
			return "no binding";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("phase=").append(state.phase)
			.append(" villager=").append(state.villagerId);
		if (player.level() instanceof ServerLevel level && level.getEntity(state.villagerId) instanceof Villager villager) {
			sb.append(" profession=").append(professionName(villager))
				.append(" distance=").append(String.format("%.1f", villager.distanceTo(player)))
				.append(" los=").append(Senses.canSee(player.level(), player, villager));
		} else {
			sb.append(" (unloaded)");
		}
		sb.append(" spotted=").append(state.spottedTicks)
			.append(" cover=").append(state.coverPos)
			.append(" stun=").append(state.stunTicks)
			.append(" strikeCooldown=").append(state.strikeCooldown)
			.append(" relocateTicks=").append(state.relocateTicks);
		return sb.toString();
	}

	/** Forces a phase (/stalker phase). Test hook. */
	public static void forcePhase(ServerPlayer player, Phases.Phase phase) {
		State state = STATES.get(player.getUUID());
		if (state == null) {
			return;
		}
		state.phase = phase;
		state.spottedTicks = 0;
		state.unspottedTicks = 0;
		if (phase == Phases.Phase.STUN) {
			state.stunTicks = StalkerConfig.get().stunDurationTicks();
		}
		if (phase == Phases.Phase.CHASE) {
			state.chaseTicks = 0;
			// The chase timer is the blindness effect (plan §4); without it the next tick flips
			// straight to STUN and the forced chase is never observable.
			player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.BLINDNESS, StalkerConfig.get().chaseDurationTicks()));
		}
		if (phase == Phases.Phase.STALK || phase == Phases.Phase.COVER) {
			state.strikeCooldown = 0;
		}
	}

	/** Forces the spotted condition for 100 ticks (/stalker spot). */
	public static void forceSpot(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		if (state != null) {
			state.spottedTicks = SPOTTED_LATCH_TICKS;
		}
	}

	/** Forces a variant B strike now (/stalker strike). */
	public static void forceStrike(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		if (state == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		// Ignore while already chasing/stunned: a delayed pretend-bribe must not re-strike mid-chase
		// (re-rolling the variant and restarting the timer). /stalker strike from STALK/COVER still works.
		if (state.phase == Phases.Phase.CHASE || state.phase == Phases.Phase.STUN) {
			return;
		}
		if (level.getEntity(state.villagerId) instanceof Villager villager) {
			Strikes.strike(villager, player);
		}
	}

	/** Debug dump (/stalker debug). Returns the same detail as status plus internals. */
	public static String debug(ServerPlayer player) {
		return "debug: " + (isDebugging(player) ? "on" : "off") + " | " + status(player) + " states=" + STATES.size();
	}

	/**
	 * Phase write-back for {@link Strikes} (it performs the variant and
	 * switches phase through this hook). Locates the owning state by the
	 * villager's UUID.
	 */
	public static void setPhase(Villager villager, Phases.Phase phase) {
		for (State state : STATES.values()) {
			if (state.villagerId.equals(villager.getUUID())) {
				state.phase = phase;
				state.resetMovement();
				return;
			}
		}
	}

	/** Current phase of the player's stalker, or null when unbound (bribe gating: STALK/COVER accept bribes). */
	public static Phases.Phase getPhase(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		return state == null ? null : state.phase;
	}

	/** Toggles the per-player particle debug view (/stalker debug). */
	public static void toggleDebug(ServerPlayer player) {
		DEBUG_PLAYERS.put(player.getUUID(), !DEBUG_PLAYERS.getOrDefault(player.getUUID(), false));
	}

	/** True when this player's debug particle view is on. */
	public static boolean isDebugging(ServerPlayer player) {
		return DEBUG_PLAYERS.getOrDefault(player.getUUID(), false);
	}

	/**
	 * Unbind + blacklist the player's bound villager — strike variant A (snatch)
	 * and bribe-accept semantics (plan §1: it got what it wanted, 10-minute
	 * immunity). No-op when unbound.
	 */
	public static void blacklistAfterSuccess(ServerPlayer player) {
		unbind(player, true);
	}

	/** Applies the 5-second post-stun strike cooldown; called by Strikes (plan §4). */
	public static void noteStrikeCooldown(ServerPlayer player) {
		State state = STATES.get(player.getUUID());
		if (state != null) {
			state.strikeCooldown = STRIKE_COOLDOWN_AFTER_STUN;
		}
	}

	/** Called by the removal hook when a bound villager dies or is removed (plan §1). */
	public static void onVillagerRemoved(ServerLevel level, Villager villager) {
		for (Map.Entry<UUID, State> entry : STATES.entrySet()) {
			if (!entry.getValue().villagerId.equals(villager.getUUID())) {
				continue;
			}
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
			STATES.remove(entry.getKey());
			if (player != null) {
				Attachments.unbindPlayer(player);
				Attachments.blacklist(level, player, villager.getUUID(), StalkerConfig.get().blacklistTicks());
			}
			return;
		}
	}

	// ----------------------------------------------------------------- misc

	private static void dropBinding(ServerLevel level, ServerPlayer player, State state, boolean blacklist, boolean villagerSide) {
		STATES.remove(player.getUUID());
		if (blacklist) {
			Attachments.blacklist(level, player, state.villagerId, StalkerConfig.get().blacklistTicks());
		}
		Attachments.unbindPlayer(player);
		Strikes.clear(player);
		if (villagerSide && level.getEntityInAnyDimension(state.villagerId) instanceof Villager villager) {
			Attachments.unbindVillager(villager);
			StalkerBrain.release(villager);
		}
	}

	private static void dropVillagerSide(ServerPlayer player) {
		State state = STATES.remove(player.getUUID());
		Attachments.unbindPlayer(player);
		Strikes.clear(player);
		if (state != null && player.level() instanceof ServerLevel level
			&& level.getEntityInAnyDimension(state.villagerId) instanceof Villager villager) {
			Attachments.unbindVillager(villager);
			// Hand it back to vanilla: clear the move memories/pose and drop STALKING, or it stands
			// inert (the memories are serialised into the brain NBT and would survive a reload).
			StalkerBrain.release(villager);
		}
	}

	private static void dropPlayerSide(ServerPlayer player) {
		STATES.remove(player.getUUID());
		Attachments.unbindPlayer(player);
		Strikes.clear(player);
	}

	private static String professionName(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
			.map(key -> key.identifier().toString())
			.orElse("unknown");
	}

	/** Per-player manager state; nothing here needs the villager to stay loaded. */
	private static final class State {
		final UUID villagerId;
		Phases.Phase phase = Phases.Phase.STALK;
		int spottedTicks;
		int unspottedTicks;
		int peekCooldown;
		int peekTicks;
		boolean peekSide;
		int coverSearchCooldown;
		BlockPos coverPos;
		int strikeCooldown;
		int stunTicks;
		int chaseTicks;
		int relocateTicks;
		int unloadedTicks;
		int mumbleCooldown;
		int footstepCooldown;
		int ticksSinceIssue;
		Vec3 lastTarget;
		boolean wantsToMove;
		int stuckSampleTicks;
		int stuckCycles;
		Vec3 lastSampledPos;
		// Pretend-normal mode (plan §3 fallback): spotted with no usable cover, so act like an
		// ordinary villager until the player looks away instead of freezing in plain sight.
		int normalLookTicks;
		int normalWanderTicks;
		Vec3 normalWanderTarget;
		// Ticks spent sprinting at the current cover point; too long means it is unreachable.
		int dashTicks;
		// Committed-advance counter (hysteresis on the unseen gate, see APPROACH_COMMIT_TICKS).
		int advanceTicks;
		// The committed approach route: hops between cover, nearest first (ApproachPlanner).
		final ArrayDeque<Vec3> plan = new ArrayDeque<>();
		// Player position when the plan was built; the plan is rebuilt when they stray from it.
		Vec3 planAnchor;
		// Ticks since the plan was built; long-lived plans are re-checked even if the player stays.
		int planAge;

		State(UUID villagerId) {
			this.villagerId = villagerId;
		}

		void tick(ServerLevel level, ServerPlayer player, StalkerConfig c) {
			if (coverSearchCooldown > 0) {
				coverSearchCooldown--;
			}
		}

		void resetMovement() {
			lastTarget = null;
			wantsToMove = false;
			ticksSinceIssue = 0;
			normalWanderTicks = 0;
			normalWanderTarget = null;
			normalLookTicks = 0;
			dashTicks = 0;
			advanceTicks = 0;
			plan.clear();
			planAnchor = null;
			planAge = 0;
		}
	}
}
