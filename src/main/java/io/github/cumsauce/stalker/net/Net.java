package io.github.cumsauce.stalker.net;

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

	/** Registers the S2C strike-tell payload type. Call from the main entrypoint. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(StrikeTellPayload.TYPE, StrikeTellPayload.CODEC);
	}

	/**
	 * Sends the strike tell to one player: the villager's head position and the
	 * camera-snap duration. Safe on vanilla clients (Fabric drops unknown payloads).
	 */
	public static void strikeTell(ServerPlayer player, Villager villager, int durationTicks) {
		ServerPlayNetworking.send(player, new StrikeTellPayload(
				villager.getX(), villager.getEyeY(), villager.getZ(), durationTicks));
	}
}
