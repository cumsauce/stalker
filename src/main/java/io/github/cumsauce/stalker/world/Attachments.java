package io.github.cumsauce.stalker.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

/**
 * Fabric Data Attachment types for stalker bindings, plus static accessors.
 *
 * <p>Storage layout (plan §1, §7):
 * <ul>
 *   <li>{@link #STALKED_BY} on the villager: the UUID of the player it stalks.
 *       Persistent, {@code copyOnDeath = false} (a dead stalker unbinds — the
 *       default, so {@code copyOnDeath()} is deliberately not called).</li>
 *   <li>{@link #STALKING} on the player: the UUID of its villager. Persistent
 *       and survives death ("it liked that one").</li>
 *   <li>{@link #BLACKLIST} on the player: map villagerUUID → expiry game time
 *       of the player's dimension. Persistent and survives death. Expiry is an
 *       absolute {@code level.getGameTime()} value, which persists with the
 *       level, so comparisons stay valid across restarts.</li>
 * </ul>
 */
public final class Attachments {

	/** Villager-side binding: UUID of the stalked player (dies with the villager). */
	public static final AttachmentType<UUID> STALKED_BY = AttachmentRegistry
		.<UUID>builder()
		.persistent(UUIDUtil.CODEC)
		.buildAndRegister(Identifier.fromNamespaceAndPath("stalker", "stalked_by"));

	/** Player-side binding: UUID of its villager (survives player death). */
	public static final AttachmentType<UUID> STALKING = AttachmentRegistry
		.<UUID>builder()
		.persistent(UUIDUtil.CODEC)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath("stalker", "stalking"));

	/** Player-side blacklist history: villagerUUID → expiry game time. */
	public static final AttachmentType<Map<UUID, Long>> BLACKLIST = AttachmentRegistry
		.<Map<UUID, Long>>builder()
		.persistent(Codec.unboundedMap(UUIDUtil.CODEC, Codec.LONG))
		.initializer(HashMap::new)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath("stalker", "blacklist"));

	private Attachments() {
	}

	/** True if the villager is bound to any player. */
	public static boolean isBound(Villager villager) {
		return villager.hasAttached(STALKED_BY);
	}

	/** The UUID of the player this villager stalks, if bound. */
	public static Optional<UUID> boundPlayer(Villager villager) {
		return Optional.ofNullable(villager.getAttached(STALKED_BY));
	}

	/** The UUID of the villager stalking this player, if any. */
	public static Optional<UUID> boundVillager(ServerPlayer player) {
		return Optional.ofNullable(player.getAttached(STALKING));
	}

	/** Writes the binding on both sides. Call only after eligibility checks. */
	public static void bind(Villager villager, ServerPlayer player) {
		villager.setAttached(STALKED_BY, player.getUUID());
		player.setAttached(STALKING, villager.getUUID());
	}

	/** Clears the binding on both sides; safe if already half-cleared. */
	public static void unbind(Villager villager, ServerPlayer player) {
		villager.removeAttached(STALKED_BY);
		player.removeAttached(STALKING);
	}

	/** Clears the villager-side binding only (used when the player is gone). */
	public static void unbindVillager(Villager villager) {
		villager.removeAttached(STALKED_BY);
	}

	/** Clears the player-side binding only (used when the villager is gone). */
	public static void unbindPlayer(ServerPlayer player) {
		player.removeAttached(STALKING);
	}

	/** True if this villager may not stalk this player yet (blacklist active). */
	public static boolean isBlacklisted(ServerLevel level, ServerPlayer player, UUID villagerId) {
		Long expiry = player.getAttached(BLACKLIST).get(villagerId);
		return expiry != null && expiry > level.getGameTime();
	}

	/** Blacklists the pair for the given duration from now. */
	public static void blacklist(ServerLevel level, ServerPlayer player, UUID villagerId, long durationTicks) {
		player.modifyAttached(BLACKLIST, map -> {
			map.put(villagerId, level.getGameTime() + durationTicks);
			return map;
		});
	}

	/** Remaining blacklist ticks for the pair, 0 if none. */
	public static long blacklistRemaining(ServerLevel level, ServerPlayer player, UUID villagerId) {
		Long expiry = player.getAttached(BLACKLIST).get(villagerId);
		return expiry == null ? 0L : Math.max(0L, expiry - level.getGameTime());
	}
}
