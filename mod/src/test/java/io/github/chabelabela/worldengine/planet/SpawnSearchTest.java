package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpawnSearchTest {
    @Test
    void landAtTheCentreIsTheCentreChunk() {
        assertArrayEquals(new int[] {0, 0}, SpawnSearch.findLandChunk(Planets.HOME, (f, x, z) -> 5, 8));
    }

    @Test
    void searchesOutwardInRings() {
        // Home's E1 centre (cell 8,192) is block x = 0. Land only from cell 8,392 (block 200) eastward. Samples
        // sit 64 blocks apart at block 8 + 64 * step, so the first on land is step 3 (block 200), in ring 3.
        // A ring is scanned row by row from its north edge, so the first hit is step (3, -3): chunk (12, -12).
        SpawnSearch.Heights eastIsLand = (face, x, z) -> face == PlanetFace.E1 && x >= 8_392 ? 3 : -10;
        assertArrayEquals(new int[] {12, -12}, SpawnSearch.findLandChunk(Planets.HOME, eastIsLand, 8));
    }

    @Test
    void anOceanPlanetFallsBackToTheCentreAfterSearchingAllOfE1() {
        AtomicInteger samples = new AtomicInteger();
        int[] chunk = SpawnSearch.findLandChunk(Planets.HOME, (f, x, z) -> {
            samples.incrementAndGet();
            return -5;
        }, SpawnSearch.MAX_RADIUS_STEPS);
        assertArrayEquals(new int[] {0, 0}, chunk);
        // Samples run from block -8,184 to 8,200 on both axes. All are on the net except the corner
        // (8,200, 8,200), which is beyond both E2 and South.
        int side = 2 * SpawnSearch.MAX_RADIUS_STEPS + 1;
        assertEquals(side * side - 1, samples.get());
    }

    @Test
    void sampledCellsAreOnThePlanet() {
        int[] moon = SpawnSearch.findLandChunk(Planets.MOON, (f, x, z) -> 2, 2);
        assertArrayEquals(new int[] {Math.floorDiv(Planets.MOON.centreX(), 16), Math.floorDiv(Planets.MOON.centreZ(), 16)}, moon);
    }
}
