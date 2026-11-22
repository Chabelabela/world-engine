package io.github.chabelabela.worldengine;

import io.github.chabelabela.worldengine.level.SeamCrossingHandler;
import io.github.chabelabela.worldengine.level.UniverseBiomeSource;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import io.github.chabelabela.worldengine.level.WorldStamp;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldEngineMod implements ModInitializer {
    public static final String MOD_ID = "worldengine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile NativeCore core; // set once in onInitialize; null if the core is unavailable
    private static NativeCore.Universe universe; // server thread only

    /** The native core, or null if it failed to load. */
    public static NativeCore core() {
        return core;
    }

    /** The running demo universe, or null. Server thread only. */
    static NativeCore.Universe universe() {
        return universe;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        // Always registered, even without the core, so World Engine worlds still load and fail with a clear
        // message instead of an unknown generator type.
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("universe"), UniverseChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, id("universe"), UniverseBiomeSource.CODEC);

        NativeCore loaded = loadCore();
        core = loaded;
        if (loaded != null) {
            SeamCrossingHandler.register();
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                checkWorldStamp(server, loaded);
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
                WorldEngineCommands.register(dispatcher));
    }

    /** Records the terrain version in a new universe world, and warns if an old world's differs. */
    private static void checkWorldStamp(MinecraftServer server, NativeCore core) {
        ServerLevel overworld = server.overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)) {
            return;
        }
        try {
            TerrainStamp current = new TerrainStamp(core.terrainVersion(), core.abiVersion());
            String warning = overworld.getDataStorage().computeIfAbsent(WorldStamp.TYPE).reconcile(current);
            if (warning != null) {
                LOGGER.warn(warning);
            }
        } catch (RuntimeException e) {
            LOGGER.error("World Engine could not check this world's terrain stamp", e);
        }
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
}
