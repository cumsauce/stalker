package io.github.cumsauce.stalker.sound;

import java.util.concurrent.ThreadLocalRandom;

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

	private static void play(Level level, Vec3 at, SoundEvent sound, SoundSource source, float volume, float pitch) {
		level.playSound(null, at.x, at.y, at.z, sound, source, volume, pitch);
	}

	/** Stalking mumble: {@code entity.villager.ambient}, pitch 0.5–0.6, volume 0.25. */
	public static void stalkingMumble(Villager villager) {
		float pitch = 0.5f + ThreadLocalRandom.current().nextFloat() * 0.1f;
		play(villager.level(), villager.position(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.25f, pitch);
	}

	/** Stalking footstep: {@code block.grass.step}, pitch 1.0, volume 0.6. */
	public static void footstep(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, 0.6f, 1.0f);
	}

	/** Spotted dash: {@code block.grass.step} ×2, pitch 1.2, volume 0.7. */
	public static void dashForCover(Villager villager) {
		Vec3 at = villager.position();
		play(villager.level(), at, SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, 0.7f, 1.2f);
		play(villager.level(), at, SoundEvents.GRASS_STEP, SoundSource.NEUTRAL, 0.7f, 1.2f);
	}

	/** Chase heartbeat: {@code entity.warden.heartbeat}, pitch 1.0, volume 0.8. */
	public static void chaseHeartbeat(Villager villager) {
		play(villager.level(), villager.position(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 0.8f, 1.0f);
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
