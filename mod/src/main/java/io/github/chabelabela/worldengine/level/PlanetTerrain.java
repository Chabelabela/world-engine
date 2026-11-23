package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import io.github.chabelabela.worldengine.planet.SpawnSearch;

/**
 * Terrain samples for world generation, from the native core. A column in an overlap band has its twin's terrain
 * ({@link SeamBands}), so the band copies the neighbouring face exactly.
 *
 * <p>Failure policy: if the core is missing or a call fails, throw. On a worldgen thread that crashes the
 * server with this message instead of saving a hole or a half-written chunk into the world for good.
 */
public final class PlanetTerrain {
    private static volatile SeamBands bands;

    /** A chunk's 16x16 columns: the planet's kind, and each column's height and biome. */
    public static final class ChunkTerrain {
        private final PlanetKind kind;
        private final TerrainColumns columns;
        private final int[] index; // band column dz * 16 + dx -> twin column, or null on a face

        private ChunkTerrain(PlanetKind kind, TerrainColumns columns, int[] index) {
            this.kind = kind;
            this.columns = columns;
            this.index = index;
        }

        public PlanetKind kind() {
            return kind;
        }

        public int height(int dx, int dz) {
            int i = index == null ? dz * 16 + dx : index[dz * 16 + dx];
            return columns.height(i % 16, i / 16);
        }

        public PlanetBiome biome(int dx, int dz) {
            int i = index == null ? dz * 16 + dx : index[dz * 16 + dx];
            return columns.biome(i % 16, i / 16);
        }
    }

    /** One column's planet kind, height and biome. */
    public record ColumnTerrain(PlanetKind kind, int height, PlanetBiome biome) {
    }

    private PlanetTerrain() {
    }

    /** Every planet's overlap bands, built once from the core's fold. */
    public static SeamBands bands() {
        SeamBands built = bands;
        if (built == null) {
            built = SeamBands.build(folder());
            bands = built; // a race only builds the same bands twice
        }
        return built;
    }

    /** The core's fold, for {@link SeamBands} and {@link SeamCrossings}. */
    public static SeamCrossings.Folder folder() {
        NativeCore core = core();
        return (cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw);
    }

    /**
     * The terrain of the chunk whose north-west block column is ({@code minX}, {@code minZ}): its own on a face, its
     * twin's in a band (one batched call either way), or null in empty space.
     */
    public static ChunkTerrain chunk(int minX, int minZ, long worldSeed) {
        PlanetHit hit = Planets.at(minX, minZ);
        if (hit != null) {
            return new ChunkTerrain(hit.planet().kind(), sample(hit, worldSeed, 16, 16), null);
        }
        SeamBand band = bands().at(minX, minZ);
        if (band == null) {
            return null;
        }
        // The twin of a chunk-aligned band chunk is a chunk-aligned 16x16 block of the twin face, turned.
        int[] twinX = new int[256];
        int[] twinZ = new int[256];
        int minTwinX = Integer.MAX_VALUE;
        int minTwinZ = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            int[] twin = band.twinBlock(minX + (i & 15), minZ + (i >> 4));
            twinX[i] = twin[0];
            twinZ[i] = twin[1];
            minTwinX = Math.min(minTwinX, twin[0]);
            minTwinZ = Math.min(minTwinZ, twin[1]);
        }
        int[] index = new int[256];
        for (int i = 0; i < 256; i++) {
            index[i] = (twinZ[i] - minTwinZ) * 16 + (twinX[i] - minTwinX);
        }
        PlanetHit twin = Planets.at(minTwinX, minTwinZ);
        return new ChunkTerrain(band.planet().kind(), sample(twin, worldSeed, 16, 16), index);
    }

    /** One column's terrain: its own on a face, its twin's in a band, or null in empty space. */
    public static ColumnTerrain at(int x, int z, long worldSeed) {
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            SeamBand band = bands().at(x, z);
            if (band == null) {
                return null;
            }
            int[] twin = band.twinBlock(x, z);
            hit = Planets.at(twin[0], twin[1]);
        }
        TerrainColumns column = column(hit, worldSeed);
        return new ColumnTerrain(hit.planet().kind(), column.height(0, 0), column.biome(0, 0));
    }

    /**
     * True if the chunk at ({@code minX}, {@code minZ}) is in a band or is copied into one. Vanilla doesn't decorate
     * those chunks (no trees, ores or structures), so a band and the face strip it copies match block for block.
     */
    public static boolean isMirrored(int minX, int minZ) {
        if (Planets.at(minX, minZ) == null) {
            return bands().at(minX, minZ) != null;
        }
        return !bands().copiesOf(minX, minZ).isEmpty();
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
        try {
            return SpawnSearch.findLandChunk(planet, (face, x, z) ->
                    core.terrainColumns(seed, planet.cells(), planet.kind(), face.id(), x, z, 1, 1).height(0, 0),
                    SpawnSearch.MAX_RADIUS_STEPS);
        } catch (NativeCoreException e) {
            throw new IllegalStateException("World Engine terrain failed while looking for land on " + planet.name(), e);
        }
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
