package io.github.cumsauce.stalker.world;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
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
}
