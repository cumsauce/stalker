package io.github.cumsauce.stalker.sound;

import io.github.cumsauce.foley.api.Foley;
import io.github.cumsauce.foley.api.FoleyApi;
import io.github.cumsauce.foley.api.SoundModel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.sounds.SoundSource;

/**
 * Stalker's client-side bridge to Foley — the ONLY class (besides {@link FoleyVoicesEntrypoint})
 * that references {@code io.github.cumsauce.foley.api.*}, so the API types are linked exclusively
 * on the client, and only after the mod-present gate. Foley's api jar is compile-only: nothing
 * bundles Foley, and shared/server code never names this class — it sends {@code VoiceCuePayload}s
 * and lets the client decide whether there is anything to play.
 *
 * <p>Registration happens exactly once, from the {@code foley} entrypoint; playback guards
 * {@link #ready()} ({@code isModLoaded("foley")}, then {@code Foley.available()}) so a vanilla
 * client simply never plays a cue.
 */
@Environment(EnvType.CLIENT)
public final class StalkerFoley {

	private StalkerFoley() {
	}

	/**
	 * Registers both stalker voice sounds. Legal only inside {@code onFoleyReady} — the register
	 * window closes when the last addon entrypoint returns.
	 */
	public static void register(FoleyApi api) {
		api.sounds().register(VoiceCues.SCREAM_SOUND,
				new SoundModel.Voice(VoiceCues.SCREAM_MOB, VoiceCues.SCREAM_CALL),
				VoiceCues.SCREAM_VARIANTS);
		api.sounds().register(VoiceCues.LAUGH_SOUND,
				new SoundModel.Voice(VoiceCues.LAUGH_MOB, VoiceCues.LAUGH_CALL),
				VoiceCues.LAUGH_VARIANTS);
	}

	/**
	 * Whether Foley can play right now: the mod-present gate first (so Foley's classes are never
	 * resolved while absent), then Foley's own availability. This is the entry every play path
	 * checks.
	 */
	public static boolean ready() {
		return FabricLoader.getInstance().isModLoaded("foley") && Foley.available();
	}

	/**
	 * Plays one cue at a position: no-op unless {@link #ready()}. The scream runs through the
	 * hostile channel (an event the player reacts to), the laugh through the neutral one (a voice
	 * in the dark, like the other ambient dread cues).
	 */
	public static void play(VoiceCues.VoiceCue cue, double x, double y, double z, float pitch, float volume) {
		if (!ready()) {
			return;
		}
		Foley.api().sounds().play(VoiceCues.soundFor(cue), x, y, z,
				cue == VoiceCues.VoiceCue.SCREAM ? SoundSource.HOSTILE : SoundSource.NEUTRAL,
				volume, pitch);
	}
}
