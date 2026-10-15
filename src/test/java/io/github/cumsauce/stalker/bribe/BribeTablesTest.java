package io.github.cumsauce.stalker.bribe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-core bribe math (PLAN.md section 6, M5 unit tests). The {@link BribeTables} overloads taking live
 * {@code Villager}/{@code ItemStack} objects are not exercised here — those need the Minecraft classpath and belong
 * to the M7 game tests.
 */
class BribeTablesTest {
	private static final Set<String> NO_TAGS = Set.of();

	@Test
	void everyProfessionHasATable() {
		for (String profession : BribeTables.professions()) {
			assertNotNull(BribeTables.table(profession), profession);
		}
		assertEquals(15, BribeTables.professions().size(), "all 15 professions (nitwit and none included)");
	}

	@Test
	void farmerTableMatchesThePlan() {
		assertEquals(1, BribeTables.value("farmer", "minecraft:wheat", NO_TAGS, 10));
		assertEquals(2, BribeTables.value("farmer", "minecraft:hay_block", NO_TAGS, 10));
		assertEquals(3, BribeTables.value("farmer", "minecraft:bread", NO_TAGS, 10));
		assertEquals(8, BribeTables.value("farmer", "minecraft:golden_carrot", NO_TAGS, 10));
	}

	@Test
	void emeraldBeatsEveryThreshold() {
		for (String profession : BribeTables.professions()) {
			if (profession.equals("nitwit")) continue;
			assertEquals(10, BribeTables.value(profession, "minecraft:emerald", NO_TAGS, 10),
					profession + " values an emerald at the configured amount");
		}
	}

	@Test
	void nitwitRefusesEverythingByDefault() {
		assertEquals(0, BribeTables.value("nitwit", "minecraft:emerald", NO_TAGS, 10));
		assertEquals(0, BribeTables.value("nitwit", "minecraft:golden_carrot", NO_TAGS, 10));
		assertFalse(BribeTables.accepts("nitwit", "minecraft:emerald", NO_TAGS, 8, 10));
	}

	@Test
	void thresholdBoundaryAcceptsAtEightAndPretendsAtSeven() {
		assertTrue(BribeTables.accepts("farmer", "minecraft:golden_carrot", NO_TAGS, 8, 10));
		assertFalse(BribeTables.accepts("farmer", "minecraft:wheat", NO_TAGS, 8, 10));
		// wheat at threshold 1 would accept; the pretend path is anything below threshold
		assertTrue(BribeTables.accepts("farmer", "minecraft:wheat", NO_TAGS, 1, 10));
	}

	@Test
	void tagsMatchWhereThePlanSaysAnyOfSomething() {
		Set<String> wool = Set.of(BribeTables.TAG_WOOL);
		Set<String> dye = Set.of(BribeTables.TAG_DYES);
		Set<String> terracotta = Set.of(BribeTables.TAG_TERRACOTTA);
		assertEquals(1, BribeTables.value("shepherd", "minecraft:white_wool", wool, 10));
		assertEquals(1, BribeTables.value("shepherd", "minecraft:red_dye", dye, 10));
		assertEquals(3, BribeTables.value("shepherd", "minecraft:shears", NO_TAGS, 10));
		assertEquals(2, BribeTables.value("mason", "minecraft:white_terracotta", terracotta, 10));
		// A plain item the tag doesn't cover is worthless to the profession
		assertEquals(0, BribeTables.value("shepherd", "minecraft:white_terracotta", terracotta, 10));
	}

	@Test
	void butcherAndUnemployedFoodSets() {
		assertEquals(3, BribeTables.value("butcher", "minecraft:cooked_beef", NO_TAGS, 10));
		assertEquals(2, BribeTables.value("butcher", "minecraft:beef", NO_TAGS, 10));
		assertEquals(8, BribeTables.value("butcher", "minecraft:rabbit_stew", NO_TAGS, 10));
		assertEquals(1, BribeTables.value("none", "minecraft:beef", NO_TAGS, 10));
		assertEquals(2, BribeTables.value("none", "minecraft:cooked_beef", NO_TAGS, 10));
		assertEquals(1, BribeTables.value("none", "minecraft:carrot", NO_TAGS, 10));
		assertEquals(0, BribeTables.value("none", "minecraft:stick", NO_TAGS, 10));
	}

	@Test
	void worthlessIsWorthless() {
		for (String profession : BribeTables.professions()) {
			if (profession.equals("nitwit")) continue;
			assertEquals(0, BribeTables.value(profession, "minecraft:netherite_scrap", NO_TAGS, 10),
					profession + " has no row for items outside its table");
		}
	}

	@Test
	void describeNamesTheProfession() {
		assertTrue(BribeTables.describe("farmer").startsWith("farmer: "));
		assertEquals("nitwit: (nothing)", BribeTables.describe("nitwit"));
	}
}
