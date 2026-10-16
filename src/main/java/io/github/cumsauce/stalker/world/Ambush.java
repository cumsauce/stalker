package io.github.cumsauce.stalker.world;

/**
 * The patience strike's decision, kept pure so it is unit-testable without a level: the manager
 * supplies how close the player is, how many villagers can see the stalker, and whether the player
 * is standing still, and this says when the trigger holds and when it has held long enough.
 *
 * <p>The design (play feedback): the stalker does not need to hide from its victim, only from
 * witnesses. Once it is close, unobserved by any other villager and by any other player, and the
 * victim has stopped moving, it loses patience and strikes — even while the victim is looking
 * straight at it.
 */
public final class Ambush {

	/** Strike range is multiplied by this: the ambush starts a little further out than a snatch. */
	public static final double RANGE_FACTOR = 2.0;

	/** Ticks the trigger must hold before it commits — "a few seconds". */
	public static final int HOLD_TICKS = 60;

	private Ambush() {
	}

	/**
	 * Whether the ambush trigger currently holds: within range, no villager witness can see the
	 * stalker, and the player is standing still.
	 *
	 * @param witnessesInSight villagers other than the stalker that have line of sight to it
	 */
	public static boolean conditionsHold(double distance, double strikeRange, int witnessesInSight, boolean playerStill) {
		return distance <= strikeRange * RANGE_FACTOR && witnessesInSight == 0 && playerStill;
	}

	/** Whether the trigger has held long enough to strike. */
	public static boolean ready(int heldTicks) {
		return heldTicks >= HOLD_TICKS;
	}
}
