package com.spawnproof;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Command handler for the /spawnproof command.
 *
 * <p>Registers and handles all SpawnProof-related commands:</p>
 * <ul>
 *   <li>{@code /spawnproof} - Preview with default radius (128 blocks)</li>
 *   <li>{@code /spawnproof <radius>} - Preview with custom radius</li>
 *   <li>{@code /spawnproof stop} - Stop the current task</li>
 *   <li>{@code /spawnproof help} - Show available commands</li>
 * </ul>
 *
 * <h3>Game Modes</h3>
 * <ul>
 *   <li><b>OP Players (Level 2+):</b> Creative mode - unlimited buttons</li>
 *   <li><b>Regular Players:</b> Survival mode - uses buttons from inventory</li>
 * </ul>
 *
 * <h3>Speed Modes</h3>
 * <ul>
 *   <li><b>Safe Mode:</b> 10 buttons/second - for multiplayer servers</li>
 *   <li><b>Fast Mode:</b> 200 buttons/second - single-player only</li>
 * </ul>
 *
 * @author Claude Code
 * @version 1.0.0
 * @see SpawnProofTask
 */
public class SpawnProofCommand {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Default radius when no argument is provided. */
    private static final int DEFAULT_RADIUS = 128;

    /** Maximum allowed radius. */
    private static final int MAX_RADIUS = 128;

    /** Minimum allowed radius. */
    private static final int MIN_RADIUS = 8;

    /** Number of buttons per stack. */
    private static final int BUTTONS_PER_STACK = 64;

    // =========================================================================
    // Command Registration
    // =========================================================================

