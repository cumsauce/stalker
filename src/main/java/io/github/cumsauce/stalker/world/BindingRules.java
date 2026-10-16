package io.github.cumsauce.stalker.world;

import java.util.UUID;
import net.minecraft.world.entity.Entity;

/**
 * The bind-eligibility decision, as pure logic so it can be unit tested without a live server.
 *
 * <p>This exists because of a real bug: a villager can end up with {@code STALKED_BY} set while its
 * player no longer claims it back (an older save, an interrupted teardown, a deleted player). If
 * that one-sided binding is taken at face value the villager is ineligible for every player forever,
 * since nothing else on the server clears it — which is exactly what makes {@code /stalker start}
 * report "no eligible villager" in a village full of villagers.
 *
 * <p>The decision only needs three facts about the candidate's owner: is the owner online, and if
 * so does it point back at this villager. Those are passed in, so the rule itself is testable.
 */
public final class BindingRules {

	/** What the villager's recorded owner looks like from here. */
	public enum Owner {
		/** The owner UUID resolves to nobody (the player was deleted, or the data is corrupt). */
		UNKNOWN,
		/** The owner is not online; the binding is kept (plan §7: it survives logout). */
		OFFLINE,
		/** The owner is online and still claims this villager. */
		CLAIMS_BACK,
		/** The owner is online but no longer points at this villager: the binding is stale. */
		DISOWNED
	}

	private BindingRules() {
	}

	/**
	 * Whether a bound villager's binding is still real. Only {@link Owner#DISOWNED} and
	 * {@link Owner#UNKNOWN} are stale; an offline owner keeps its binding so that logging out to
	 * escape does not let the villager be re-bound to someone else.
	 */
	public static boolean bindingIsLive(Owner owner) {
		return owner == Owner.OFFLINE || owner == Owner.CLAIMS_BACK;
	}

	/**
	 * Whether a villager may be bound, given that it is an adult, whether it is already bound, how
	 * live that binding is, and whether it is blacklisted against the asking player.
	 */
	public static boolean isEligible(boolean isBaby, boolean isBound, Owner owner, boolean blacklisted) {
		if (isBaby) {
			return false;
		}
		if (isBound && bindingIsLive(owner)) {
			return false;
		}
		return !blacklisted;
	}

	/**
	 * Whether an entity removal should end the villager's binding. Only removals that destroy the
	 * entity do (killed, discarded — lightning conversion, /kill, despawn). Chunk unloads and
	 * dimension changes keep it (plan §7: bindings survive both), as does a null reason.
	 */
	public static boolean removalEndsBinding(Entity.RemovalReason reason) {
		return reason != null && reason.shouldDestroy();
	}
}
