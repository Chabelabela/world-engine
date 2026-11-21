package io.github.chabelabela.worldengine.spike;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.chabelabela.worldengine.WorldEngineMod;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** {@code /we spike …}: the M1b experiment commands. Game masters only, and only with the spike flag. */
final class SpikeCommands {
    /** Ticks a pre-load ticket stays after arrival (plan: 5 s). */
    private static final int TICKET_LINGER_TICKS = 100;

    /** Delayed work (pre-loaded jumps and ticket removals), run on the server thread. */
    private record Pending(long dueTick, Runnable task) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private SpikeCommands() {
    }

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(SpikeCommands::runPending);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("we")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("spike")
                        .then(Commands.literal("setup").executes(context -> setup(context.getSource())))
                        .then(Commands.literal("ship").executes(context -> ship(context.getSource())))
                        .then(Commands.literal("plot").executes(context -> plot(context.getSource())))
                        .then(Commands.literal("e1")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("rounds", IntegerArgumentType.integer(1, 50))
                                                .executes(context -> e1Batch(context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        IntegerArgumentType.getInteger(context, "rounds"))))))
                        .then(Commands.literal("jump")
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(context -> jump(context, 0))
                                        .then(Commands.argument("preloadTicks", IntegerArgumentType.integer(0, 1200))
                                                .executes(context -> jump(context,
                                                        IntegerArgumentType.getInteger(context, "preloadTicks"))))))));
    }

    // ------------------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------------------

    /** Marks the face region's border with glowstone posts every 32 blocks, and reports the plot. */
    private static int setup(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int n = Spike.FACE_REGION_SIZE;
        int posts = 0;
        for (int i = 0; i <= n; i += 32) {
            int c = Math.min(i, n - 1);
            posts += post(level, c, 0) + post(level, c, n - 1) + post(level, 0, c) + post(level, n - 1, c);
        }
        reply(source, String.format(Locale.ROOT,
                "Face region [0, %d)² marked (%d glowstone posts). Shipyard plot at %s, %d×%d. Next: /we spike ship",
                n, posts, Spike.PLOT_ORIGIN.toShortString(), Spike.PLOT_SIZE, Spike.PLOT_SIZE));
        return Command.SINGLE_SUCCESS;
    }

    /** A 3-block glowstone post on the surface at (x, z). */
    private static int post(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        for (int dy = 0; dy < 3; dy++) {
            level.setBlock(new BlockPos(x, y + dy, z), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        return 1;
    }

    // ------------------------------------------------------------------------------------------------
    // Test ship
    // ------------------------------------------------------------------------------------------------

    /** Ship size: 7 wide (x) × 5 tall (y) × 9 long (z). Bow (door) at low z. */
    static final int SHIP_X = 7;
    static final int SHIP_Y = 5;
    static final int SHIP_Z = 9;

    /**
     * Builds the test ship at the plot origin: planks floor and roof, glass walls, and on purpose facing
     * blocks (stairs, a door, a lever) and a block entity (a chest), plus a redstone lamp the lever powers.
     */
    private static int ship(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos o = Spike.PLOT_ORIGIN;
        for (int cx = o.getX() >> 4; cx <= (o.getX() + SHIP_X) >> 4; cx++) {
            for (int cz = o.getZ() >> 4; cz <= (o.getZ() + SHIP_Z) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        // Clear first so the command is repeatable (the chest's contents go too).
        for (int x = 0; x < SHIP_X; x++) {
            for (int y = 0; y < SHIP_Y; y++) {
                for (int z = 0; z < SHIP_Z; z++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        int blocks = 0;
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        for (int x = 0; x < SHIP_X; x++) {
            for (int z = 0; z < SHIP_Z; z++) {
                blocks += put(level, o.offset(x, 0, z), planks);
                blocks += put(level, o.offset(x, SHIP_Y - 1, z), planks);
                boolean wall = x == 0 || x == SHIP_X - 1 || z == 0 || z == SHIP_Z - 1;
                if (wall) {
                    for (int y = 1; y < SHIP_Y - 1; y++) {
                        blocks += put(level, o.offset(x, y, z), glass);
                    }
                }
            }
        }
        // Door in the bow wall (z = 0), facing out (north). Both halves, placed without neighbour updates.
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        put(level, o.offset(3, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        put(level, o.offset(3, 2, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Two seats facing the bow.
        BlockState seat = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        blocks += put(level, o.offset(2, 1, 5), seat);
        blocks += put(level, o.offset(4, 1, 5), seat);
        // A chest (block entity) against the starboard wall.
        blocks += put(level, o.offset(5, 1, 7), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
        // A redstone lamp in the stern wall, and a lever on the lamp's inner face.
        blocks += put(level, o.offset(3, 2, SHIP_Z - 1), Blocks.REDSTONE_LAMP.defaultBlockState());
        blocks += put(level, o.offset(3, 2, SHIP_Z - 2), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, Direction.NORTH));
        reply(source, String.format(Locale.ROOT, "Test ship (%d×%d×%d, %d blocks) built at %s. /we spike plot goes aboard.",
                SHIP_X, SHIP_Y, SHIP_Z, blocks + 2, o.toShortString()));
        return Command.SINGLE_SUCCESS;
    }

    private static int put(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        return 1;
    }

    /** Teleports the player aboard the test ship (no pre-load), like {@code jump} to the plot. */
    private static int plot(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BlockPos o = Spike.PLOT_ORIGIN;
        Vec3 aboard = new Vec3(o.getX() + 3.5, o.getY() + 1, o.getZ() + 3.5);
        return startJump(source, player, aboard, 0);
    }

    // ------------------------------------------------------------------------------------------------
    // E1: same-level jump with optional pre-loading
    // ------------------------------------------------------------------------------------------------

    /** E1's pre-load variants, interleaved within each round so drift (JIT, GC, caches) hits all alike. */
    private static final int[] E1_PRELOADS = {0, 20, 60};
    /** Ticks between one jump's arrival and the next jump's start: summary at 2 s, ticket gone at 5 s. */
    private static final int E1_GAP_TICKS = 200;
    /** The surface end of every E1 jump (the ship end is the plot). */
    private static final Vec3 E1_GROUND = new Vec3(256.5, -60.0, 256.5);

    /**
     * Runs E1 unattended: {@code rounds} rounds of one jump per pre-load variant, alternating between the
     * ground and the ship. Each jump is announced to the player as a chat line {@code [E1] run …} just before
     * the teleport, so the client log pairs each {@code [spike] E1 jump} summary with its variant.
     */
    private static int e1Batch(CommandSourceStack source, ServerPlayer player, int rounds) {
        MinecraftServer server = source.getServer();
        BlockPos o = Spike.PLOT_ORIGIN;
        Vec3 ship = new Vec3(o.getX() + 3.5, o.getY() + 1, o.getZ() + 3.5);
        UUID id = player.getUUID();
        // Warm-up: start on the ground (unlabelled, so the parser ignores it).
        teleport(player, E1_GROUND, 0);
        long at = server.getTickCount() + E1_GAP_TICKS;
        int total = rounds * E1_PRELOADS.length;
        int n = 0;
        for (int round = 0; round < rounds; round++) {
            for (int preload : E1_PRELOADS) {
                n++;
                Vec3 target = n % 2 == 1 ? ship : E1_GROUND;
                String label = String.format(Locale.ROOT, "[E1] run %d/%d preload=%d to=%s",
                        n, total, preload, target == ship ? "ship" : "ground");
                PENDING.add(new Pending(at, () -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(id);
                    if (p != null) {
                        beginJump(server, p, target, preload, label);
                    }
                }));
                at += preload + E1_GAP_TICKS;
            }
        }
        long seconds = (at - server.getTickCount()) / 20;
        reply(source, String.format(Locale.ROOT, "E1 batch: %d jumps for %s, about %d s", total,
                player.getGameProfile().name(), seconds));
        PENDING.add(new Pending(at, () -> WorldEngineMod.LOGGER.info("[spike] E1 batch done")));
        return Command.SINGLE_SUCCESS;
    }

    /** One labelled jump: pre-load ticket now, label and teleport after {@code preloadTicks}. */
    private static void beginJump(MinecraftServer server, ServerPlayer player, Vec3 target, int preloadTicks, String label) {
        ServerLevel level = player.level();
        ChunkPos chunk = ChunkPos.containing(BlockPos.containing(target));
        int radius = server.getPlayerList().getViewDistance();
        long now = server.getTickCount();
        UUID id = player.getUUID();
        Runnable go = () -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(Component.literal(label));
                WorldEngineMod.LOGGER.info("[spike] {}", label);
                teleport(p, target, preloadTicks);
            }
        };
        if (preloadTicks > 0) {
            level.getChunkSource().addTicketWithRadius(Spike.preloadTicket, chunk, radius);
            PENDING.add(new Pending(now + preloadTicks, go));
            PENDING.add(new Pending(now + preloadTicks + TICKET_LINGER_TICKS,
                    () -> level.getChunkSource().removeTicketWithRadius(Spike.preloadTicket, chunk, radius)));
        } else {
            go.run();
        }
    }

    private static int jump(CommandContext<CommandSourceStack> context, int preloadTicks) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Vec3 target = Vec3Argument.getVec3(context, "pos");
        return startJump(source, source.getPlayerOrException(), target, preloadTicks);
    }

    /**
     * Teleports {@code player} within its level to {@code target}, keeping velocity and look. With
     * {@code preloadTicks > 0}, a ticket loads the destination first and the jump happens that many ticks later;
     * the ticket goes 5 s after arrival.
     */
    private static int startJump(CommandSourceStack source, ServerPlayer player, Vec3 target, int preloadTicks) {
        MinecraftServer server = source.getServer();
        ServerLevel level = player.level();
        ChunkPos chunk = ChunkPos.containing(BlockPos.containing(target));
        int radius = server.getPlayerList().getViewDistance();
        long now = server.getTickCount();
        UUID id = player.getUUID();
        if (preloadTicks > 0) {
            level.getChunkSource().addTicketWithRadius(Spike.preloadTicket, chunk, radius);
            WorldEngineMod.LOGGER.info("[spike] preload ticket at chunk {} radius {} (tick {}), jump in {} ticks",
                    chunk, radius, now, preloadTicks);
            PENDING.add(new Pending(now + preloadTicks, () -> {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) {
                    teleport(p, target, preloadTicks);
                }
            }));
            PENDING.add(new Pending(now + preloadTicks + TICKET_LINGER_TICKS,
                    () -> level.getChunkSource().removeTicketWithRadius(Spike.preloadTicket, chunk, radius)));
            reply(source, String.format(Locale.ROOT, "Pre-loading %s (radius %d); jumping in %d ticks",
                    chunk, radius, preloadTicks));
        } else {
            teleport(player, target, 0);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void teleport(ServerPlayer player, Vec3 target, int preloadTicks) {
        Vec3 from = player.position();
        // DELTA keeps the current velocity, ROTATION with zero offsets keeps the look direction.
        boolean ok = player.teleportTo(player.level(), target.x, target.y, target.z,
                Relative.union(Relative.DELTA, Relative.ROTATION), 0.0F, 0.0F, false);
        WorldEngineMod.LOGGER.info("[spike] jump {} -> {} preload={} ok={} tick={} distance={}",
                fmt(from), fmt(target), preloadTicks, ok, player.level().getServer().getTickCount(),
                Math.round(from.distanceTo(target)));
    }

    private static void runPending(MinecraftServer server) {
        long now = server.getTickCount();
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (pending.dueTick() <= now) {
                due.add(pending.task());
                it.remove();
            }
        }
        due.forEach(Runnable::run);
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    private static void reply(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), true);
    }
}