    /**
     * Registers the /spawnproof command with the server's command dispatcher.
     *
     * @param dispatcher The server's command dispatcher
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("spawnproof")
            .executes(context -> showPreview(context, DEFAULT_RADIUS))
            .then(CommandManager.literal("help")
                .executes(SpawnProofCommand::executeHelp)
            )
            .then(CommandManager.literal("stop")
                .executes(SpawnProofCommand::executeStop)
            )
            .then(CommandManager.literal("confirm")
                .then(CommandManager.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                    .executes(context -> executeConfirm(context, IntegerArgumentType.getInteger(context, "radius"), false))
                    .then(CommandManager.literal("fast")
                        .executes(context -> executeConfirm(context, IntegerArgumentType.getInteger(context, "radius"), true))
                    )
                )
            )
            .then(CommandManager.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                .executes(context -> showPreview(context, IntegerArgumentType.getInteger(context, "radius")))
            )
        );
    }

    // =========================================================================
    // Command Handlers
    // =========================================================================

    /**
     * Shows a preview of the spawn-proof task with button counts and clickable START buttons.
     *
     * @param context The command context
     * @param radius The radius to spawn-proof
     * @return 1 on success, 0 on failure
     */
    private static int showPreview(CommandContext<ServerCommandSource> context, int radius) {
        ServerCommandSource source = context.getSource();

        // Validate: must be run by a player
        if (!source.isExecutedByPlayer()) {
            source.sendError(Text.literal("This command must be run by a player"));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Could not find player"));
            return 0;
        }

        // Check for existing active task
        if (SpawnProofTask.hasActiveTask(player)) {
            source.sendError(Text.literal("You already have a spawnproof task running! Use /spawnproof stop to cancel it."));
            return 0;
        }

        // Determine game mode
        boolean isCreativeMode = source.getPermissions().hasPermission(
            new Permission.Level(PermissionLevel.GAMEMASTERS));

        // Scan for spawnable blocks
        source.sendFeedback(() -> Text.literal("§eScanning area for spawnable blocks..."), false);
        int spawnableBlocks = countSpawnableBlocks(player, radius);
        int stacks = spawnableBlocks / BUTTONS_PER_STACK;
        int remainder = spawnableBlocks % BUTTONS_PER_STACK;

        // Estimate time
        int safeTimeSeconds = spawnableBlocks / 10;
        int fastTimeSeconds = spawnableBlocks / 200;

        // Show header
        source.sendFeedback(() -> Text.literal("§6=== SpawnProof Preview ==="), false);
        source.sendFeedback(() -> Text.literal("§7Radius: §f" + radius + " blocks"), false);

        if (isCreativeMode) {
            // OP Mode
            source.sendFeedback(() -> Text.literal("§7Mode: §6OP Mode §7(unlimited buttons)"), false);
            source.sendFeedback(() -> Text.literal(""), false);
            source.sendFeedback(() -> Text.literal("§7Spawnable blocks found: §f" + spawnableBlocks), false);
            source.sendFeedback(() -> Text.literal("§7  (" + formatStacks(stacks, remainder) + ")"), false);
        } else {
            // Survival Mode - count ALL button types
            int buttonsInInventory = countAllButtons(player);
            int inventoryStacks = buttonsInInventory / BUTTONS_PER_STACK;
            int inventoryRemainder = buttonsInInventory % BUTTONS_PER_STACK;

            source.sendFeedback(() -> Text.literal("§7Mode: §eSurvival Mode §7(uses inventory)"), false);
            source.sendFeedback(() -> Text.literal(""), false);
            source.sendFeedback(() -> Text.literal("§7Buttons needed: §f" + spawnableBlocks), false);
            source.sendFeedback(() -> Text.literal("§7  (" + formatStacks(stacks, remainder) + ")"), false);
            source.sendFeedback(() -> Text.literal(""), false);
            source.sendFeedback(() -> Text.literal("§7Buttons in inventory: §f" + buttonsInInventory + " §7(any type)"), false);
            source.sendFeedback(() -> Text.literal("§7  (" + formatStacks(inventoryStacks, inventoryRemainder) + ")"), false);

            if (buttonsInInventory < spawnableBlocks) {
                int shortage = spawnableBlocks - buttonsInInventory;
                int shortageStacks = shortage / BUTTONS_PER_STACK;
                int shortageRemainder = shortage % BUTTONS_PER_STACK;

                source.sendFeedback(() -> Text.literal(""), false);
                source.sendFeedback(() -> Text.literal("§c⚠ You may be short by ~" + shortage + " buttons"), false);
                source.sendFeedback(() -> Text.literal("§7  (" + formatStacks(shortageStacks, shortageRemainder) + ")"), false);
                source.sendFeedback(() -> Text.literal("§7  Tip: Warped/Crimson buttons don't burn in the Nether!"), false);
            } else {
                source.sendFeedback(() -> Text.literal(""), false);
                source.sendFeedback(() -> Text.literal("§a✓ You have enough buttons!"), false);
            }
        }

        // Show time estimates
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§7Estimated time:"), false);
        source.sendFeedback(() -> Text.literal("§7  Safe mode: §f" + formatTime(safeTimeSeconds)), false);
        source.sendFeedback(() -> Text.literal("§7  Fast mode: §f" + formatTime(fastTimeSeconds)), false);

        // Show clickable buttons
        source.sendFeedback(() -> Text.literal(""), false);

        MutableText startButton = Text.literal("§a§l[START]")
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/spawnproof confirm " + radius))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Start in safe mode (10 buttons/sec)")))
            );

        MutableText fastButton = Text.literal("§e§l[START FAST]")
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/spawnproof confirm " + radius + " fast"))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Start in fast mode (200 buttons/sec)")))
            );

        MutableText buttons = Text.literal("").append(startButton).append(Text.literal("  ")).append(fastButton);

        MutableText cancelButton = Text.literal("§c[CANCEL]")
            .setStyle(Style.EMPTY
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Do nothing to cancel")))
            );
        buttons.append(Text.literal("  ")).append(cancelButton);

        source.sendFeedback(() -> buttons, false);

        return 1;
    }

    /**
     * Actually starts the spawn-proof task (called from clickable button).
     *
     * @param context The command context
     * @param radius The radius to spawn-proof
     * @param fastMode Whether to use fast mode (single-player only)
     * @return 1 on success, 0 on failure
     */
    private static int executeConfirm(CommandContext<ServerCommandSource> context, int radius, boolean fastMode) {
        ServerCommandSource source = context.getSource();

        // Validate: must be run by a player
        if (!source.isExecutedByPlayer()) {
            source.sendError(Text.literal("This command must be run by a player"));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Could not find player"));
            return 0;
        }

        // Check for existing active task
        if (SpawnProofTask.hasActiveTask(player)) {
            source.sendError(Text.literal("You already have a spawnproof task running! Use /spawnproof stop to cancel it."));
            return 0;
        }

        // Fast mode warning for dedicated servers
        if (fastMode && source.getServer().isDedicated()) {
            source.sendFeedback(() -> Text.literal("§c⚠ Fast mode on dedicated server - may cause lag for other players"), false);
        }

        // Determine game mode
        boolean isCreativeMode = source.getPermissions().hasPermission(
            new Permission.Level(PermissionLevel.GAMEMASTERS));

        // Check for buttons in survival mode
        if (!isCreativeMode) {
            int buttonsInInventory = countAllButtons(player);
            if (buttonsInInventory == 0) {
                source.sendError(Text.literal("You have no buttons in your inventory!"));
                return 0;
            }
        }

        // Start the task
        String modeText = isCreativeMode ? "OP Mode" : "Survival Mode";
        String speedText = fastMode ? "Fast" : "Safe";
        source.sendFeedback(() -> Text.literal("§aStarting SpawnProof with radius " + radius + "... (" + modeText + ", " + speedText + " speed)"), false);

        SpawnProofTask task = new SpawnProofTask(player, radius, isCreativeMode, fastMode);
        task.start();

        return 1;
    }

    // =========================================================================
    // Helper Methods - Scanning
    // =========================================================================

    /**
     * Counts spawnable blocks in the given radius.
     *
     * @param player The player (for world access and position)
     * @param radius The radius in blocks
     * @return Count of spawnable blocks
     */
    private static int countSpawnableBlocks(ServerPlayerEntity player, int radius) {
        int count = 0;
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        BlockPos center = player.getBlockPos();

        int minY = Math.max(world.getBottomY(), center.getY() - radius);
        int maxY = Math.min(world.getTopYInclusive(), center.getY() + radius);

        for (int y = minY; y <= maxY; y++) {
            for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    // Spherical distance check
                    double dx = x - center.getX();
                    double dy = y - center.getY();
                    double dz = z - center.getZ();
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (isSpawnablePosition(world, pos)) {
                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    /**
     * Checks if a position is spawnable (needs a button).
     *
     * <p>A position is spawnable if:</p>
     * <ul>
     *   <li>The block below has a solid full top surface (mobs can stand on it)</li>
     *   <li>The current position is air</li>
     *   <li>The position above is air (mob headroom)</li>
     * </ul>
     *
     * <p>Note: We only check {@code isSideSolidFullSquare} for the block below,
     * not {@code isSolidBlock}. This is because some spawnable blocks (soul sand,
     * certain variants) may not be full solid cubes but still allow mob spawning.</p>
     *
     * @param world The world to check in
     * @param pos The position to check (where button would go)
     * @return true if this position is spawnable
     */
    private static boolean isSpawnablePosition(ServerWorld world, BlockPos pos) {
        // Skip unloaded chunks
        if (!world.isChunkLoaded(pos)) {
            return false;
        }

        // Current position must be air (where button goes)
        BlockState stateAt = world.getBlockState(pos);
        if (!stateAt.isAir()) {
            return false;
        }

        // Block above must be air (mob headroom)
        if (!world.getBlockState(pos.up()).isAir()) {
            return false;
        }

        // Block below must have a full solid top surface (mobs can stand on it)
        BlockPos below = pos.down();
        BlockState stateBelow = world.getBlockState(below);

        // Skip bedrock - mobs can't spawn on it
        if (stateBelow.isOf(Blocks.BEDROCK)) {
            return false;
        }

        if (!stateBelow.isSideSolidFullSquare(world, below, Direction.UP)) {
            return false;
        }

        // Check if FLOOR button can be placed here (not default wall button!)
        BlockState floorButton = Blocks.STONE_BUTTON.getDefaultState()
            .with(ButtonBlock.FACE, BlockFace.FLOOR);
        if (!floorButton.canPlaceAt(world, pos)) {
            return false;
        }

        return true;
    }

    // =========================================================================
    // Helper Methods - Button Detection
    // =========================================================================

    /**
     * Counts all buttons of any type in the player's inventory.
     *
     * @param player The player whose inventory to check
     * @return Total count of all button items
     */
    private static int countAllButtons(ServerPlayerEntity player) {
        int count = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isButton(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Checks if an ItemStack is a button (any type).
     *
     * @param stack The ItemStack to check
     * @return true if the item is a button
     */
    private static boolean isButton(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            return blockItem.getBlock() instanceof ButtonBlock;
        }
        return false;
    }

    // =========================================================================
    // Helper Methods - Formatting
    // =========================================================================

    /**
     * Formats a button count as stacks and remainder.
     *
     * @param stacks Number of full stacks
     * @param remainder Extra buttons beyond full stacks
     * @return Formatted string like "3 stacks + 12"
     */
    private static String formatStacks(int stacks, int remainder) {
        if (stacks == 0) {
            return remainder + " buttons";
        } else if (remainder == 0) {
            return stacks + " stack" + (stacks == 1 ? "" : "s");
        } else {
            return stacks + " stack" + (stacks == 1 ? "" : "s") + " + " + remainder;
        }
    }

    /**
     * Formats seconds as a human-readable time string.
     *
     * @param seconds Total seconds
     * @return Formatted string like "1h 30m" or "45s"
     */
    private static String formatTime(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            int mins = seconds / 60;
            int secs = seconds % 60;
            return secs > 0 ? mins + "m " + secs + "s" : mins + "m";
        } else {
            int hours = seconds / 3600;
            int mins = (seconds % 3600) / 60;
            return mins > 0 ? hours + "h " + mins + "m" : hours + "h";
        }
    }

    /**
     * Executes the /spawnproof help command.
     *
     * @param context The command context
     * @return 1 (always succeeds)
     */
    private static int executeHelp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendFeedback(() -> Text.literal("§6=== SpawnProof Commands ==="), false);
        source.sendFeedback(() -> Text.literal("§e/spawnproof §7- Preview with default radius (" + DEFAULT_RADIUS + ")"), false);
        source.sendFeedback(() -> Text.literal("§e/spawnproof <radius> §7- Preview with custom radius (" + MIN_RADIUS + "-" + MAX_RADIUS + ")"), false);
        source.sendFeedback(() -> Text.literal("§e/spawnproof stop §7- Stop the current task"), false);
        source.sendFeedback(() -> Text.literal("§e/spawnproof help §7- Show this help"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§6=== How It Works ==="), false);
        source.sendFeedback(() -> Text.literal("§71. Run §e/spawnproof §7to scan for spawnable blocks"), false);
        source.sendFeedback(() -> Text.literal("§72. Click §a[START] §7or §e[START FAST] §7to begin"), false);
        source.sendFeedback(() -> Text.literal("§73. Run §e/spawnproof stop §7to cancel"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§6=== Speed Modes ==="), false);
        source.sendFeedback(() -> Text.literal("§aSafe Mode:§7 10 buttons/sec (server-safe)"), false);
        source.sendFeedback(() -> Text.literal("§eFast Mode:§7 200 buttons/sec (single-player only)"), false);
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§7Places stone buttons on spawnable surfaces."), false);
        source.sendFeedback(() -> Text.literal("§7Perfect for Wither skeleton farms!"), false);

        return 1;
    }

    /**
     * Executes the /spawnproof stop command.
     *
     * @param context The command context
     * @return 1 if a task was stopped, 0 if no task existed
     */
    private static int executeStop(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        // Validate: must be run by a player
        if (!source.isExecutedByPlayer()) {
            source.sendError(Text.literal("This command must be run by a player"));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Could not find player"));
            return 0;
        }

        // Attempt to stop the task
        if (SpawnProofTask.stopTask(player)) {
            source.sendFeedback(() -> Text.literal("SpawnProof task stopped."), false);
            return 1;
        } else {
            source.sendError(Text.literal("No active spawnproof task to stop."));
            return 0;
        }
    }
}
