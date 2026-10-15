package io.github.cumsauce.stalker;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stalker: a villager that follows a player, watches from cover, and sometimes gets them. Server side only.
 */
public final class Stalker implements ModInitializer {
	public static final String MOD_ID = "stalker";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Stalker initialized");
	}
}
