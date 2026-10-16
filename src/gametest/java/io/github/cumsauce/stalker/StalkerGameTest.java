package io.github.cumsauce.stalker;

import io.github.cumsauce.stalker.world.StalkerBrain;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

/**
 * Binds a villager and lets the brain tick. This is the scenario that crashed a real world with
 * "Unregistered memory fetched: stalker:stalking_speed": the movement task read a memory the brain
 * had no slot for. It only shows up once a villager is actually bound and moving, which no earlier
 * test did, so this exists to keep that path exercised.
 */
public class StalkerGameTest {

	@GameTest(maxTicks = 120)
	public void boundVillagerTicksWithoutCrashing(GameTestHelper helper) {
		Villager villager = helper.spawn(EntityTypes.VILLAGER, 1, 1, 1);
		// Drive the brain through the movement path the manager uses, re-targeting so the move
		// task's repath branch runs too. Delays stay inside the 100-tick game test budget.
		StalkerBrain.setTarget(villager, new Vec3(3.5, 1, 3.5), 1.0F);
		helper.runAfterDelay(20, () -> StalkerBrain.setTarget(villager, new Vec3(1.5, 1, 1.5), 1.0F));
		helper.runAfterDelay(45, () -> StalkerBrain.setTarget(villager, new Vec3(4.5, 1, 1.5), 1.0F));
		helper.runAfterDelay(70, () -> {
			StalkerBrain.release(villager);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void hamletBuildsBellAndVillagers(net.minecraft.gametest.framework.GameTestHelper helper) {
		var spawned = io.github.cumsauce.stalker.world.VillagerSpawner.buildHamlet(
				helper.getLevel(), helper.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, 1.0, 4.5)),
				2, helper.getLevel().getRandom());
		helper.runAfterDelay(10, () -> {
			if (spawned.size() < 2) {
				helper.fail("hamlet spawned " + spawned.size() + " villagers, wanted 2");
				return;
			}
			// A bell must exist in the centre column somewhere above ground (the ground level of a
			// gametest structure varies, so scan the column rather than hard-coding a Y).
			boolean bellFound = false;
			for (int y = 0; y < 30; y++) {
				var pos = helper.absolutePos(new net.minecraft.core.BlockPos(4, y, 4));
				if (helper.getLevel().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BELL)) {
					bellFound = true;
					break;
				}
			}
			if (!bellFound) {
				helper.fail("no bell found in the hamlet centre column");
				return;
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 90)
	public void villagerOpensDoorOnItsPath(net.minecraft.gametest.framework.GameTestHelper helper) {
		// A 5x5 pen with a closed oak door as the only opening. The villager inside is targeted at a
		// point outside; the only route is through the door, so it must open it to make progress.
		var centre = helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 3));
		int wallX = centre.getX();
		int wallZ = centre.getZ();
		// Ground line: highest non-air at the centre column, so the pen sits on the platform.
		int y = centre.getY();
		while (y > 1 && helper.getLevel().getBlockState(new net.minecraft.core.BlockPos(wallX, y - 2, wallZ)).isAir()) {
			y--;
		}
		y--;
		var planks = net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState();
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				if (!edge) {
					continue;
				}
				// The door is the middle of the north edge (the target side).
				boolean doorSpot = dz == -2 && dx == 0;
				// Ground fill under the wall line so nothing floats or pops.
				helper.getLevel().setBlock(new net.minecraft.core.BlockPos(wallX + dx, y - 1, wallZ + dz), planks, 3);
				if (doorSpot) {
					helper.getLevel().setBlock(new net.minecraft.core.BlockPos(wallX + dx, y, wallZ + dz),
							net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
									.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
											net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
									.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false), 2);
					helper.getLevel().setBlock(new net.minecraft.core.BlockPos(wallX + dx, y + 1, wallZ + dz),
							net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
									.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
											net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
									.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false), 2);
				} else {
					helper.getLevel().setBlock(new net.minecraft.core.BlockPos(wallX + dx, y, wallZ + dz), planks, 3);
					helper.getLevel().setBlock(new net.minecraft.core.BlockPos(wallX + dx, y + 1, wallZ + dz), planks, 3);
				}
			}
		}
		var doorPos = new net.minecraft.core.BlockPos(wallX, y, wallZ - 2);
		var placed = helper.getLevel().getBlockState(doorPos);
		if (!placed.is(net.minecraft.world.level.block.Blocks.OAK_DOOR)) {
			helper.fail("door not placed (found " + placed.getBlock() + ")");
			return;
		}
		Villager villager = helper.spawn(EntityTypes.VILLAGER, 3, y + 2, 3);
		// Target is outside the pen, beyond the door.
		StalkerBrain.setTarget(villager, new net.minecraft.world.phys.Vec3(wallX + 0.5, y, wallZ - 2.5), 1.0F);
		helper.runAfterDelay(70, () -> {
			var state = helper.getLevel().getBlockState(doorPos);
			if (!state.is(net.minecraft.world.level.block.Blocks.OAK_DOOR)) {
				helper.fail("door missing (block: " + state.getBlock() + ")");
				return;
			}
			if (!state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) {
				helper.fail("villager never opened the door on its path");
				return;
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 60)
	public void spawnRingSpawnsEmployedVillagers(net.minecraft.gametest.framework.GameTestHelper helper) {
		var spawned = io.github.cumsauce.stalker.world.VillagerSpawner.spawnRing(
				helper.getLevel(), helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1.0, 2.5)), 5, helper.getLevel().getRandom());
		helper.runAfterDelay(10, () -> {
			if (spawned.isEmpty()) {
				helper.fail("VillagerSpawner.spawnRing spawned nobody");
				return;
			}
			for (String job : spawned) {
				if (job.equals("none") || job.equals("nitwit")) {
					helper.fail("spawned an excluded profession: " + job);
					return;
				}
			}
			helper.succeed();
		});
	}
}
