package com.spawnproof;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

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
 * @author manchesterjm
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
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spawnproof")
            .executes(context -> showPreview(context, DEFAULT_RADIUS))
            .then(Commands.literal("help")
                .executes(SpawnProofCommand::executeHelp)
            )
            .then(Commands.literal("stop")
                .executes(SpawnProofCommand::executeStop)
            )
            .then(Commands.literal("confirm")
                .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
                    .executes(context -> executeConfirm(context, IntegerArgumentType.getInteger(context, "radius"), false))
                    .then(Commands.literal("fast")
                        .executes(context -> executeConfirm(context, IntegerArgumentType.getInteger(context, "radius"), true))
                    )
                )
            )
            .then(Commands.argument("radius", IntegerArgumentType.integer(MIN_RADIUS, MAX_RADIUS))
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
    private static int showPreview(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();

        // Validate: must be run by a player
        if (!source.isPlayer()) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Could not find player"));
            return 0;
        }

        // Check for existing active task
        if (SpawnProofTask.hasActiveTask(player)) {
            source.sendFailure(Component.literal("You already have a spawnproof task running! Use /spawnproof stop to cancel it."));
            return 0;
        }

        // Determine game mode
        boolean isCreativeMode = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

        // Scan for spawnable blocks
        source.sendSuccess(() -> Component.literal("§eScanning area for spawnable blocks..."), false);
        int spawnableBlocks = countSpawnableBlocks(player, radius);
        int stacks = spawnableBlocks / BUTTONS_PER_STACK;
        int remainder = spawnableBlocks % BUTTONS_PER_STACK;

        // Estimate time
        int safeTimeSeconds = spawnableBlocks / 10;
        int fastTimeSeconds = spawnableBlocks / 200;

        // Show header
        source.sendSuccess(() -> Component.literal("§6=== SpawnProof Preview ==="), false);
        source.sendSuccess(() -> Component.literal("§7Radius: §f" + radius + " blocks"), false);

        if (isCreativeMode) {
            // OP Mode
            source.sendSuccess(() -> Component.literal("§7Mode: §6OP Mode §7(unlimited buttons)"), false);
            source.sendSuccess(() -> Component.literal(""), false);
            source.sendSuccess(() -> Component.literal("§7Spawnable blocks found: §f" + spawnableBlocks), false);
            source.sendSuccess(() -> Component.literal("§7  (" + formatStacks(stacks, remainder) + ")"), false);
        } else {
            // Survival Mode - count ALL button types
            int buttonsInInventory = countAllButtons(player);
            int inventoryStacks = buttonsInInventory / BUTTONS_PER_STACK;
            int inventoryRemainder = buttonsInInventory % BUTTONS_PER_STACK;

            source.sendSuccess(() -> Component.literal("§7Mode: §eSurvival Mode §7(uses inventory)"), false);
            source.sendSuccess(() -> Component.literal(""), false);
            source.sendSuccess(() -> Component.literal("§7Buttons needed: §f" + spawnableBlocks), false);
            source.sendSuccess(() -> Component.literal("§7  (" + formatStacks(stacks, remainder) + ")"), false);
            source.sendSuccess(() -> Component.literal(""), false);
            source.sendSuccess(() -> Component.literal("§7Buttons in inventory: §f" + buttonsInInventory + " §7(any type)"), false);
            source.sendSuccess(() -> Component.literal("§7  (" + formatStacks(inventoryStacks, inventoryRemainder) + ")"), false);

            if (buttonsInInventory < spawnableBlocks) {
                int shortage = spawnableBlocks - buttonsInInventory;
                int shortageStacks = shortage / BUTTONS_PER_STACK;
                int shortageRemainder = shortage % BUTTONS_PER_STACK;

                source.sendSuccess(() -> Component.literal(""), false);
                source.sendSuccess(() -> Component.literal("§c⚠ You may be short by ~" + shortage + " buttons"), false);
                source.sendSuccess(() -> Component.literal("§7  (" + formatStacks(shortageStacks, shortageRemainder) + ")"), false);
                source.sendSuccess(() -> Component.literal("§7  Tip: Warped/Crimson buttons don't burn in the Nether!"), false);
            } else {
                source.sendSuccess(() -> Component.literal(""), false);
                source.sendSuccess(() -> Component.literal("§a✓ You have enough buttons!"), false);
            }
        }

        // Show time estimates
        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§7Estimated time:"), false);
        source.sendSuccess(() -> Component.literal("§7  Safe mode: §f" + formatTime(safeTimeSeconds)), false);
        source.sendSuccess(() -> Component.literal("§7  Fast mode: §f" + formatTime(fastTimeSeconds)), false);

        // Show clickable buttons
        source.sendSuccess(() -> Component.literal(""), false);

        MutableComponent startButton = Component.literal("§a§l[START]")
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/spawnproof confirm " + radius))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Start in safe mode (10 buttons/sec)")))
            );

        MutableComponent fastButton = Component.literal("§e§l[START FAST]")
            .setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/spawnproof confirm " + radius + " fast"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Start in fast mode (200 buttons/sec)")))
            );

        MutableComponent buttons = Component.literal("").append(startButton).append(Component.literal("  ")).append(fastButton);

        MutableComponent cancelButton = Component.literal("§c[CANCEL]")
            .setStyle(Style.EMPTY
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Do nothing to cancel")))
            );
        buttons.append(Component.literal("  ")).append(cancelButton);

        source.sendSuccess(() -> buttons, false);

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
    private static int executeConfirm(CommandContext<CommandSourceStack> context, int radius, boolean fastMode) {
        CommandSourceStack source = context.getSource();

        // Validate: must be run by a player
        if (!source.isPlayer()) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Could not find player"));
            return 0;
        }

        // Check for existing active task
        if (SpawnProofTask.hasActiveTask(player)) {
            source.sendFailure(Component.literal("You already have a spawnproof task running! Use /spawnproof stop to cancel it."));
            return 0;
        }

        // Fast mode warning for dedicated servers
        if (fastMode && source.getServer().isDedicatedServer()) {
            source.sendSuccess(() -> Component.literal("§c⚠ Fast mode on dedicated server - may cause lag for other players"), false);
        }

        // Determine game mode
        boolean isCreativeMode = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

        // Check for buttons in survival mode
        if (!isCreativeMode) {
            int buttonsInInventory = countAllButtons(player);
            if (buttonsInInventory == 0) {
                source.sendFailure(Component.literal("You have no buttons in your inventory!"));
                return 0;
            }
        }

        // Start the task
        String modeText = isCreativeMode ? "OP Mode" : "Survival Mode";
        String speedText = fastMode ? "Fast" : "Safe";
        source.sendSuccess(() -> Component.literal("§aStarting SpawnProof with radius " + radius + "... (" + modeText + ", " + speedText + " speed)"), false);

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
    private static int countSpawnableBlocks(ServerPlayer player, int radius) {
        int count = 0;
        ServerLevel world = (ServerLevel) player.level();
        BlockPos center = player.blockPosition();

        int minY = Math.max(world.getMinY(), center.getY() - radius);
        int maxY = Math.min(world.getMaxY(), center.getY() + radius);

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
    private static boolean isSpawnablePosition(ServerLevel world, BlockPos pos) {
        // Skip unloaded chunks
        if (!world.isLoaded(pos)) {
            return false;
        }

        // Current position must be air (where button goes)
        BlockState stateAt = world.getBlockState(pos);
        if (!stateAt.isAir()) {
            return false;
        }

        // Block above must be air (mob headroom)
        if (!world.getBlockState(pos.above()).isAir()) {
            return false;
        }

        // Block below must have a full solid top surface (mobs can stand on it)
        BlockPos below = pos.below();
        BlockState stateBelow = world.getBlockState(below);

        // Skip bedrock - mobs can't spawn on it
        if (stateBelow.is(Blocks.BEDROCK)) {
            return false;
        }

        if (!stateBelow.isFaceSturdy(world, below, Direction.UP)) {
            return false;
        }

        // Check if FLOOR button can be placed here (not default wall button!)
        BlockState floorButton = Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(ButtonBlock.FACE, AttachFace.FLOOR);
        if (!floorButton.canSurvive(world, pos)) {
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
    private static int countAllButtons(ServerPlayer player) {
        int count = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
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
    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        source.sendSuccess(() -> Component.literal("§6=== SpawnProof Commands ==="), false);
        source.sendSuccess(() -> Component.literal("§e/spawnproof §7- Preview with default radius (" + DEFAULT_RADIUS + ")"), false);
        source.sendSuccess(() -> Component.literal("§e/spawnproof <radius> §7- Preview with custom radius (" + MIN_RADIUS + "-" + MAX_RADIUS + ")"), false);
        source.sendSuccess(() -> Component.literal("§e/spawnproof stop §7- Stop the current task"), false);
        source.sendSuccess(() -> Component.literal("§e/spawnproof help §7- Show this help"), false);
        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§6=== How It Works ==="), false);
        source.sendSuccess(() -> Component.literal("§71. Run §e/spawnproof §7to scan for spawnable blocks"), false);
        source.sendSuccess(() -> Component.literal("§72. Click §a[START] §7or §e[START FAST] §7to begin"), false);
        source.sendSuccess(() -> Component.literal("§73. Run §e/spawnproof stop §7to cancel"), false);
        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§6=== Speed Modes ==="), false);
        source.sendSuccess(() -> Component.literal("§aSafe Mode:§7 10 buttons/sec (server-safe)"), false);
        source.sendSuccess(() -> Component.literal("§eFast Mode:§7 200 buttons/sec (single-player only)"), false);
        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§7Places stone buttons on spawnable surfaces."), false);
        source.sendSuccess(() -> Component.literal("§7Perfect for Wither skeleton farms!"), false);

        return 1;
    }

    /**
     * Executes the /spawnproof stop command.
     *
     * @param context The command context
     * @return 1 if a task was stopped, 0 if no task existed
     */
    private static int executeStop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Validate: must be run by a player
        if (!source.isPlayer()) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Could not find player"));
            return 0;
        }

        // Attempt to stop the task
        if (SpawnProofTask.stopTask(player)) {
            source.sendSuccess(() -> Component.literal("SpawnProof task stopped."), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("No active spawnproof task to stop."));
            return 0;
        }
    }
}
