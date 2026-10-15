package io.github.cumsauce.stalker.mixin;

import io.github.cumsauce.stalker.world.StalkerBrain;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the stalker movement activity to every villager brain at build time.
 *
 * <p>{@code Villager#registerBrainGoals} is the only per-instance brain hook in 26.3 — it runs on
 * {@code makeBrain} and on {@code refreshBrain(ServerLevel)} (profession/baby changes), so injecting
 * at its head covers every brain (re)build. The injected call forces {@link StalkerBrain}'s static
 * initializer, which registers the custom Activity and memory module types before the brain uses them.
 */
@Mixin(Villager.class)
public abstract class VillagerBrainMixin {

	@Inject(
		method = "registerBrainGoals(Lnet/minecraft/world/entity/ai/Brain;)V",
		at = @At("HEAD")
	)
	private void stalker$addStalkingActivity(Brain<Villager> brain, CallbackInfo ci) {
		StalkerBrain.injectIntoBrain(brain);
	}
}
