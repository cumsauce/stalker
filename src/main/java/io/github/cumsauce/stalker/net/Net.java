package io.github.cumsauce.stalker.net;

import io.github.cumsauce.stalker.sound.VoiceCues;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Networking bootstrap and send helpers. {@link #register()} must be called once
 * during mod init (before clients handshake) — payload registration is not valid
 * mid-play.
 */
public final class Net {

	private Net() {
	}

	/** Registers the S2C strike-tell and voice-cue payload types. Call from the main entrypoint. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(StrikeTellPayload.TYPE, StrikeTellPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VoiceCuePayload.TYPE, VoiceCuePayload.CODEC);
	}

	/**
	 * Sends the strike tell to one player: the villager's head position and the
	 * camera-snap duration. Safe on vanilla clients (Fabric drops unknown payloads).
	 */
	public static void strikeTell(ServerPlayer player, Villager villager, int durationTicks) {
		ServerPlayNetworking.send(player, new StrikeTellPayload(
				villager.getX(), villager.getEyeY(), villager.getZ(), durationTicks));
	}

	/**
	 * Sends one Foley voice cue to one player: its play position, the cue by enum name, and the
	 * beat-0 pitch/volume (the client derives any laugh cadence beats from that pitch). Safe on
	 * vanilla clients, and a client without Foley declines to play — layering over the vanilla
	 * cues, never replacing them.
	 */
	public static void voiceCue(ServerPlayer player, double x, double y, double z,
			VoiceCues.VoiceCue cue, float pitch, float volume) {
		ServerPlayNetworking.send(player, new VoiceCuePayload(x, y, z, cue.name(), pitch, volume));
	}
}
