package io.github.cumsauce.stalker.mixin;

import io.github.cumsauce.stalker.world.Attachments;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Silences a bound villager: while it stalks, it does not hum (plan §5). The mod's own pitched
 * ambient cue is played positionally from the manager instead, so the villager's voice is heard
 * only on the mod's terms. Hurt and death sounds are untouched.
 */
@Mixin(Villager.class)
public abstract class VillagerSilenceMixin {

	@Inject(method = "getAmbientSound()Lnet/minecraft/sounds/SoundEvent;", at = @At("HEAD"), cancellable = true)
	private void stalker$silenceWhileBound(CallbackInfoReturnable<SoundEvent> ci) {
		if (Attachments.isBound((Villager) (Object) this)) {
			ci.setReturnValue(null);
		}
	}
}
