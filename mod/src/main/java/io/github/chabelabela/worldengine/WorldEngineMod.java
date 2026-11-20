package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldEngineMod implements ModInitializer {
    public static final String MOD_ID = "worldengine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** Blocks per face edge of the home planet (ROADMAP §4.4). */
    public static final int HOME_CELLS = 16_384;

    private static NativeCore core; // set once in onInitialize; null if the core is unavailable
    private static NativeCore.Universe universe; // server thread only

    @Override
    public void onInitialize() {
        NativeCore loaded = loadCore();
        core = loaded;
        if (loaded != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                if (universe != null) {
                    universe.close(); // only if a stop event was ever missed
                    universe = null;
                }
                try {
                    universe = loaded.newDemoUniverse();
                } catch (NativeCoreException e) {
                    LOGGER.error("World Engine could not create its universe; the mod stays idle this session", e);
                }
            });
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                if (universe != null) {
                    universe.close();
                    universe = null;
                }
            });
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("worldengine")
                        .then(Commands.literal("bodies").executes(context -> printBodies(context.getSource())))
                        .then(Commands.literal("terrain")
                                .then(Commands.argument("face", IntegerArgumentType.integer(0, 5))
                                        .then(Commands.argument("x", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                                .then(Commands.argument("z", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                                        .executes(context -> printTerrain(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "face"),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "z")))))))));
    }

    /** Loads the Rust core, or logs why it can't and returns null so the game still starts. */
    private static NativeCore loadCore() {
        Path cache = FabricLoader.getInstance().getGameDir().resolve(".worldengine").resolve("natives");
        NativeCore loaded;
        Path library;
        int abi;
        try {
            library = NativeLibraryLoader.locate(cache);
            loaded = NativeCore.load(library);
            abi = loaded.abiVersion();
        } catch (RuntimeException e) {
            // Deliberately RuntimeException, not just NativeCoreException: the core is optional, so any
            // non-fatal failure must degrade to an inactive mod rather than kill the game. Errors still propagate.
            LOGGER.error("World Engine core failed to load on {} {}; the mod stays inactive and /worldengine reports this. "
                    + "Please include this log when reporting the problem.",
                    System.getProperty("os.name"), System.getProperty("os.arch"), e);
            return null;
        }
        try {
            NativeLibraryLoader.pruneStaleCaches(cache, library);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not prune old World Engine native caches", e);
        }
        LOGGER.info("World Engine core loaded: ABI v{} from {}", abi, library);
        return loaded;
    }

    /** Debug: the home planet's terrain column at cell (x, z) of a face, for this world's seed. */
    private static int printTerrain(CommandSourceStack source, int face, int x, int z) {
        if (core == null) {
            source.sendFailure(Component.literal("World Engine core failed to load (see the game log)"));
            return 0;
        }
        long seed = source.getLevel().getSeed();
        TerrainColumns column = core.terrainColumns(seed, HOME_CELLS, PlanetKind.TERRESTRIAL, face, x, z, 1, 1);
        String line = String.format(Locale.ROOT, "home planet, face %d, cell (%d, %d): %d m, %s",
                face, x, z, column.height(0, 0), column.biome(0, 0));
        source.sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int printBodies(CommandSourceStack source) {
        if (universe == null) {
            source.sendFailure(Component.literal(core == null
                    ? "World Engine core failed to load (see the game log)"
                    : "World Engine universe is not running"));
            return 0;
        }
        universe.setTime(source.getLevel().getGameTime() / 20.0);
        for (int i = 0; i < universe.bodyCount(); i++) {
            double[] p = universe.bodyPosition(i);
            String line = String.format(Locale.ROOT, "%s: x=%.1f y=%.1f z=%.1f m, |r|=%.1f m",
                    universe.bodyName(i), p[0], p[1], p[2], Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]));
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
