package io.github.cumsauce.stalker.sound;

import io.github.cumsauce.foley.api.FoleyApi;
import io.github.cumsauce.foley.api.FoleyEntrypoint;

/**
 * Stalker's {@code foley} entrypoint (declared in {@code fabric.mod.json}). Foley invokes it once,
 * client-side, inside the registration window; being an entrypoint, this class is only ever loaded
 * when Foley itself is present — a soft dependency by construction, so it needs no hard
 * {@code depends} on {@code foley}.
 */
public final class FoleyVoicesEntrypoint implements FoleyEntrypoint {

	@Override
	public void onFoleyReady(FoleyApi api) {
		StalkerFoley.register(api);
	}
}
