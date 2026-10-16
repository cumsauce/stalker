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
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.DoorBlock;
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
	public static final MemoryModuleType<Vec3> STALKING_TARGET = registerMemory(MemoryAccesses.TARGET_ID, Vec3.CODEC);

	/** Memory holding the movement speed modifier the manager wants the villager to walk at. */
	public static final MemoryModuleType<Float> STALKING_SPEED = registerMemory(MemoryAccesses.SPEED_ID, Codec.FLOAT);

	/** Speed used when the manager has set a target but no speed (and the slot is empty). */
	private static final float DEFAULT_SPEED = 1.0F;

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

	/** Looks at a plain point (the pretend-normal idle glances). */
	public static void lookAt(Villager villager, Vec3 point) {
		villager.getLookControl().setLookAt(point.x, point.y, point.z);
	}

	/**
	 * Drops a villager out of the stalker entirely and hands it back to vanilla behavior: clears the
	 * movement memories and pose, and re-applies the schedule so STALKING stops being the active
	 * activity. {@link #setTarget} alone is not enough — the CORE assert task latches STALKING as the
	 * active activity, and with no IDLE/WORK behaviors running the villager would stand inert rather
	 * than resume wandering. Called on every teardown path.
	 */
	public static void release(Villager villager) {
		setTarget(villager, null, 0.0F);
		villager.setPose(Pose.STANDING);
		villager.getBrain().useDefaultActivity();
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
	 *
	 * <p>It must not out-rank the villager's own survival activities: a bound villager still has to
	 * flee zombies and fire, so while PANIC or RAID is active this leaves the brain alone. It also
	 * uses the maximum duration (like {@link MoveTargetTask}) so it runs every tick, not for a fixed
	 * 60-tick spell with a one-tick gap.
	 */
	private static final class AssertStalkingTask extends Behavior<Villager> {

		AssertStalkingTask() {
			super(Map.of(), Integer.MAX_VALUE);
		}

		@Override
		protected boolean canStillUse(ServerLevel level, Villager villager, long timestamp) {
			return true;
		}

		@Override
		protected void tick(ServerLevel level, Villager villager, long timestamp) {
			var brain = villager.getBrain();
			// Never override the villager's own panic/raid response (vanilla PANIC/RAID behaviours).
			if (brain.isActive(Activity.PANIC) || brain.isActive(Activity.RAID)) {
				return;
			}
			if (brain.hasMemoryValue(STALKING_TARGET) && !brain.isActive(STALKING)) {
				brain.setActiveActivityIfPossible(STALKING);
			}
		}
	}

	/**
	 * The movement task: each tick reads the target and speed memories and (re-)issues
	 * {@link PathNavigation#moveTo}, re-pathing when the target has moved or the navigation has finished its path (throttled)
	 * more than {@code REPATH_DISTANCE_SQR} from the position we last pathed to. Vanilla behaviors
	 * occasionally issue their own {@code moveTo}; ours runs at priority 0 and re-issues so it wins.
	 */
	private static final class MoveTargetTask extends Behavior<Villager> {

		/** Re-path when the target moved more than 1 block (square of 1.0) from the last pathed point. */
		private static final double REPATH_DISTANCE_SQR = 1.0;

		/** Minimum ticks between re-pathing a finished path (a pushed-off villager must recover, but
		 * {@code navigation.isDone()} alone is true every tick and would re-run the A* each one). */
		private static final long DONE_REPATH_INTERVAL_TICKS = 10;

		@Nullable
		private Vec3 lastPathedTarget;
		private long lastDoneRepathTimestamp;

		MoveTargetTask() {
			// Effectively no timeout: duration is Integer.MAX_VALUE ticks; the task ends only when the
			// target memory is erased (canStillUse) or the brain stops the activity.
			// Both memories are listed even though only the target gates entry: Brain.registerMemory
			// runs off a behavior's required memories, so a memory we only *read* would have no slot
			// on this brain and Brain.getMemory would throw "Unregistered memory fetched". The speed
			// is optional at entry (canStillUse and the read both default it).
			super(ImmutableMap.of(
				STALKING_TARGET, MemoryStatus.VALUE_PRESENT,
				STALKING_SPEED, MemoryStatus.REGISTERED), Integer.MAX_VALUE);
		}

		@Override
		protected boolean canStillUse(ServerLevel level, Villager villager, long timestamp) {
			// Entry required both (so both are registered); the task keeps running on the target alone.
			return villager.getBrain().hasMemoryValue(STALKING_TARGET);
		}

		@Override
		protected void start(ServerLevel level, Villager villager, long timestamp) {
			this.lastPathedTarget = null;
			this.lastDoneRepathTimestamp = 0;
		}

		@Override
		protected void tick(ServerLevel level, Villager villager, long timestamp) {
			Vec3 target = villager.getBrain().getMemory(STALKING_TARGET).orElse(null);
			if (target == null) {
				return;
			}
			PathNavigation navigation = villager.getNavigation();
			// A finished path re-paths at most once per interval: a villager pushed off its route must
			// recover, but isDone() is true every tick afterwards and alone would re-run the A* each one.
			// The move attempt is remembered even when no path is found, so an unreachable target
			// does not spin the pathfinder either.
			boolean stale = this.lastPathedTarget == null
				|| this.lastPathedTarget.distanceToSqr(target) > REPATH_DISTANCE_SQR
				|| (navigation.isDone()
					&& timestamp - this.lastDoneRepathTimestamp >= DONE_REPATH_INTERVAL_TICKS);
			if (stale) {
				float speed = villager.getBrain().getMemory(STALKING_SPEED).orElse(DEFAULT_SPEED);
				navigation.moveTo(target.x, target.y, target.z, speed);
				this.lastPathedTarget = target;
				this.lastDoneRepathTimestamp = timestamp;
			}
			openDoorsOnPath(level, navigation);
		}

		/**
		 * Opens closed doors standing in the next few path nodes. Villagers' brains would do this via
		 * the vanilla OpenDoor task, but that keys off the WALK_TARGET memory, which this task
		 * deliberately does not use (plan Risk #1) — so the villager would otherwise bump into every
		 * closed door between it and the player. Doors are left open behind it, which suits it.
		 */
		private void openDoorsOnPath(ServerLevel level, PathNavigation navigation) {
			Path path = navigation.getPath();
			if (path == null || path.isDone()) {
				return;
			}
			for (int i = 0; i < Math.min(3, path.getNodeCount()); i++) {
				BlockPos nodePos = path.getNodePos(i);
				var state = level.getBlockState(nodePos);
				if (state.getBlock() instanceof DoorBlock door && !state.getValue(DoorBlock.OPEN)) {
					level.setBlock(nodePos, state.setValue(DoorBlock.OPEN, true), 3);
					level.playSound(null, nodePos, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 0.8F, 1.0F);
				}
			}
		}

		@Override
		protected void stop(ServerLevel level, Villager villager, long timestamp) {
			this.lastPathedTarget = null;
			this.lastDoneRepathTimestamp = 0;
			// Hold position when the manager cleared the target mid-run.
			if (!villager.getBrain().hasMemoryValue(STALKING_TARGET)) {
				villager.getNavigation().stop();
			}
		}
	}

}
