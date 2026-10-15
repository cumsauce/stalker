package io.github.cumsauce.stalker.world;

import com.mojang.serialization.Codec;
import io.github.cumsauce.stalker.mixin.VillagerBrainMixin;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;

import java.util.Map;

/**
 * The custom-{@link Activity} movement layer for the stalker villager.
 *
 * <p>Registers the {@code stalker:stalking} {@link Activity} and the
 * {@code stalker:stalking_target} / {@code stalker:stalking_speed} memory module types
 * (in this class's static initializer; {@link VillagerBrainMixin} references this class so the
 * registrations are guaranteed to run before any villager brain is built), and exposes the
 * manager-facing API: the manager owns everything that is not movement and each tick writes a
 * move target through {@link #setTarget}; the activity's memory-gated behavior drives
 * {@link PathNavigation#moveTo} so that unbound villagers are byte-for-byte vanilla.
 *
 * <p>Constraint: {@code Brain#updateActivityFromSchedule} re-asserts the scheduled vanilla
 * activity at most every 20 ticks, which can switch away from STALKING; the priority-0
 * {@link AssertStalkingTask} registered alongside the CORE package re-asserts STALKING on the
 * next brain tick, and the manager rewrites the memory every tick anyway. For that reason the
 * target memory is deliberately <em>not</em> in the activity's erase-when-stopped set (erasing
 * manager state on every schedule flicker would fight the manager); it is erased explicitly by
 * {@link #setTarget(Villager, Vec3, float)} with a null target.
 */
public final class StalkerBrain {

	/** Priority used for the STALKING behaviors. Small numbers run first in {@code Brain}; 0 puts our
	 * navigation before CORE priority 1's {@code MoveToTargetSink} each tick while never displacing
	 * the CORE behaviors themselves (CORE stays active in every activity). */
	private static final int STALK_PRIORITY = 0;

	/** Memory holding the current move target. Present exactly while the manager wants the villager to move. */
	public static final MemoryModuleType<Vec3> STALKING_TARGET = registerMemory("stalking_target", Vec3.CODEC);

	/** Memory holding the movement speed modifier the manager wants the villager to walk at. */
	public static final MemoryModuleType<Float> STALKING_SPEED = registerMemory("stalking_speed", Codec.FLOAT);

	/** The custom activity, gated on the target memory so unbound villagers never enter it. */
	public static final Activity STALKING = Registry.register(
		BuiltInRegistries.ACTIVITY,
		Identifier.fromNamespaceAndPath("stalker", "stalking"),
		new Activity("stalking")
	);

	private static boolean registered = false;

	// Class-load trigger: guarantee registration before any villager brain is built, idempotently
	// (static init itself only runs once per classloader, but a datapack/reload must not re-register).
	private static synchronized void ensureRegistered() {
		if (registered) {
			return;
		}
		// Touching the constants forces their own static-init block on this class in declaration order;
		// the guard only protects against a second ensureRegistered call after, e.g., a reset.
		registered = true;
	}

	private static <U> MemoryModuleType<U> registerMemory(String name, Codec<U> codec) {
		return Registry.register(
			BuiltInRegistries.MEMORY_MODULE_TYPE,
			Identifier.fromNamespaceAndPath("stalker", name),
			new MemoryModuleType<>(java.util.Optional.of(codec))
		);
	}

	static {
		ensureRegistered();
	}

	private StalkerBrain() {
	}

	/**
	 * Forces this class's static initializer (and so the Activity/memory registrations) to run at
	 * mod-init time. Fabric freezes {@code BuiltInRegistries} right after mod init, and a
	 * {@code Registry.register} into a frozen registry throws. The mixin's first call happens at the
	 * first villager's brain build — a world load — which is far too late. {@code Stalker.onInitialize}
	 * calls this.
	 */
	public static void bootstrap() {
	}

	/**
	 * Called from {@link VillagerBrainMixin} at the head of {@code Villager#registerBrainGoals}, which
	 * runs on every brain build including {@code refreshBrain} after profession/baby changes. Adds the
	 * STALKING activity with a memory-gated move task plus a CORE-package assert task. Idempotent per
	 * fresh brain (a new brain is built each time), and a no-op on unbound villagers' behavior because
	 * every task requires the target memory to be present.
	 */
	public static void injectIntoBrain(net.minecraft.world.entity.ai.Brain<Villager> brain) {
		brain.addActivity(
			STALKING,
			ImmutableList.of(Pair.of(STALK_PRIORITY, new MoveTargetTask())),
			ImmutableSet.of(Pair.of(STALKING_TARGET, MemoryStatus.VALUE_PRESENT)),
			ImmutableSet.of()
		);
		// Runs every tick in every activity (CORE is always active) and pulls the brain back into
		// STALKING whenever the target memory is present, winning against the schedule re-assertion.
		brain.addActivity(
			Activity.CORE,
			ImmutableList.of(Pair.of(STALK_PRIORITY, new AssertStalkingTask())),
			ImmutableSet.of(),
			ImmutableSet.of()
		);
	}

