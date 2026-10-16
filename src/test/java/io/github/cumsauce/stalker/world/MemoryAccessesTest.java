package io.github.cumsauce.stalker.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The invariant behind a crash on a real world: {@code Unregistered memory fetched: stalker:stalking_speed}.
 *
 * <p>{@code Brain.getMemory} throws unless the brain has a slot for that type, and slots exist only
 * for memories a behavior declares in its entry condition. Reading an undeclared memory therefore
 * throws inside the entity tick and takes the server down. {@link MemoryAccesses} holds that rule as
 * data so this cannot regress silently again.
 */
class MemoryAccessesTest {

	@Test
	@DisplayName("every memory the movement task reads is declared (the invariant that crashed)")
	void everyReadIsDeclared() {
		assertTrue(MemoryAccesses.everyReadIsDeclared(),
			"a memory is read without being declared; Brain.getMemory will throw at runtime");
	}

	@Test
	@DisplayName("an undeclared read is unsafe")
	void undeclaredReadIsUnsafe() {
		assertFalse(MemoryAccesses.isSafeToRead("stalker:not_a_real_memory"));
	}

	@Test
	@DisplayName("the speed memory is declared even though it does not gate entry")
	void speedIsDeclaredButDoesNotGate() {
		assertTrue(MemoryAccesses.isSafeToRead("stalker:stalking_speed"));
		assertFalse(MemoryAccesses.gatesEntry("stalker:stalking_speed"));
	}

	@Test
	@DisplayName("the target memory is declared and does gate entry")
	void targetIsDeclaredAndGates() {
		assertTrue(MemoryAccesses.isSafeToRead("stalker:stalking_target"));
		assertTrue(MemoryAccesses.gatesEntry("stalker:stalking_target"));
	}
}
