package io.github.cumsauce.stalker.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cumsauce.stalker.world.BindingRules.Owner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bind-eligibility rule. These pin a real bug: a one-sided binding used to make a villager
 * ineligible forever, so {@code /stalker start} reported "no eligible villager" in a village.
 */
class BindingRulesTest {

	@Test
	@DisplayName("a baby is never eligible")
	void babiesAreRefused() {
		assertFalse(BindingRules.isEligible(true, false, Owner.UNKNOWN, false));
		assertFalse(BindingRules.isEligible(true, true, Owner.CLAIMS_BACK, false));
	}

	@Test
	@DisplayName("an unbound adult with no blacklist is eligible")
	void plainVillagerIsEligible() {
		assertTrue(BindingRules.isEligible(false, false, Owner.UNKNOWN, false));
	}

	@Test
	@DisplayName("a blacklisted pair is refused")
	void blacklistedIsRefused() {
		assertFalse(BindingRules.isEligible(false, false, Owner.UNKNOWN, true));
	}

	@Test
	@DisplayName("a villager whose owner still claims it is refused")
	void liveOwnerIsRespected() {
		assertFalse(BindingRules.isEligible(false, true, Owner.CLAIMS_BACK, false));
	}

	@Test
	@DisplayName("an offline owner keeps its binding, so the villager stays taken")
	void offlineOwnerKeepsBinding() {
		assertFalse(BindingRules.isEligible(false, true, Owner.OFFLINE, false));
	}

	@Test
	@DisplayName("a one-sided binding is stale, so the villager is eligible again (the bug)")
	void disownedBindingIsHealed() {
		assertTrue(BindingRules.isEligible(false, true, Owner.DISOWNED, false));
	}

	@Test
	@DisplayName("a binding whose owner no longer exists is stale too")
	void unknownOwnerIsStale() {
		assertTrue(BindingRules.isEligible(false, true, Owner.UNKNOWN, false));
		assertFalse(BindingRules.bindingIsLive(Owner.UNKNOWN));
	}

	@Test
	@DisplayName("only offline and claiming owners count as live")
	void liveness() {
		assertTrue(BindingRules.bindingIsLive(Owner.OFFLINE));
		assertTrue(BindingRules.bindingIsLive(Owner.CLAIMS_BACK));
		assertFalse(BindingRules.bindingIsLive(Owner.DISOWNED));
		assertFalse(BindingRules.bindingIsLive(Owner.UNKNOWN));
	}

	@Test
	@DisplayName("a baby is refused even with a stale binding")
	void babyBeatsStaleBinding() {
		assertFalse(BindingRules.isEligible(true, true, Owner.DISOWNED, false));
	}
}
