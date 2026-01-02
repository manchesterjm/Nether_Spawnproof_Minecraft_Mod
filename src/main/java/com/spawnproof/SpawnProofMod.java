package com.spawnproof;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SpawnProof Mod - Spawn-proofs large areas by placing stone buttons.
 *
 * <p>This mod provides the /spawnproof command which scans an area for spawnable
 * surfaces and places stone buttons on them to prevent mob spawning. Ideal for
 * creating efficient Wither skeleton farms in the Nether.</p>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Scans for actual spawnable surfaces (solid block + 2 air blocks above)</li>
 *   <li>Places stone buttons on all spawnable blocks</li>
 *   <li>Supports survival mode (uses buttons from inventory)</li>
 *   <li>Fast mode for single-player worlds</li>
 *   <li>Progress updates every 500 buttons</li>
 * </ul>
 *
 * @author Claude Code
 * @version 1.0.0
 */
public class SpawnProofMod implements ModInitializer {

    /** Mod identifier used for logging. */
    public static final String MOD_ID = "spawnproof";

    /** Logger instance for this mod. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Initializes the mod and registers the /spawnproof command.
     */
    @Override
    public void onInitialize() {
        LOGGER.info("SpawnProof mod initializing...");

        // Register the /spawnproof command
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SpawnProofCommand.register(dispatcher);
        });

        LOGGER.info("SpawnProof mod initialized - use /spawnproof to spawn-proof an area");
    }
}
