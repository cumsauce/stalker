package io.github.cumsauce.stalker.strike;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.net.Net;
import io.github.cumsauce.stalker.sound.Cues;
import io.github.cumsauce.stalker.sound.StalkerSounds;
import io.github.cumsauce.stalker.sound.VoiceCues;
import io.github.cumsauce.stalker.world.Phases;
import io.github.cumsauce.stalker.world.StalkerBrain;
import io.github.cumsauce.stalker.world.StalkerManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The strike, the chase and the stun (PLAN.md §4).
 *
 * <p>{@link #strike} performs the tell and rolls variant A (snatch) vs variant B
 * (chase). The manager calls {@link #tickChase} every server tick while the phase
 * is CHASE and {@link #tickStun} while it is STUN; per-player timers live here in
 * static state keyed by player UUID (the manager owns the binding itself).</p>
 */
public final class Strikes {

	/** Camera-snap tell length in client ticks. No tuning-table key — fixed by the plan's §4 tell spec. */
	private static final int TELL_SNAP_TICKS = 12;
	/** DARKNESS length for the snatch variant (§4, no tuning key). */
	private static final int SNATCH_DARKNESS_TICKS = 60;
	/** NAUSEA length during the stun (§4, no tuning key). */
	private static final int STUN_NAUSEA_TICKS = 100;
	/** How often the dazed villager picks a new slow look target while stunned. */
	private static final int STUN_LOOK_INTERVAL_TICKS = 20;

	private static final Map<UUID, ChaseState> CHASE = new HashMap<>();
	private static final Map<UUID, StunState> STUN = new HashMap<>();

	private Strikes() {
	}

	/** Per-player chase progress: hits landed and ticks elapsed since the strike. */
	private static final class ChaseState {
		int hits;
		int ticks;
		int ticksSinceHit;
		/** Last behind-the-player target issued to {@code StalkerBrain.setTarget}; used to throttle
		 * re-issuing so we don't replace the brain's STALKING_TARGET Vec3 (and allocate one) every
		 * tick when the player has barely moved. The brain's MoveTargetTask only re-paths past its
		 * own distance threshold, so once we cap re-issues here the A* doesn't run more than it
		 * already would. */
		Vec3 lastTarget;
	}

	/** Distance in blocks the player must move before the chase re-issues its move target. */
	private static final double CHASE_REPATH_DISTANCE = 1.0;

	/** Per-player stun progress. */
	private static final class StunState {
		int ticks;
		int ticksSinceLook;
	}

	/** Drops any chase/stun timers for a player (manager may call this on unbind). */
	public static void clear(ServerPlayer player) {
		CHASE.remove(player.getUUID());
		STUN.remove(player.getUUID());
	}

	/**
	 * Performs the strike from an established trigger (manager checks range/facing/phase):
	 * the tell first (face + sting + camera-snap packet), then variant A — snatch —
	 * with probability {@code teleportChance}, otherwise variant B — the chase.
	 */
	public static void strike(Villager villager, ServerPlayer player) {
		// The tell: face the victim, sting, camera snap.
		villager.getLookControl().setLookAt(player);
		Cues.strikeTell(villager);
		Net.strikeTell(player, villager, TELL_SNAP_TICKS);

		// Foley layer: a villager shriek on top of the sting. A scream is an event tell — it comes
		// from the villager's true position, not the lie layer. Gated server-side on the config so
		// the client is never sent a cue it has to discard; the client additionally gates on Foley
		// being installed, so a vanilla client sees nothing change.
		if (VoiceCues.shouldScream(StalkerConfig.get().foleyVoices())) {
			Net.voiceCue(player, villager.getX(), villager.getY(), villager.getZ(),
					VoiceCues.VoiceCue.SCREAM, VoiceCues.screamPitch(VoiceCues.newDraw()), VoiceCues.SCREAM_VOLUME);
		}

		if (StalkerConfig.get().teleportChance() > ThreadLocalRandom.current().nextDouble()) {
			snatch(villager, player);
		} else {
			chase(villager, player);
		}
	}

	/** Variant A (15 %): darkness, the player is teleported ~100 blocks, binding ends satisfied. */
	private static void snatch(Villager villager, ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		StalkerConfig cfg = StalkerConfig.get();

		player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, SNATCH_DARKNESS_TICKS));

		// 100 ± 16 blocks horizontal, then land on the heightmap top, 0–teleportMaxLift up.
		// getHeight returns the world bottom for an unloaded column, which would drop the player
		// into bedrock or the void, so retry a few angles until the column is loaded and above
		// world-bottom; the last attempt is used as is if none qualifies.
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		double x = 0;
		double z = 0;
		int surfaceY = level.getMinY();
		double y = surfaceY + 1;
		for (int attempt = 0; attempt < 8; attempt++) {
			double angle = rng.nextDouble() * Math.PI * 2.0;
			double radius = cfg.teleportDistance() - 16.0 + rng.nextDouble() * 32.0;
			x = player.getX() + Math.cos(angle) * radius;
			z = player.getZ() + Math.sin(angle) * radius;
			surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
			y = surfaceY + (int) (rng.nextDouble() * (cfg.teleportMaxLift() + 1.0));
			if (level.hasChunkAt(BlockPos.containing(x, y, z)) && surfaceY > level.getMinY()) {
				break;
			}
		}

		// If no attempt found a loaded, above-bottom column, do not fling the player into the void:
		// keep them where they are.
		if (!level.hasChunkAt(BlockPos.containing(x, y, z)) || surfaceY <= level.getMinY()) {
			x = player.getX();
			y = player.getY();
			z = player.getZ();
		}

		Vec3 from = player.position();
		// Absolute position, keep the player's current rotation, no camera reset.
		player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), player.getXRot(), false);
		Cues.snatchTeleport(level, from, new Vec3(x, y, z));

		// It got what it wanted — the pair is done for 10 minutes. One call: unbind() alone would
		// clear the state and attachment the blacklist needs to read the villager UUID from.
		StalkerManager.blacklistAfterSuccess(player);
	}

	/** Variant B (85 %): blindness is the 9-second timer, poison starts climbing, phase becomes CHASE. */
	private static void chase(Villager villager, ServerPlayer player) {
		StalkerConfig cfg = StalkerConfig.get();
		player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, cfg.chaseDurationTicks()));
		player.addEffect(new MobEffectInstance(MobEffects.POISON, cfg.poisonDurationTicks(), 0));
		// The extended fart lands with the blindness: the moment the player is blinded and the chase
		// begins. Gated on the same foleyVoices option as the scream/laugh, so it is opt-in with the
		// rest of the voice layer even though the asset itself needs no Foley.
		if (cfg.foleyVoices()) {
			StalkerSounds.chaseFart(villager);
		}
		CHASE.put(player.getUUID(), new ChaseState());
		StalkerManager.setPhase(villager, Phases.Phase.CHASE);
	}

	/**
	 * Chase tick, driven by the manager while the phase is CHASE: keep out of LOS,
	 * close on the point behind the player, hit on a cooldown, heartbeat by ear.
	 */
	public static void tickChase(Villager villager, ServerPlayer player) {
		StalkerConfig cfg = StalkerConfig.get();
		ChaseState state = CHASE.computeIfAbsent(player.getUUID(), id -> new ChaseState());

		// Prefer the point 1.5 blocks behind the player (position minus look). Re-issuing the brain
		// memory every tick allocates a fresh Vec3 even when the player has barely moved; the brain's
		// MoveTargetTask only re-paths past its own distance threshold, so throttle the wrapper too.
		Vec3 behind = player.position().subtract(player.getLookAngle().scale(1.5));
		boolean reissue = state.lastTarget == null
			|| state.lastTarget.distanceToSqr(behind) > CHASE_REPATH_DISTANCE * CHASE_REPATH_DISTANCE;
		if (reissue) {
			StalkerBrain.setTarget(villager, behind, (float) cfg.chaseSpeed());
			state.lastTarget = behind;
		}
		StalkerBrain.lookAt(villager, player);

		state.ticks++;
		state.ticksSinceHit++;

		// Melee hit on cooldown while in range; damage and poison amplifier climb with N.
		if (state.ticksSinceHit >= cfg.hitCooldownTicks()
				&& player.distanceToSqr(villager) <= cfg.meleeRange() * cfg.meleeRange()) {
			state.ticksSinceHit = 0;
			state.hits++;
			hit(villager, player, state.hits);
		}

		if (state.ticks % Math.max(1, cfg.chaseHeartbeatIntervalTicks()) == 0) {
			Cues.chaseHeartbeat(villager);
		}

		// The CHASE->STUN edge is owned by StalkerManager.tickChase (it watches the blindness
		// effect, which is the timer). Doing it here too played the stun cue twice, so this only
		// drops the bookkeeping; the manager's setPhase arrives as the phase change.
		if (state.ticks >= cfg.chaseDurationTicks()) {
			CHASE.remove(player.getUUID());
		}
	}

	/** Hit N: {@code hitDamage + hitDamage × amp(N)}, poison reapplied at {@code min(N−1, cap)}. */
	private static void hit(Villager villager, ServerPlayer player, int hitNumber) {
		ServerLevel level = (ServerLevel) player.level();
		StalkerConfig cfg = StalkerConfig.get();
		int amp = Math.min(hitNumber - 1, cfg.poisonAmplifierCap());

		float damage = (float) (cfg.hitDamage() + cfg.hitDamage() * amp);
		player.hurtServer(level, level.damageSources().mobAttack(villager), damage);
		player.addEffect(new MobEffectInstance(MobEffects.POISON, cfg.poisonDurationTicks(), amp));

		// Melee shriek — the same true-tell scream as the strike, pitched from a fresh draw so
		// successive hits in one chase never sound identical.
		if (VoiceCues.shouldScream(cfg.foleyVoices())) {
			Net.voiceCue(player, villager.getX(), villager.getY(), villager.getZ(),
					VoiceCues.VoiceCue.SCREAM, VoiceCues.screamPitch(VoiceCues.newDraw()), VoiceCues.SCREAM_VOLUME);
		}
	}

	/** Starts the stun: NAUSEA, freeze, stun cue. Called by the manager's CHASE->STUN edge. */
	public static void stun(Villager villager, ServerPlayer player) {
		STUN.put(player.getUUID(), new StunState());
		player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, STUN_NAUSEA_TICKS));
		villager.getNavigation().stop();
		StalkerBrain.setTarget(villager, null, 0.0f);
		Cues.stun(villager);
	}

	/**
	 * Stun tick, driven by the manager while the phase is STUN: no movement, head
	 * lolling; the player escaping past {@code stunEscapeDistance} ends the pair,
	 * the timeout returns it to STALK under a strike cooldown.
	 */
	public static void tickStun(Villager villager, ServerPlayer player) {
		StalkerConfig cfg = StalkerConfig.get();
		StunState state = STUN.computeIfAbsent(player.getUUID(), id -> new StunState());

		// Stay frozen; keep the navigation stopped in case something else started it.
		StalkerBrain.setTarget(villager, null, 0.0f);

		if (player.distanceToSqr(villager) > cfg.stunEscapeDistance() * cfg.stunEscapeDistance()) {
			STUN.remove(player.getUUID());
			StalkerManager.blacklistAfterSuccess(player);
			return;
		}

		state.ticks++;
		state.ticksSinceLook++;
		if (state.ticksSinceLook >= STUN_LOOK_INTERVAL_TICKS) {
			state.ticksSinceLook = 0;
			// Slow random look targets — head lolling.
			ThreadLocalRandom rng = ThreadLocalRandom.current();
			double angle = rng.nextDouble() * Math.PI * 2.0;
			double dist = 3.0 + rng.nextDouble() * 5.0;
			Vec3 look = villager.position().add(Math.cos(angle) * dist, 0.5 + rng.nextDouble() * 1.5, Math.sin(angle) * dist);
			villager.getLookControl().setLookAt(look.x, look.y, look.z, 5.0f, 5.0f);
		}

		if (state.ticks >= cfg.stunDurationTicks()) {
			STUN.remove(player.getUUID());
			StalkerManager.setPhase(villager, Phases.Phase.STALK);
			StalkerManager.noteStrikeCooldown(player);
		}
	}
}
