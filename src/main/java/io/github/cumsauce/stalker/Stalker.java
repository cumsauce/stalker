package io.github.cumsauce.stalker;

import io.github.cumsauce.stalker.bribe.BribeInteract;
import io.github.cumsauce.stalker.command.StalkerCommands;
import io.github.cumsauce.stalker.config.StalkerConfig;
import io.github.cumsauce.stalker.net.Net;
import io.github.cumsauce.stalker.sound.StalkerSounds;
import io.github.cumsauce.stalker.world.StalkerBrain;
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
		// Force StalkerBrain's static initializer here: it registers the custom Activity and
		// memory types into BuiltInRegistries, which Fabric freezes right after mod init.
		// Deferring this to the first villager's brain build would throw "Registry is already frozen".
		StalkerBrain.bootstrap();
		StalkerSounds.bootstrap();
		LOGGER.info("Stalker initialized");
	}
}
