package io.github.cumsauce.stalker.sound;

import io.github.cumsauce.stalker.Stalker;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Stalker's own sound assets. Distinct from {@link Cues}, which only ever plays <em>vanilla</em>
 * sound events — this class owns the events backed by the mod's own bundled {@code .ogg} files
 * (currently just the post-blindness fart).
 *
 * <p>Registration happens once from {@link Stalker#onInitialize}: {@code BuiltInRegistries} is
 * frozen by Fabric immediately after mod init, so a {@code Registry.register} deferred to first
 * playback would throw.
 */
public final class StalkerSounds {

	/**
	 * The long fart played as the chase begins. Gated by the caller on the {@code foleyVoices}
	 * config option, so it ships dark unless the voice layer is opted into. The asset itself is a
	 * bundled vanilla {@link SoundEvent} and needs neither Foley nor its config to play.
	 */
	public static final SoundEvent VOICE_FART = register("voice_fart");

	/** Bounds of the per-play pitch jitter, matching the ambient cues' habit of never sounding identical. */
	private static final float FART_PITCH_MIN = 0.9f;
	private static final float FART_PITCH_MAX = 1.1f;

	private StalkerSounds() {
	}

	private static SoundEvent register(String name) {
		Identifier id = Identifier.fromNamespaceAndPath(Stalker.MOD_ID, name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	/**
	 * Touched from {@link Stalker#onInitialize} so this class's static initializer (the
	 * {@link Registry#register} call above) runs before Fabric freezes the registry. The body is
	 * intentionally empty — the registration is the field initializer, this only forces class load.
	 */
	public static void bootstrap() {
		// no-op: see javadoc
	}

	/**
	 * Plays the extended fart at the villager's position with a small pitch jitter, so repeat
	 * encounters do not sound byte-identical. Punchy and near-full gain: this is an event the
	 * player is meant to notice, not an ambient dread cue from the lie layer.
	 *
	 * <p>The jitter draws from the thread-local RNG rather than {@code villager.getRandom()}: the
	 * villager's own stream drives the vanilla cue cadences, and the voice layer's contract
	 * (see {@link VoiceCues#newDraw()}) is that arming it must not shift those.
	 */
	public static void chaseFart(Villager villager) {
		chaseFart(villager.level(), villager.position());
	}

	/**
	 * Position-based variant of {@link #chaseFart(Villager)}, for callers with no villager to
	 * anchor on (the {@code /stalker fart} dev command plays it at the player).
	 */
	public static void chaseFart(Level level, Vec3 at) {
		float pitch = FART_PITCH_MIN
				+ VoiceCues.newDraw().nextFloat() * (FART_PITCH_MAX - FART_PITCH_MIN);
		// Positional, no excluded player (null source entity), on the hostile channel like the
		// other strike tells — it plays where the caller says, not from the lie layer's stale origin.
		level.playSound(null, at.x, at.y, at.z, VOICE_FART, SoundSource.HOSTILE, 0.9f, pitch);
	}
}
