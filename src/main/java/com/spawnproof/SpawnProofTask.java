package com.spawnproof;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous task that places stone buttons on spawnable surfaces.
 *
 * <p>This class scans all blocks in a spherical area and places buttons
 * on any surface where mobs could spawn. Unlike torch placement which uses
 * a grid, this checks every single block because buttons must cover all
 * spawnable surfaces.</p>
 *
 * <h3>Speed Modes</h3>
 * <ul>
 *   <li><b>Safe Mode:</b> 1 button every 2 ticks (~10/sec) - server safe</li>
 *   <li><b>Fast Mode:</b> 10 buttons per tick (~200/sec) - single-player only</li>
 * </ul>
 *
 * <h3>Game Modes</h3>
 * <ul>
 *   <li><b>Creative Mode (OP):</b> Buttons placed without consuming inventory</li>
 *   <li><b>Survival Mode:</b> Buttons consumed from player inventory as placed</li>
 * </ul>
 *
 * @author Claude Code
 * @version 1.0.0
 * @see SpawnProofCommand
 */
public class SpawnProofTask {

    // =========================================================================
    // Static Fields
    // =========================================================================

    /** Map of active tasks by player UUID. */
    private static final Map<UUID, SpawnProofTask> ACTIVE_TASKS = new HashMap<>();

    /** Buttons placed per tick in safe mode. */
    private static final int BUTTONS_PER_TICK_SAFE = 1;

    /** Buttons placed per tick in fast mode. */
    private static final int BUTTONS_PER_TICK_FAST = 10;

    /** Ticks between placements in safe mode. */
    private static final int TICKS_BETWEEN_SAFE = 2;

    /** Progress update interval. */
    private static final int PROGRESS_UPDATE_INTERVAL = 500;

    /** Buttons per stack (for display). */
    private static final int BUTTONS_PER_STACK = 64;

    // =========================================================================
    // Instance Fields
    // =========================================================================

    /** The player who initiated this task. */
    private final ServerPlayerEntity player;

    /** UUID of the player. */
    private final UUID playerId;

    /** Radius in blocks. */
    private final int radius;

    /** Center position (player's location when task started). */
    private final BlockPos center;

    /** The world to place buttons in. */
    private final ServerWorld world;

    /** Whether player is in creative mode (OP). */
    private final boolean isCreativeMode;

    /** Whether to use fast mode. */
    private final boolean fastMode;

    /** List of positions to place buttons. */
    private List<BlockPos> positions;

    /** Current index in positions list. */
    private int currentIndex = 0;

    /** Total buttons placed so far. */
    private int buttonsPlaced = 0;

    /** Tick counter for rate limiting. */
    private int tickCounter = 0;

    /** System time when task started. */
    private long startTime;

    /** Whether this instance has registered a tick handler. */
    private boolean registered = false;

    /** Last progress update count. */
    private int lastProgressUpdate = 0;

    /** Whether inventory was exhausted (survival mode only). */
    private boolean inventoryExhausted = false;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new SpawnProof task for the specified player.
     *
     * @param player The player who initiated the task
     * @param radius The radius in blocks to spawn-proof
     * @param isCreativeMode Whether the player is in creative/OP mode
     * @param fastMode Whether to use fast placement mode
     */
    public SpawnProofTask(ServerPlayerEntity player, int radius, boolean isCreativeMode, boolean fastMode) {
        this.player = player;
        this.playerId = player.getUuid();
        this.radius = radius;
        this.center = player.getBlockPos();
        this.world = (ServerWorld) player.getEntityWorld();
        this.isCreativeMode = isCreativeMode;
        this.fastMode = fastMode;
    }

    // =========================================================================
    // Public Methods
    // =========================================================================

    /**
     * Starts the spawn-proof task.
     */
    public void start() {
        // Build list of positions to place buttons
        positions = buildPositionList();

        if (positions.isEmpty()) {
            player.sendMessage(Text.literal("§eNo spawnable blocks found in the area!"), false);
            return;
        }

        // Register this task
        ACTIVE_TASKS.put(playerId, this);
        startTime = System.currentTimeMillis();

        player.sendMessage(Text.literal("§7Found §f" + positions.size() + " §7spawnable blocks. Starting..."), false);

        // Register tick handler
        if (!registered) {
            registered = true;
            ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        }
    }

    /**
     * Checks if the specified player has an active task.
     *
     * @param player The player to check
     * @return true if the player has an active task
     */
    public static boolean hasActiveTask(ServerPlayerEntity player) {
        return ACTIVE_TASKS.containsKey(player.getUuid());
    }

    /**
     * Stops the active task for the specified player.
     *
     * @param player The player whose task to stop
     * @return true if a task was stopped
     */
    public static boolean stopTask(ServerPlayerEntity player) {
        SpawnProofTask task = ACTIVE_TASKS.remove(player.getUuid());
        if (task != null) {
            task.player.sendMessage(Text.literal("§eSpawnProof stopped. Placed §f" + task.buttonsPlaced + " §ebuttons."), false);
            return true;
        }
        return false;
    }

    // =========================================================================
    // Private Methods - Tick Handler
    // =========================================================================

