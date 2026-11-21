package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** The {@code /worldengine} debug commands. Game masters (operator level 2) only. */
final class WorldEngineCommands {
    private static final int HOME_CELLS = Planets.HOME.cells();

    private WorldEngineCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("worldengine")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("bodies").executes(context -> bodies(context.getSource())))
                .then(Commands.literal("where").executes(context -> where(context.getSource())))
                .then(Commands.literal("goto")
                        .then(Commands.argument("planet", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Planets.ALL.stream().map(PlanetSpec::name), builder))
                                .executes(context -> goTo(
                                        context.getSource(), StringArgumentType.getString(context, "planet")))))
                .then(Commands.literal("terrain")
                        .then(Commands.argument("face", IntegerArgumentType.integer(0, 5))
                                .then(Commands.argument("x", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                        .then(Commands.argument("z", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                                .executes(context -> terrain(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "face"),
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z"))))))));
    }

    /** Which planet, face and cell the executor stands on, and that column's terrain. */
    private static int where(CommandSourceStack source) {
        Vec3 position = source.getPosition();
        int x = Mth.floor(position.x());
        int z = Mth.floor(position.z());
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            reply(source, String.format(Locale.ROOT, "(%d, %d): empty space", x, z));
            return Command.SINGLE_SUCCESS;
        }
        if (!coreLoaded(source)) {
            return 0;
        }
        TerrainColumns column = PlanetTerrain.column(hit, source.getLevel().getSeed());
        FaceCell cell = hit.cell();
        String note = source.getServer().overworld().getChunkSource().getGenerator() instanceof UniverseChunkGenerator
                ? "" : " (layout only: this world isn't a World Engine universe)";
        reply(source, String.format(Locale.ROOT, "(%d, %d): %s, face %s, cell (%d, %d), %d m, %s%s",
                x, z, hit.planet().name(), cell.face(), cell.x(), cell.z(), column.height(0, 0), column.biome(0, 0), note));
        return Command.SINGLE_SUCCESS;
    }

    /** Teleports the player to dry land near {@code name}'s E1 centre. */
    private static int goTo(CommandSourceStack source, String name) throws CommandSyntaxException {
        PlanetSpec planet = Planets.byName(name);
        if (planet == null) {
            source.sendFailure(Component.literal("Unknown planet " + name));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        if (!(level.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)) {
            source.sendFailure(Component.literal("This world isn't a World Engine universe"));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        int[] chunk = PlanetTerrain.landChunk(planet, level.getSeed());
        int x = chunk[0] * 16 + 8;
        int z = chunk[1] * 16 + 8;
        level.getChunk(chunk[0], chunk[1]);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        reply(source, String.format(Locale.ROOT, "Sent to %s at (%d, %d, %d)", planet.name(), x, y, z));
        return Command.SINGLE_SUCCESS;
    }

    /** The home planet's terrain at cell (x, z) of a face, for this world's seed. */
    private static int terrain(CommandSourceStack source, int face, int x, int z) {
        if (!coreLoaded(source)) {
            return 0;
        }
        PlanetHit hit = new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.fromId(face), x, z));
        TerrainColumns column = PlanetTerrain.column(hit, source.getLevel().getSeed());
        reply(source, String.format(Locale.ROOT, "home, face %s, cell (%d, %d): %d m, %s",
                PlanetFace.fromId(face), x, z, column.height(0, 0), column.biome(0, 0)));
        return Command.SINGLE_SUCCESS;
    }

    private static int bodies(CommandSourceStack source) {
        NativeCore.Universe universe = WorldEngineMod.universe();
        if (universe == null) {
            source.sendFailure(Component.literal(WorldEngineMod.core() == null
                    ? "World Engine core failed to load (see the game log)"
                    : "World Engine universe is not running"));
            return 0;
        }
        universe.setTime(source.getLevel().getGameTime() / 20.0);
        for (int i = 0; i < universe.bodyCount(); i++) {
            double[] p = universe.bodyPosition(i);
            reply(source, String.format(Locale.ROOT, "%s: x=%.1f y=%.1f z=%.1f m, |r|=%.1f m",
                    universe.bodyName(i), p[0], p[1], p[2], Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static boolean coreLoaded(CommandSourceStack source) {
        if (WorldEngineMod.core() == null) {
            source.sendFailure(Component.literal("World Engine core failed to load (see the game log)"));
            return false;
        }
        return true;
    }

    private static void reply(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
