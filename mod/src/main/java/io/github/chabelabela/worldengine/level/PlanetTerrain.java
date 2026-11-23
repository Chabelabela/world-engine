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
        private final int[] index; // band column dz * 16 + dx -> index into the 16x16 twin sample; null on a face

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

        /**
         * The biome of the 4x4 quart whose centre column is ({@code dx}, {@code dz}). In a band, the biome at the
         * centre of the twin's quart: a quarter turn maps a chunk's quarts onto whole quarts of its twin, so a band
         * chunk stores exactly its twin's quart biomes.
         */
        public PlanetBiome quartBiome(int dx, int dz) {
            if (index == null) {
                return columns.biome(dx, dz);
            }
            int i = index[dz * 16 + dx];
            return columns.biome((i % 16 & ~3) + 2, (i / 16 & ~3) + 2);
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
        int maxTwinX = Integer.MIN_VALUE;
        int maxTwinZ = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            int[] twin = band.twinBlock(minX + (i & 15), minZ + (i >> 4));
            twinX[i] = twin[0];
            twinZ[i] = twin[1];
            minTwinX = Math.min(minTwinX, twin[0]);
            minTwinZ = Math.min(minTwinZ, twin[1]);
            maxTwinX = Math.max(maxTwinX, twin[0]);
            maxTwinZ = Math.max(maxTwinZ, twin[1]);
        }
        if (maxTwinX - minTwinX != 15 || maxTwinZ - minTwinZ != 15) {
            throw new IllegalStateException("World Engine band chunk at (" + minX + ", " + minZ
                    + ") doesn't map onto one 16x16 block of its twin face");
        }
        int[] index = new int[256];
        for (int i = 0; i < 256; i++) {
            index[i] = (twinZ[i] - minTwinZ) * 16 + (twinX[i] - minTwinX);
        }
        PlanetHit twin = Planets.at(minTwinX, minTwinZ);
        if (twin == null) {
            throw new IllegalStateException("World Engine band chunk at (" + minX + ", " + minZ
                    + ") maps off its twin face");
        }
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
     * The biome stored for the 4x4 quart whose centre is block column ({@code x}, {@code z}), as
     * {@link ChunkTerrain#quartBiome} stores it: the column's own on a face, its twin quart's centre in a band. Null
     * in empty space.
     */
    public static PlanetBiome quartBiome(int x, int z, long worldSeed) {
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            SeamBand band = bands().at(x, z);
            if (band == null) {
                return null;
            }
            int[] twin = band.twinBlock(x, z);
            hit = Planets.at((twin[0] & ~3) + 2, (twin[1] & ~3) + 2);
        }
        return column(hit, worldSeed).biome(0, 0);
    }

    /**
     * True if the chunk at ({@code minX}, {@code minZ}) is in a band or is copied into one. See {@link #isUndecorated}.
     */
    public static boolean isMirrored(int minX, int minZ) {
        if (Planets.at(minX, minZ) == null) {
            return bands().at(minX, minZ) != null;
        }
        return !bands().copiesOf(minX, minZ).isEmpty();
    }

    /**
     * True if vanilla must not decorate the chunk at ({@code minX}, {@code minZ}) (no trees, ores or structures): it
     * or one of its eight neighbours {@link #isMirrored is mirrored}. A feature may write one chunk beyond its own, so
     * the ring keeps decoration from spilling into a mirrored chunk, and a band and the face strip it copies match
     * block for block.
     */
    public static boolean isUndecorated(int minX, int minZ) {
        for (int dz = -16; dz <= 16; dz += 16) {
            for (int dx = -16; dx <= 16; dx += 16) {
                if (isMirrored(minX + dx, minZ + dz)) {
                    return true;
                }
            }
        }
        return false;
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
