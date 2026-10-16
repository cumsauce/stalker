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
