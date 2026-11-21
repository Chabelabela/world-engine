package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.planet.Planets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class NativeTerrainTest {
    /** Set by the Gradle test task to the freshly built library (see build.gradle). */
    private static final String LIBRARY_PROPERTY = "worldengine.native.path";

    private static final int HOME_CELLS = Planets.HOME.cells();
    private static final int MOON_CELLS = 4_096;
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty(LIBRARY_PROPERTY);
        assertNotNull(path, "the Gradle test task must set " + LIBRARY_PROPERTY);
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void fillsAChunkWithPlausibleTerrain() {
        TerrainColumns chunk = core.terrainColumns(42, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 8_192, 8_192, 16, 16);
        assertEquals(256, chunk.heights().length);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int height = chunk.height(dx, dz);
                assertTrue(height >= TerrainColumns.MIN_HEIGHT && height <= TerrainColumns.MAX_HEIGHT, "height " + height);
                assertTrue(chunk.biome(dx, dz) != PlanetBiome.BARREN);
            }
        }
    }

    @Test
    void batchesMatchSingleColumns() {
        TerrainColumns block = core.terrainColumns(7, HOME_CELLS, PlanetKind.TERRESTRIAL, 3, 1_000, 2_000, 4, 3);
        for (int dz = 0; dz < 3; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                TerrainColumns one = core.terrainColumns(
                        7, HOME_CELLS, PlanetKind.TERRESTRIAL, 3, 1_000 + dx, 2_000 + dz, 1, 1);
                assertEquals(one.height(0, 0), block.height(dx, dz));
                assertEquals(one.biome(0, 0), block.biome(dx, dz));
            }
        }
    }

    @Test
    void cubeCornersAreDeepOcean() {
        // Cell (0, 0) of face E0 sits at the (1, 1, 1) corner of the cube.
        TerrainColumns corner = core.terrainColumns(42, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 0, 0, 1, 1);
        assertEquals(PlanetBiome.DEEP_OCEAN, corner.biome(0, 0));
    }

    @Test
    void theMoonIsBarren() {
        TerrainColumns moon = core.terrainColumns(42, MOON_CELLS, PlanetKind.BARREN, 4, 1_000, 1_000, 8, 8);
        for (int dz = 0; dz < 8; dz++) {
            for (int dx = 0; dx < 8; dx++) {
                assertEquals(PlanetBiome.BARREN, moon.biome(dx, dz));
            }
        }
    }

    @Test
    void seedsChangeTheTerrain() {
        TerrainColumns a = core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 2, 5_000, 5_000, 16, 16);
        TerrainColumns b = core.terrainColumns(2, HOME_CELLS, PlanetKind.TERRESTRIAL, 2, 5_000, 5_000, 16, 16);
        assertFalse(Arrays.equals(a.heights(), b.heights()));
    }

    @Test
    void rejectsInvalidRequests() {
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 6, 0, 0, 1, 1));
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, 3, PlanetKind.TERRESTRIAL, 0, 0, 0, 1, 1));
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, HOME_CELLS - 2, 0, 4, 1));
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 0, 0, -1, 1));
    }

    @Test
    void rejectsNegativeCells() {
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, -1, PlanetKind.TERRESTRIAL, 0, 0, 0, 1, 1));
    }

    @Test
    void zeroSizedRegionsAreEmpty() {
        TerrainColumns empty = core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 0, 0, 0, 5);
        assertEquals(0, empty.heights().length);
        assertEquals(0, empty.biomes().length);
    }

    @Test
    void rejectsRegionsOverTheColumnLimit() {
        // 1025 * 1024 is one row over NativeCore.MAX_COLUMNS; rejected in Java before allocating.
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 0, 0, 1025, 1024));
    }

    @Test
    void negativeSeedsAreDeterministic() {
        TerrainColumns a = core.terrainColumns(-123_456_789L, HOME_CELLS, PlanetKind.TERRESTRIAL, 1, 100, 100, 16, 16);
        TerrainColumns b = core.terrainColumns(-123_456_789L, HOME_CELLS, PlanetKind.TERRESTRIAL, 1, 100, 100, 16, 16);
        assertEquals(a, b);
    }

    @Test
    void terrainColumnsRejectsMismatchedArrays() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainColumns(2, 2, new int[3], new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> new TerrainColumns(2, 2, new int[4], new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> new TerrainColumns(-1, 2, new int[0], new byte[0]));
    }

    @Test
    void equalTerrainColumnsHaveEqualHashCodes() {
        TerrainColumns a = core.terrainColumns(5, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 500, 500, 8, 8);
        TerrainColumns b = core.terrainColumns(5, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 500, 500, 8, 8);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @Timeout(60)
    void isSafeToCallFromManyThreads() throws Exception {
        int threads = 8;
        int loops = 200;
        long[] seeds = new long[threads];
        int[] faces = new int[threads];
        int[] origins = new int[threads];
        TerrainColumns[] expected = new TerrainColumns[threads];
        for (int i = 0; i < threads; i++) {
            seeds[i] = i % 2 == 0 ? 42 + i : -(42 + i);
            faces[i] = i % 6;
            origins[i] = 300 + 97 * i;
            expected[i] = core.terrainColumns(
                    seeds[i], HOME_CELLS, PlanetKind.TERRESTRIAL, faces[i], origins[i], origins[i], 32, 32);
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Void>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int t = i;
                results.add(pool.submit(() -> {
                    barrier.await();
                    for (int n = 0; n < loops; n++) {
                        TerrainColumns actual = core.terrainColumns(
                                seeds[t], HOME_CELLS, PlanetKind.TERRESTRIAL, faces[t], origins[t], origins[t], 32, 32);
                        assertEquals(expected[t], actual, "thread " + t + " loop " + n);
                    }
                    return null;
                }));
            }
            for (Future<Void> result : results) {
                result.get();
            }
        }
    }

    @Test
    void biomeIdsMatchTheAbi() {
        assertEquals(13, PlanetBiome.values().length);
        assertEquals(0, PlanetBiome.DEEP_OCEAN.id());
        assertEquals(4, PlanetBiome.FOREST.id());
        assertEquals(12, PlanetBiome.BARREN.id());
        assertEquals(PlanetBiome.SNOWY_PEAKS, PlanetBiome.fromId(11));
        assertThrows(NativeCoreException.class, () -> PlanetBiome.fromId(13));
        assertEquals(1, PlanetKind.BARREN.id());
    }
}
