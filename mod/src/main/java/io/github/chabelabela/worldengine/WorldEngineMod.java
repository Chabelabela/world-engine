package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
import java.io.UncheckedIOException;
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

    private static NativeCore core; // set once in onInitialize; null if the core is unavailable
    private static NativeCore.Universe universe; // server thread only

    @Override
    public void onInitialize() {
        core = loadCore();
        if (core != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                if (universe != null) {
                    universe.close(); // only if a stop event was ever missed
                }
                universe = core.newDemoUniverse();
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
                        .then(Commands.literal("bodies").executes(context -> printBodies(context.getSource())))));
    }

    /** Loads the Rust core, or logs why it can't and returns null so the game still starts. */
    private static NativeCore loadCore() {
        Path cache = FabricLoader.getInstance().getGameDir().resolve(".worldengine").resolve("natives");
        try {
            Path library = NativeLibraryLoader.locate(cache);
            NativeCore loaded = NativeCore.load(library);
            NativeLibraryLoader.pruneStaleCaches(cache, library);
            LOGGER.info("World Engine core loaded: ABI v{} from {}", loaded.abiVersion(), library);
            return loaded;
        } catch (NativeCoreException | UncheckedIOException e) {
            LOGGER.error("World Engine core unavailable, so the mod stays inactive: {}", e.getMessage());
            return null;
        }
    }

    public static boolean isCoreAvailable() {
        return core != null;
    }

    private static int printBodies(CommandSourceStack source) {
        if (universe == null) {
            source.sendFailure(Component.literal(core == null
                    ? "World Engine core is unavailable on this platform (see the log)"
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
