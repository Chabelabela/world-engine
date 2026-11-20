package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class SpawnSearchTest {
    @Test
    void landAtTheCentreIsTheCentreChunk() {
        assertArrayEquals(new int[] {0, 0}, SpawnSearch.findLandChunk(Planets.HOME, (f, x, z) -> 5, 8));
    }

    @Test
    void searchesOutwardInRings() {
        // Home's E1 centre (cell 8,192) is block x = 0. Land only from cell 8,232 (block 40) eastward:
        // chunk x = 2 is the first whose centre column (block 40) is on land, in ring 2. The ring is
        // scanned row by row from its north edge, so the first hit is chunk (2, -2).
        SpawnSearch.Heights eastIsLand = (face, x, z) -> face == PlanetFace.E1 && x >= 8_232 ? 3 : -10;
        assertArrayEquals(new int[] {2, -2}, SpawnSearch.findLandChunk(Planets.HOME, eastIsLand, 8));
    }

    @Test
    void anOceanPlanetFallsBackToTheCentre() {
        assertArrayEquals(new int[] {0, 0}, SpawnSearch.findLandChunk(Planets.HOME, (f, x, z) -> -5, 3));
    }

    @Test
    void sampledCellsAreOnThePlanet() {
        int[] moon = SpawnSearch.findLandChunk(Planets.MOON, (f, x, z) -> 2, 2);
        assertArrayEquals(new int[] {Math.floorDiv(Planets.MOON.centreX(), 16), Math.floorDiv(Planets.MOON.centreZ(), 16)}, moon);
    }
}