	/**
	 * Manager entry point, called every tick. {@code target} is the position the villager should walk
	 * to at {@code speed}; {@code null} means hold position: the target and speed memories are erased
	 * and navigation is stopped, which drops the villager out of the STALKING activity (its entry
	 * condition is the memory being present).
	 */
	public static void setTarget(Villager villager, @Nullable Vec3 target, float speed) {
		var brain = villager.getBrain();
		if (target == null) {
			brain.eraseMemory(STALKING_TARGET);
			brain.eraseMemory(STALKING_SPEED);
			villager.getNavigation().stop();
			return;
		}
		brain.setMemory(STALKING_TARGET, target);
		brain.setMemory(STALKING_SPEED, speed);
	}

	/**
	 * Makes the villager look at {@code entity} immediately (peeks, the strike tell). Direct
	 * look-control writes persist until something else overwrites them; no brain behavior needed.
	 */
	public static void lookAt(Villager villager, Entity entity) {
		villager.getLookControl().setLookAt(entity);
	}

	/**
	 * True while the villager's brain has the STALKING entry requirement satisfiable, i.e. it is
	 * currently managed by a stalker binding. Used by silence/interaction checks.
	 */
	public static boolean isHandling(Villager villager) {
		return villager.getBrain().checkMemory(STALKING_TARGET, MemoryStatus.VALUE_PRESENT);
	}

	/**
	 * Pose carrier for hiding (COVER). Villagers ignore {@link Pose#CROUCHING} in vanilla behavior,
	 * so this changes nothing server-side but the pose is synced entity data the client can key off.
	 */
	public static void setHidden(Villager villager, boolean hidden) {
		villager.setPose(hidden ? Pose.CROUCHING : Pose.STANDING);
	}

	/**
	 * Re-asserts the STALKING activity every tick while the target memory is present. Lives in the
	 * CORE package so it ticks regardless of which non-core activity is active.
	 */
	private static final class AssertStalkingTask extends Behavior<Villager> {

		AssertStalkingTask() {
			super(Map.of());
		}

		@Override
		protected boolean canStillUse(ServerLevel level, Villager villager, long timestamp) {
			return true;
		}

		@Override
		protected void tick(ServerLevel level, Villager villager, long timestamp) {
			if (villager.getBrain().hasMemoryValue(STALKING_TARGET) && !villager.getBrain().isActive(STALKING)) {
				villager.getBrain().setActiveActivityIfPossible(STALKING);
			}
		}
	}

	/**
	 * The movement task: each tick reads the target and speed memories and (re-)issues
	 * {@link PathNavigation#moveTo}, re-pathing when the navigation is done or the target has moved
	 * more than {@code REPATH_DISTANCE_SQR} from the position we last pathed to. Vanilla behaviors
	 * occasionally issue their own {@code moveTo}; ours runs at priority 0 and re-issues so it wins.
	 */
	private static final class MoveTargetTask extends Behavior<Villager> {

		/** Re-path when the target moved more than 1 block (square of 1.0) from the last pathed point. */
		private static final double REPATH_DISTANCE_SQR = 1.0;

		@Nullable
		private Vec3 lastPathedTarget;

		MoveTargetTask() {
			// Effectively no timeout: duration is Integer.MAX_VALUE ticks; the task ends only when the
			// target memory is erased (canStillUse) or the brain stops the activity.
			super(ImmutableMap.of(STALKING_TARGET, MemoryStatus.VALUE_PRESENT), Integer.MAX_VALUE);
		}

		@Override
		protected boolean canStillUse(ServerLevel level, Villager villager, long timestamp) {
			return villager.getBrain().hasMemoryValue(STALKING_TARGET);
		}

		@Override
		protected void start(ServerLevel level, Villager villager, long timestamp) {
			this.lastPathedTarget = null;
		}

		@Override
		protected void tick(ServerLevel level, Villager villager, long timestamp) {
			Vec3 target = villager.getBrain().getMemory(STALKING_TARGET).orElse(null);
			if (target == null) {
				return;
			}
			PathNavigation navigation = villager.getNavigation();
			boolean stale = this.lastPathedTarget == null
				|| navigation.isDone()
				|| this.lastPathedTarget.distanceToSqr(target) > REPATH_DISTANCE_SQR;
			if (stale) {
				float speed = villager.getBrain().getMemory(STALKING_SPEED).orElse(1.0F);
				if (navigation.moveTo(target.x, target.y, target.z, speed)) {
					this.lastPathedTarget = target;
				}
			}
		}

		@Override
		protected void stop(ServerLevel level, Villager villager, long timestamp) {
			this.lastPathedTarget = null;
			// Hold position when the manager cleared the target mid-run.
			if (!villager.getBrain().hasMemoryValue(STALKING_TARGET)) {
				villager.getNavigation().stop();
			}
		}
	}

}
