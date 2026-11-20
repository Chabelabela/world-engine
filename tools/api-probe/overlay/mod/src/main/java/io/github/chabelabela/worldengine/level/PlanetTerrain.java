package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.SpawnSearch;

/**
 * Terrain samples for world generation, from the native core.
 *
 * <p>Failure policy: if the core is missing or a call fails, throw. On a worldgen thread that crashes the
 * server with this message instead of saving a hole or a half-written chunk into the world for good.
 */
public final class PlanetTerrain {
    private PlanetTerrain() {
    }

    /** The {@code countX} by {@code countZ} columns whose north-west cell is {@code hit}'s cell. */
    public static TerrainColumns sample(PlanetHit hit, long worldSeed, int countX, int countZ) {
        PlanetSpec planet = hit.planet();
        FaceCell cell = hit.cell();
        try {
            return core().terrainColumns(planet.seed(worldSeed), planet.cells(), planet.kind(), cell.face().id(),
                    cell.x(), cell.z(), countX, countZ);
        } catch (NativeCoreException e) {
            throw new IllegalStateException("World Engine terrain failed on " + planet.name() + " face " + cell.face()
                    + " at cell (" + cell.x() + ", " + cell.z() + ")", e);
        }
    }

    /** One column. */
    public static TerrainColumns column(PlanetHit hit, long worldSeed) {
        return sample(hit, worldSeed, 1, 1);
    }

    /** {chunkX, chunkZ} of dry land near {@code planet}'s E1 centre; see {@link SpawnSearch}. */
    public static int[] landChunk(PlanetSpec planet, long worldSeed) {
        NativeCore core = core();
        long seed = planet.seed(worldSeed);
        return SpawnSearch.findLandChunk(planet, (face, x, z) ->
                core.terrainColumns(seed, planet.cells(), planet.kind(), face.id(), x, z, 1, 1).height(0, 0),
                SpawnSearch.MAX_RADIUS_STEPS);
    }

    static NativeCore core() {
        NativeCore core = WorldEngineMod.core();
        if (core == null) {
            throw new IllegalStateException("World Engine's native core failed to load (see the log above), "
                    + "and a World Engine world can't be generated without it");
        }
        return core;
    }
}
