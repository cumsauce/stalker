package io.github.cumsauce.stalker;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import io.github.cumsauce.stalker.net.StrikeTellPayload;
import io.github.cumsauce.stalker.net.VoiceCuePayload;
import io.github.cumsauce.stalker.sound.StalkerFoley;
import io.github.cumsauce.stalker.sound.VoiceCues;

/**
 * Client entrypoint: receives the strike-tell payload and snaps the camera toward
 * the villager's head over the payload duration with an eased lerp. Purely
 * cosmetic — on a vanilla client (no packet) the tell degrades to sound alone.
 */
public class StalkerClient implements ClientModInitializer {

	/** Active camera snap, or null. */
	private static Snap snap;

	/**
	 * Clamp windows for pitch and volume taken off the wire. Foley's own models only ever produce
	 * pitches near 1.0 and unity gain, so anything far outside these is a buggy or hostile server,
	 * not a real cue; clamping keeps it audible-but-sane instead of silent or deafening.
	 */
	private static final float MIN_REMOTE_PITCH = 0.25f;
	private static final float MAX_REMOTE_PITCH = 4.0f;
	private static final float MIN_REMOTE_VOLUME = 0.0f;
	private static final float MAX_REMOTE_VOLUME = 4.0f;

	/**
	 * Queued follow-up beats of a laugh cadence, in arrival order. The server sends one packet per
	 * laugh (beat 0); the client spaces the rest so a chuckle reads as a rhythm rather than a
	 * stack. Empty on a vanilla client, which never receives the packet at all.
	 */
	private static final List<PendingBeat> PENDING_BEATS = new ArrayList<>();

	/**
	 * One not-yet-played laugh beat: its position, the pitch this rung should sound at, and how
	 * many ticks remain before it fires. Immutable — the queue is rebuilt each tick.
	 */
	private record PendingBeat(VoiceCues.VoiceCue cue, float pitch, float volume, double x, double y, double z, int ticksLeft) {
		PendingBeat tick() {
			return new PendingBeat(cue, pitch, volume, x, y, z, ticksLeft - 1);
		}
	}

	/** One camera snap: target position, remaining ticks, duration and the rotation it started from. */
	private static final class Snap {
		final Vec3 target;
		final int duration;
		int ticksLeft;
		final float startYaw;
		final float startPitch;

		Snap(Vec3 target, int duration, float startYaw, float startPitch) {
			this.target = target;
			this.duration = duration;
			this.ticksLeft = duration;
			this.startYaw = startYaw;
			this.startPitch = startPitch;
		}
	}

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(StrikeTellPayload.TYPE, (payload, context) ->
				context.client().execute(() -> beginSnap(payload, context.player())));
		ClientPlayNetworking.registerGlobalReceiver(VoiceCuePayload.TYPE, (payload, context) ->
				context.client().execute(() -> handleVoiceCue(payload)));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (snap != null) {
				tickSnap(client.player);
			}
			tickVoiceBeats();
		});
	}

	/**
	 * Plays one voice cue, and for a laugh schedules the rest of its cadence. The mod-present gate
	 * runs before anything else so Foley's classes are never resolved on a client that lacks it;
	 * {@link StalkerFoley#play} then re-checks Foley's own availability. Degenerate packets (NaN
	 * coordinates, an unknown cue name) are dropped rather than trusted — the payload is remote
	 * input.
	 */
	private static void handleVoiceCue(VoiceCuePayload payload) {
		if (!FabricLoader.getInstance().isModLoaded("foley")) {
			return;
		}
		if (!Double.isFinite(payload.x()) || !Double.isFinite(payload.y()) || !Double.isFinite(payload.z())
				|| !Float.isFinite(payload.pitch()) || !Float.isFinite(payload.volume())) {
			return;
		}
		VoiceCues.VoiceCue cue;
		try {
			cue = VoiceCues.VoiceCue.valueOf(payload.cue());
		} catch (IllegalArgumentException unknown) {
			return;
		}
		// The payload is remote input: finite-but-absurd magnitudes pass the guard above and would
		// reach Foley's mixer unmuted and untuned, so clamp to windows the layer actually produces.
		float pitch = Mth.clamp(payload.pitch(), MIN_REMOTE_PITCH, MAX_REMOTE_PITCH);
		float volume = Mth.clamp(payload.volume(), MIN_REMOTE_VOLUME, MAX_REMOTE_VOLUME);
		StalkerFoley.play(cue, payload.x(), payload.y(), payload.z(), pitch, volume);
		if (cue != VoiceCues.VoiceCue.LAUGH) {
			return;
		}
		// Beat 0 just played; queue the remaining rungs, each one pitch-step lower and one gap later.
		for (int beat = 1; beat < VoiceCues.LAUGH_CADENCE; beat++) {
			PENDING_BEATS.add(new PendingBeat(cue, VoiceCues.laughBeatPitch(pitch, beat),
					volume, payload.x(), payload.y(), payload.z(),
					beat * VoiceCues.LAUGH_BEAT_GAP_TICKS));
		}
	}

	/**
	 * Fires queued laugh beats whose countdown has run out, in arrival order.
	 *
	 * <p>Removal is by backwards index rather than by value: a due beat is the <em>ticked</em>
	 * copy, so it no longer equals the stored original, and a value-based removal would match
	 * nothing — leaving the beat to come due, play, and come due again every tick forever. Walking
	 * backwards keeps the removal from skipping an element.
	 */
	private static void tickVoiceBeats() {
		for (int i = PENDING_BEATS.size() - 1; i >= 0; i--) {
			PendingBeat beat = PENDING_BEATS.get(i).tick();
			if (beat.ticksLeft() <= 0) {
				PENDING_BEATS.remove(i);
				StalkerFoley.play(beat.cue(), beat.x(), beat.y(), beat.z(), beat.pitch(), beat.volume());
			} else {
				PENDING_BEATS.set(i, beat);
			}
		}
	}

	/** Validates the payload and starts a snap; anything missing or degenerate degrades silently. */
	private static void beginSnap(StrikeTellPayload payload, LocalPlayer player) {
		if (player == null || snap != null) {
			return;
		}
		if (payload.durationTicks() <= 0
				|| !Double.isFinite(payload.villagerX())
				|| !Double.isFinite(payload.villagerY())
				|| !Double.isFinite(payload.villagerZ())) {
			return;
		}
		snap = new Snap(new Vec3(payload.villagerX(), payload.villagerY(), payload.villagerZ()),
				payload.durationTicks(), player.getYRot(), player.getXRot());
	}

	/** One eased-lerp step toward the villager's head; the final tick snaps exactly. */
	private static void tickSnap(LocalPlayer player) {
		if (player == null) {
			snap = null;
			return;
		}
		Snap s = snap;
		Vec3 eye = player.getEyePosition();
		Vec3 delta = s.target.subtract(eye);

		float targetYaw = (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0f;
		float targetPitch = (float) -(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) * (180.0 / Math.PI));

		int done = s.duration - s.ticksLeft + 1;
		float t = Mth.clamp((float) done / s.duration, 0.0f, 1.0f);
		float eased = t * t * (3.0f - 2.0f * t); // smoothstep

		float yaw = s.startYaw + Mth.wrapDegrees(targetYaw - s.startYaw) * eased;
		float pitch = s.startPitch + (targetPitch - s.startPitch) * eased;
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;

		s.ticksLeft--;
		if (s.ticksLeft <= 0) {
			snap = null;
		}
	}
}
