package io.github.cumsauce.stalker;

import io.github.cumsauce.stalker.bribe.BribeInteract;
import io.github.cumsauce.stalker.command.StalkerCommands;
import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.net.Net;
import io.github.cumsauce.stalker.world.StalkerManager;
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
		StalkerConfig.load();
		Net.register();
		StalkerManager.initialize();
		BribeInteract.initialize();
		StalkerCommands.initialize();
		LOGGER.info("Stalker initialized");
	}
}
