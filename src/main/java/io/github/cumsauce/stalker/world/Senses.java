package io.github.cumsauce.stalker.world;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pure-geometry senses for the stalker: line of sight, look cone, spotted and
 * strike-facing tests (plan §3, §4). All static; the math core takes plain
 * vectors so it stays unit-testable without a level.
 */
public final class Senses {

	/**
	 * Cached collisionless context: {@link net.minecraft.world.phys.shapes.CollisionContext#empty()} returns the
	 * same singleton instance, but caching the import + lookup avoids walking a fully qualified path on every LOS
	 * test. {@code canSee} is one of the hot-path callers (a tick-bound villager can hit it three times per tick).
	 */
	private static final net.minecraft.world.phys.shapes.CollisionContext COLLISIONLESS =
			net.minecraft.world.phys.shapes.CollisionContext.empty();

	private Senses() {
	}

	/**
	 * Line of sight between two eye positions: {@code BlockGetter#clip} with
	 * {@code ClipContext.Block.VISUAL}; blocked means no LOS.
	 */
	public static boolean canSee(BlockGetter level, Vec3 fromEye, Vec3 toEye) {
		// The ClipContext must be a fresh allocation because it stores the start/end vectors; the collision shape
		// context it wraps is the cached singleton.
		ClipContext ctx = new ClipContext(fromEye, toEye, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
				COLLISIONLESS);
		return level.clip(ctx).getType() == HitResult.Type.MISS;
	}

	/**
	 * Can the player see the villager, eye to eye.
	 */
	public static boolean canSee(Level level, Player player, Entity villager) {
		return canSee(level, player.getEyePosition(), villager.getEyePosition());
	}

	/**
	 * Dot product of the look direction and the unit direction to the target.
	 * 1.0 = dead centre of view, 0.0 = 90° off, negative = behind.
	 */
	public static double lookDot(Vec3 look, Vec3 eye, Vec3 targetEye) {
		Vec3 toTarget = targetEye.subtract(eye);
		double len = toTarget.length();
		if (len < 1.0E-4) {
			return 1.0;
		}
		return look.normalize().dot(toTarget.scale(1.0 / len));
	}

	/**
	 * True if the target is within the player's look cone: dot ≥ cos(angle).
	 * {@code maxAngleDegrees} is the half-angle of the cone.
	 */
	public static boolean inLookCone(Vec3 look, Vec3 eye, Vec3 targetEye, double maxAngleDegrees) {
		return lookDot(look, eye, targetEye) >= Math.cos(Math.toRadians(maxAngleDegrees));
	}

	/**
	 * Plan §3 spotted test: LOS clear AND within the cone AND within range. The caller may pass a
	 * {@code precomputedCanSee} value already computed for this tick (e.g. via
	 * {@link #canSee(Level, Player, Entity)}) to avoid running the chunk-clip ray twice in the same
	 * tick; pass {@code null} to compute it here.
	 */
	public static boolean isSpotted(Level level, Player player, Entity villager, double range,
			double coneAngleDegrees, Boolean precomputedCanSee) {
		if (player.distanceTo(villager) > range) {
			return false;
		}
		if (!inLookCone(player.getLookAngle(), player.getEyePosition(), villager.getEyePosition(), coneAngleDegrees)) {
			return false;
		}
		return precomputedCanSee != null ? precomputedCanSee : canSee(level, player, villager);
	}

	/**
	 * Plan §3 spotted test: LOS clear AND within the cone AND within range.
	 */
	public static boolean isSpotted(Level level, Player player, Entity villager, double range, double coneAngleDegrees) {
		return isSpotted(level, player, villager, range, coneAngleDegrees, null);
	}

	/**
	 * True when the player's gaze passes near the block position (used to gate
	 * peeking: peek only while the player is not looking at the cover point).
	 */
	public static boolean lookingAtPos(Player player, Vec3 pos, double coneAngleDegrees) {
		return inLookCone(player.getLookAngle(), player.getEyePosition(), pos, coneAngleDegrees);
	}
}