    /**
     * Called every server tick to place buttons.
     */
    private void tick() {
        // Check if this task is still active
        if (ACTIVE_TASKS.get(playerId) != this) {
            return;
        }

        // Check if complete
        if (currentIndex >= positions.size()) {
            complete();
            return;
        }

        // Rate limiting for safe mode
        if (!fastMode) {
            tickCounter++;
            if (tickCounter < TICKS_BETWEEN_SAFE) {
                return;
            }
            tickCounter = 0;
        }

        // Place buttons
        int buttonsThisTick = fastMode ? BUTTONS_PER_TICK_FAST : BUTTONS_PER_TICK_SAFE;
        for (int i = 0; i < buttonsThisTick && currentIndex < positions.size(); i++) {
            BlockPos pos = positions.get(currentIndex);
            currentIndex++;

            // Validate position is still valid (may have changed)
            if (isValidPosition(pos)) {
                if (isCreativeMode) {
                    // OP mode: always use stone buttons
                    placeButton(pos, Blocks.STONE_BUTTON);
                    buttonsPlaced++;
                } else {
                    // Survival mode: consume any button from inventory
                    Block buttonBlock = consumeButton();
                    if (buttonBlock != null) {
                        placeButton(pos, buttonBlock);
                        buttonsPlaced++;
                    } else {
                        // Inventory exhausted
                        inventoryExhausted = true;
                        completeExhausted();
                        return;
                    }
                }
            }
        }

        // Progress update
        if (buttonsPlaced - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL) {
            lastProgressUpdate = buttonsPlaced;
            int remaining = positions.size() - currentIndex;
            player.sendMessage(Text.literal("§7Progress: §f" + buttonsPlaced + " §7placed, §f" + remaining + " §7remaining..."), false);
        }
    }

    // =========================================================================
    // Private Methods - Position Building
    // =========================================================================

    /**
     * Builds the list of all spawnable positions in the radius.
     *
     * @return List of positions needing buttons
     */
    private List<BlockPos> buildPositionList() {
        List<BlockPos> list = new ArrayList<>();

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
                        if (isValidPosition(pos)) {
                            list.add(pos);
                        }
                    }
                }
            }
        }

        return list;
    }

    // =========================================================================
    // Private Methods - Validation
    // =========================================================================

    /**
     * Checks if a position is valid for button placement.
     *
     * <p>Note: We only check {@code isSideSolidFullSquare} for the block below,
     * not {@code isSolidBlock}. This catches more spawnable surfaces like soul sand.</p>
     *
     * @param pos The position to check
     * @return true if a button can and should be placed here
     */
    private boolean isValidPosition(BlockPos pos) {
        // Skip unloaded chunks
        if (!world.isChunkLoaded(pos)) {
            return false;
        }

        // Current position must be air
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
    // Private Methods - Button Placement
    // =========================================================================

    /**
     * Places a button at the specified position.
     *
     * @param pos The position to place the button
     * @param buttonBlock The type of button block to place
     */
    private void placeButton(BlockPos pos, Block buttonBlock) {
        // Place the button (floor button facing up)
        BlockState buttonState = buttonBlock.getDefaultState()
            .with(ButtonBlock.FACE, BlockFace.FLOOR);
        world.setBlockState(pos, buttonState);
    }

    /**
     * Consumes one button (any type) from the player's inventory.
     *
     * @return The Block type of the consumed button, or null if none available
     */
    private Block consumeButton() {
        var inventory = player.getInventory();

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (block instanceof ButtonBlock) {
                    stack.decrement(1);
                    if (stack.isEmpty()) {
                        inventory.setStack(i, ItemStack.EMPTY);
                    }
                    return block;
                }
            }
        }

        return null;
    }

    // =========================================================================
    // Private Methods - Completion
    // =========================================================================

    /**
     * Completes the task successfully.
     */
    private void complete() {
        ACTIVE_TASKS.remove(playerId);

        long elapsed = System.currentTimeMillis() - startTime;
        double seconds = elapsed / 1000.0;

        player.sendMessage(Text.literal("§a✓ SpawnProof complete!"), false);
        player.sendMessage(Text.literal("§7  Buttons placed: §f" + buttonsPlaced), false);
        player.sendMessage(Text.literal("§7  Time: §f" + String.format("%.1f", seconds) + " seconds"), false);
    }

    /**
     * Completes the task due to inventory exhaustion.
     */
    private void completeExhausted() {
        ACTIVE_TASKS.remove(playerId);

        int remaining = positions.size() - currentIndex;
        int stacks = remaining / BUTTONS_PER_STACK;
        int remainder = remaining % BUTTONS_PER_STACK;

        player.sendMessage(Text.literal("§c⚠ Ran out of buttons!"), false);
        player.sendMessage(Text.literal("§7  Buttons placed: §f" + buttonsPlaced), false);
        player.sendMessage(Text.literal("§7  Still needed: §f" + remaining + " §7(" + formatStacks(stacks, remainder) + ")"), false);
        player.sendMessage(Text.literal("§7  Craft more buttons and run §e/spawnproof §7again."), false);
    }

    /**
     * Formats stacks and remainder for display.
     */
    private String formatStacks(int stacks, int remainder) {
        if (stacks == 0) {
            return remainder + " buttons";
        } else if (remainder == 0) {
            return stacks + " stack" + (stacks == 1 ? "" : "s");
        } else {
            return stacks + " stack" + (stacks == 1 ? "" : "s") + " + " + remainder;
        }
    }
}
