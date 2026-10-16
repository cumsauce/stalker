package io.github.cumsauce.stalker.bribe;

import io.github.cumsauce.stalker.Stalker;
import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.sound.Cues;
import io.github.cumsauce.stalker.world.Attachments;
import io.github.cumsauce.stalker.world.Phases;
import io.github.cumsauce.stalker.world.StalkerManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * The bribe interaction: right-clicking a bound villager with an item while stalked. Per PLAN.md section 6 the
 * interaction is consumed for the bound player only (the trade screen never opens for them; everyone else trades
 * normally), exactly one item is taken per click, and the same accept sounds play whether the offer is really
 * accepted or pretend-accepted. A pretend-accept schedules a forced variant B chase {@code pretendChaseDelayTicks}
 * later; the delay is ticked by a static list here so nothing else has to expose scheduling.
 */
public final class BribeInteract {
	/** Villagers scheduled to turn on their stalker: player UUID, villager UUID, server tick to fire at. */
	private record PendingChase(UUID player, UUID villager, long fireAtTick) {}

	private static final List<PendingChase> PENDING = new ArrayList<>();

	private BribeInteract() {}

	/** Registers the interaction and tick hooks; called once from the mod initializer. */
	public static void initialize() {
		UseEntityCallback.EVENT.register(BribeInteract::onUseEntity);
		ServerTickEvents.END_SERVER_TICK.register(BribeInteract::tick);
	}

	private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity,
			EntityHitResult hit) {
		if (!(player instanceof ServerPlayer clicked) || !(entity instanceof Villager villager)) return InteractionResult.PASS;
		if (clicked.isShiftKeyDown()) return InteractionResult.PASS;
		// Bribes only land on our own stalker, and only while it is stalking/covering — CHASE and STUN are dead ends.
		UUID bound = Attachments.boundVillager(clicked).orElse(null);
		if (bound == null || !bound.equals(villager.getUUID())) return InteractionResult.PASS;
		Phases.Phase phase = StalkerManager.getPhase(clicked);
		if (phase != Phases.Phase.STALK && phase != Phases.Phase.COVER) return InteractionResult.PASS;
		ItemStack offered = clicked.getItemInHand(hand);
		if (offered.isEmpty()) return InteractionResult.PASS;

		// Exactly one item per click, on both the accept and pretend paths. The accept test runs
		// BEFORE the shrink: the held stack is a live reference, so shrinking first would evaluate
		// a single-count bribe against an empty stack and it could never succeed.
		boolean accepted = BribeTables.accepts(villager, offered);
		offered.shrink(1);

		// Identical sounds either way — that is the deception (PLAN.md section 5/6).
		Cues.bribeAccepted(villager);
		if (accepted) {
			// Accept: unbind AND blacklist (plan §6 — it got what it wanted, 10-minute immunity).
			StalkerManager.blacklistAfterSuccess(clicked);
			villager.getNavigation().moveTo(
					villager.getX() + (villager.getRandom().nextDouble() - 0.5) * 32,
					villager.getY(),
					villager.getZ() + (villager.getRandom().nextDouble() - 0.5) * 32,
					1.0);
		} else {
			StalkerConfig config = StalkerConfig.get();
			int delay = config.pretendChaseMinDelayTicks()
					+ villager.getRandom().nextInt(
							Math.max(1, config.pretendChaseMaxDelayTicks() - config.pretendChaseMinDelayTicks() + 1));
			schedulePretendChase(clicked, villager, clicked.level().getServer().getTickCount() + delay);
		}
		return InteractionResult.SUCCESS;
	}

	private static void schedulePretendChase(ServerPlayer player, Villager villager, long fireAtTick) {
		PENDING.add(new PendingChase(player.getUUID(), villager.getUUID(), fireAtTick));
	}

	private static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		Iterator<PendingChase> it = PENDING.iterator();
		while (it.hasNext()) {
			PendingChase chase = it.next();
			// Drop entries whose player left the server; otherwise the entry sits in PENDING for
			// the JVM's lifetime and grows across a long-running server.
			ServerPlayer player = server.getPlayerList().getPlayer(chase.player());
			if (player == null) {
				it.remove();
				continue;
			}
			if (now < chase.fireAtTick()) continue;
			it.remove();
			// A real accept in the window (or a command unbind) cancels the betrayal.
			UUID bound = Attachments.boundVillager(player).orElse(null);
			if (bound == null || !bound.equals(chase.villager())) continue;
			Stalker.LOGGER.debug("Pretend-accepted bribe turns into a chase for {}", player.getGameProfile().name());
			StalkerManager.forceStrike(player);
		}
	}
}
