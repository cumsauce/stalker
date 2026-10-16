package io.github.cumsauce.stalker.world;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Scatters adult villagers with random jobs on standable ground in a ring around a centre point —
 * the core of {@code /stalker spawn}. Jobs are drawn from the profession registry with nitwits and
 * the unemployed filtered out; ground is taken from the motion-blocking heightmap; each candidate
 * position is bounded in attempts so a bad location (void, ocean) cannot spin the command forever.
 */
public final class VillagerSpawner {

	/** Ring radius: inner and outer bound around the centre. */
	public static final double MIN_RADIUS = 4.0;
	public static final double MAX_RADIUS = 18.0;

	/** Attempts per wanted villager before giving up (bad columns, failed spawns). */
	public static final int ATTEMPTS_PER_VILLAGER = 10;

	private VillagerSpawner() {
	}

	/**
	 * Spawns up to {@code count} employed villagers in a ring around {@code centre}.
	 *
	 * @return the professions of the villagers spawned, one entry each, sorted case-insensitively
	 */
	public static List<String> spawnRing(ServerLevel level, Vec3 centre, int count, RandomSource random) {
		List<String> professions = new ArrayList<>();
		int spawned = 0;
		int attempts = 0;
		while (spawned < count && attempts < count * ATTEMPTS_PER_VILLAGER) {
			attempts++;
			double angle = random.nextDouble() * Math.PI * 2.0;
			double radius = MIN_RADIUS + random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
			int x = BlockPos.containing(centre.x + Math.cos(angle) * radius, 0.0, 0.0).getX();
			int z = BlockPos.containing(0.0, 0.0, centre.z + Math.sin(angle) * radius).getZ();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			if (y <= level.getMinY()) {
				continue;
			}
			var profession = BuiltInRegistries.VILLAGER_PROFESSION.getRandom(random).orElse(null);
			if (profession == null || isExcluded(profession.key().identifier().getPath())) {
				continue;
			}
			var type = BuiltInRegistries.VILLAGER_TYPE.getRandom(random).orElse(null);
			if (type == null) {
				continue;
			}
			Villager villager = EntityTypes.VILLAGER.spawn(level, new BlockPos(x, y, z), EntitySpawnReason.COMMAND);
			if (villager == null) {
				continue;
			}
			villager.setVillagerData(new VillagerData(type, profession, 1));
			villager.setVillagerDataFinalized(true);
			professions.add(profession.key().identifier().getPath());
			spawned++;
		}
		professions.sort(String.CASE_INSENSITIVE_ORDER);
		return professions;
	}

	/** Jobs that do not count as "a villager with a job" for this helper. */
	private static boolean isExcluded(String professionId) {
		return professionId.equals("none") || professionId.equals("nitwit");
	}
	// ------------------------------------------------------------------ hamlet

	/** Hut footprint is 5x5; walls two high with a plank roof. */
	private static final int HUT_SIZE = 5;
	private static final int HUT_WALL_HEIGHT = 2;

	/** Bell pedestal height above the ground line. */
	private static final int BELL_POST_HEIGHT = 1;

	/**
	 * Builds a small settlement at {@code centre}: a bell on a cobble pedestal, {@code huts} simple
	 * huts in a ring around it (cobble floor, plank walls, a door, a plank roof, a bed and a torch
	 * inside), and one employed villager by each hut's door. Debug tooling — the blocks replace
	 * whatever is there, and the villagers count is returned so callers can report it.
	 *
	 * @return the professions of the villagers spawned
	 */
	public static List<String> buildHamlet(ServerLevel level, Vec3 centre, int huts, RandomSource random) {
		List<String> professions = new ArrayList<>();
		placeBell(level, centre, random);
		for (int i = 0; i < huts; i++) {
			double angle = (Math.PI * 2.0 * i) / huts + random.nextDouble() * 0.3;
			double radius = 7.0 + random.nextDouble() * 5.0;
			int x = BlockPos.containing(centre.x + Math.cos(angle) * radius, 0.0, 0.0).getX();
			int z = BlockPos.containing(0.0, 0.0, centre.z + Math.sin(angle) * radius).getZ();
			Direction facing = Direction.fromYRot((float) (Math.toDegrees(angle) + 180.0));
			BlockPos doorFront = buildHut(level, new BlockPos(x, 0, z), facing, random);
			// buildHut returns null when the column is at world bottom (void/unloaded) — spawning
			// there would drop a villager into the void, so skip the hut entirely.
			if (doorFront == null) {
				continue;
			}
			String job = spawnOneAt(level, doorFront, random);
			if (job != null) {
				professions.add(job);
			}
		}
		professions.sort(String.CASE_INSENSITIVE_ORDER);
		return professions;
	}

