package io.github.cumsauce.stalker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import io.github.cumsauce.stalker.net.StrikeTellPayload;

/**
 * Client entrypoint: receives the strike-tell payload and snaps the camera toward
 * the villager's head over the payload duration with an eased lerp. Purely
 * cosmetic — on a vanilla client (no packet) the tell degrades to sound alone.
 */
public class StalkerClient implements ClientModInitializer {

	/** Active camera snap, or null. */
	private static Snap snap;

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
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (snap != null) {
				tickSnap(client.player);
			}
		});
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
