package io.github.cumsauce.stalker.world;

import java.util.Set;

/**
 * The memory-access invariant for {@link StalkerBrain}, as pure data so it can be tested without a
 * live brain.
 *
 * <p>A crash on a real world ("Unregistered memory fetched: stalker:stalking_speed") came from this
 * rule being broken: {@code Brain.getMemory} throws unless the brain has a slot for the memory type,
 * and slots are created only by {@code Brain.addActivity} for each behavior's required memories
 * (its entry-condition key set). Any memory a task <em>reads</em> must therefore be listed in that
 * task's entry condition, with {@code REGISTERED} if it need not hold a value yet.
 *
 * <p>The brain uses these two sets directly, so a read that is not declared is a test failure here
 * rather than an exception inside the entity tick.
 */
public final class MemoryAccesses {

	/** Registry ids of the two memories, used by {@link StalkerBrain} to register them. */
	public static final String TARGET_ID = "stalking_target";
	public static final String SPEED_ID = "stalking_speed";

	/** Memories {@code MoveTargetTask} reads. Must be a subset of {@link #MOVE_TASK_DECLARED}. */
	public static final Set<String> MOVE_TASK_READS = Set.of("stalker:stalking_target", "stalker:stalking_speed");

	/** Memories the task's entry condition declares (which is what registers their slots). */
	public static final Set<String> MOVE_TASK_DECLARED = Set.of("stalker:stalking_target", "stalker:stalking_speed");

	/** The only memory that gates entry; the speed is optional at entry, only required to exist. */
	public static final Set<String> MOVE_TASK_GATING = Set.of("stalker:stalking_target");

	private MemoryAccesses() {
	}

	/** Whether reading this memory is safe: it must be declared by the reading task. */
	public static boolean isSafeToRead(String memoryId) {
		return MOVE_TASK_DECLARED.contains(memoryId);
	}

	/** Whether this memory is required to hold a value before the task may start. */
	public static boolean gatesEntry(String memoryId) {
		return MOVE_TASK_GATING.contains(memoryId);
	}

	/** True when every memory the task reads is declared — the invariant that crashed a world. */
	public static boolean everyReadIsDeclared() {
		return MOVE_TASK_DECLARED.containsAll(MOVE_TASK_READS);
	}
}