	/** Bell on a cobble pedestal at the centre's heightmap ground. */
	private static void placeBell(ServerLevel level, Vec3 centre, RandomSource random) {
		int x = BlockPos.containing(centre.x, 0.0, 0.0).getX();
		int z = BlockPos.containing(0.0, 0.0, centre.z).getZ();
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (y <= level.getMinY()) {
			return;
		}
		level.setBlock(new BlockPos(x, y - 1, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
		level.setBlock(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
		level.setBlock(new BlockPos(x, y + 1 + BELL_POST_HEIGHT - 1, z),
				Blocks.BELL.defaultBlockState()
						.setValue(BellBlock.FACING, Direction.from2DDataValue(random.nextInt(4))), 3);
	}

	/**
	 * A 5x5 hut with its door on the {@code facing} side. Positions are resolved against the
	 * heightmap at the hut's centre column.
	 *
	 * @return the position in front of the door, where a villager is spawned — or {@code null} when
	 *         the column is at world bottom and a hut cannot be placed there
	 */
	private static BlockPos buildHut(ServerLevel level, BlockPos centreColumn, Direction facing, RandomSource random) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, centreColumn.getX(), centreColumn.getZ());
		if (y <= level.getMinY()) {
			return null;
		}
		int half = HUT_SIZE / 2;
		int x0 = centreColumn.getX() - half;
		int z0 = centreColumn.getZ() - half;
		int x1 = x0 + HUT_SIZE - 1;
		int z1 = z0 + HUT_SIZE - 1;

		// Floor and walls.
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				level.setBlock(new BlockPos(x, y - 1, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
				if (edge) {
					for (int dy = 0; dy < HUT_WALL_HEIGHT; dy++) {
						level.setBlock(new BlockPos(x, y + dy, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
					}
				}
				// Roof: full plank layer.
				level.setBlock(new BlockPos(x, y + HUT_WALL_HEIGHT, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
			}
		}

		// Door: middle of the facing side, two tall, opening outward.
		int doorX = facing.getAxis() == Direction.Axis.X
				? (facing == Direction.EAST ? x1 : x0)
				: centreColumn.getX();
		int doorZ = facing.getAxis() == Direction.Axis.Z
				? (facing == Direction.SOUTH ? z1 : z0)
				: centreColumn.getZ();
		level.setBlock(new BlockPos(doorX, y, doorZ), Blocks.OAK_DOOR.defaultBlockState()
				.setValue(DoorBlock.FACING, facing)
				.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
		level.setBlock(new BlockPos(doorX, y + 1, doorZ), Blocks.OAK_DOOR.defaultBlockState()
				.setValue(DoorBlock.FACING, facing)
				.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);

		// Interior: bed along the back (opposite the door) and a torch by it.
		Direction inward = facing.getOpposite();
		int backX = facing.getAxis() == Direction.Axis.X ? (facing == Direction.EAST ? x0 + 1 : x1 - 1) : centreColumn.getX();
		int backZ = facing.getAxis() == Direction.Axis.Z ? (facing == Direction.SOUTH ? z0 + 1 : z1 - 1) : centreColumn.getZ();
		// The foot is one cell toward the door from the head.
		int footX = backX + (facing.getAxis() == Direction.Axis.X ? facing.getStepX() : 0);
		int footZ = backZ + (facing.getAxis() == Direction.Axis.Z ? facing.getStepZ() : 0);
		level.setBlock(new BlockPos(backX, y, backZ), Blocks.STRAW_BED.defaultBlockState()
				.setValue(BedBlock.PART, BedPart.HEAD)
				.setValue(BedBlock.FACING, inward), 3);
		level.setBlock(new BlockPos(footX, y, footZ), Blocks.STRAW_BED.defaultBlockState()
				.setValue(BedBlock.PART, BedPart.FOOT)
				.setValue(BedBlock.FACING, inward), 3);
		int torchX = footX + (facing.getAxis() == Direction.Axis.Z ? 1 : 0);
		int torchZ = footZ + (facing.getAxis() == Direction.Axis.X ? 1 : 0);
		level.setBlock(new BlockPos(torchX, y, torchZ), Blocks.TORCH.defaultBlockState(), 3);

		// In front of the door: where the villager goes.
		return new BlockPos(doorX + facing.getStepX(), y, doorZ + facing.getStepZ());
	}

	/** Spawns one employed villager standing at {@code at} (its feet position). */
	private static String spawnOneAt(ServerLevel level, BlockPos at, RandomSource random) {
		for (int attempt = 0; attempt < 20; attempt++) {
			var profession = BuiltInRegistries.VILLAGER_PROFESSION.getRandom(random).orElse(null);
			if (profession == null || isExcluded(profession.key().identifier().getPath())) {
				continue;
			}
			var type = BuiltInRegistries.VILLAGER_TYPE.getRandom(random).orElse(null);
			if (type == null) {
				continue;
			}
			Villager villager = EntityTypes.VILLAGER.spawn(level, at, EntitySpawnReason.COMMAND);
			if (villager == null) {
				return null;
			}
			villager.setVillagerData(new VillagerData(type, profession, 1));
			villager.setVillagerDataFinalized(true);
			return profession.key().identifier().getPath();
		}
		return null;
	}
}
