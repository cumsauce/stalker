package io.github.cumsauce.stalker.world;

/**
 * The four phases of the stalker state machine (plan "The loop").
 *
 * <p>Nested in {@code Phases} so the enum lives in the file the integrator
 * expects; the constants are the canonical names.
 */
public final class Phases {

	private Phases() {
	}

	/** Stalker state machine phases. */
	public enum Phase {
		/** Orbiting at a distance, approaching only while unseen. */
		STALK,
		/** Hiding behind cover after being spotted; peek cycles. */
		COVER,
		/** Strike variant B in progress: blindness chase with melee hits. */
		CHASE,
		/** Post-chase daze: no movement, escape window for the player. */
		STUN
	}
}
