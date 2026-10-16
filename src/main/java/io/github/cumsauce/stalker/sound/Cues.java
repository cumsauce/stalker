package io.github.cumsauce.stalker.sound;

import java.util.concurrent.ThreadLocalRandom;

import io.github.cumsauce.stalker.config.StalkerConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * All diegetic cues, per PLAN.md §5. Every sound is positional
 * ({@code level.playSound(null, x, y, z, ...)} with {@code null} except so no
 * player is excluded); volumes and pitches follow the §5 table. No custom assets.
 */
public final class Cues {

	private Cues() {
	}

	/** The volumes below are the plan §5 defaults, read live so /stalker reload takes effect. */
	private static StalkerConfig cfg() {
		return StalkerConfig.get();
	}

	private static void play(Level level, Vec3 at, SoundEvent sound, SoundSource source, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch);
	}

	/** Stalking mumble: {@code entity.villager.ambient}, pitch 0.5–0.6, volume 0.25. */
	public static void stalkingMumble(Villager villager) {
		float pitch = 0.5f + ThreadLocalRandom.current().nextFloat() * 0.1f;
		play(villager.level(), villager.position(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, (float) cfg().mumbleVolume(), pitch);
	}

	/** Stalking footstep: {@code block.grass.step}, pitch 1.0, volume 0.6. */
	public static void footstep(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, (float) cfg().footstepVolume(), 1.0f);
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
	 * is right there without giving away exactly where.
	 */
	public static void closeHeartbeat(Villager villager, double baseVolume) {
		play(villager.level(), villager.position(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, (float) (baseVolume * 0.5), 1.0f);
	}

	/**
	 * Ambush windup: the patience strike's tell, played once when it stops waiting and decides to act —
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

	/** Chase heartbeat: {@code entity.warden.heartbeat}, pitch 1.0, volume 0.8. */
	public static void chaseHeartbeat(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, (float) cfg().chaseHeartbeatVolume(), 1.0f);
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
}
