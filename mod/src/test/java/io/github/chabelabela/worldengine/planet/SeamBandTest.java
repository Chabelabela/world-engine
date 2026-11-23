package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeamBandTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();

    /** E3's east band: the equator wraps onto E0's west edge, a pure translation 4 faces west. */
    private static final SeamBand WRAP = new SeamBand(Planets.HOME, PlanetFace.E3, SeamBand.Side.EAST, PlanetFace.E0, 0,
            HOME.faceOriginX(PlanetFace.E3) + N, HOME.faceOriginZ(PlanetFace.E3),
            HOME.faceOriginX(PlanetFace.E3) + N + SeamBands.WIDTH, HOME.faceOriginZ(PlanetFace.E3) + N, -4.0 * N, 0.0);

    /** A made-up band turned one quarter clockwise about the origin, then moved by (1000, 2000). */
    private static final SeamBand TURNED = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 1,
            -100, 0, 0, 50, 1000.0, 2000.0);

    @Test
    void theWrapIsATranslation() {
        double east = HOME.faceOriginX(PlanetFace.E3) + N;
        double west = HOME.faceOriginX(PlanetFace.E0);
        assertArrayEquals(new double[] {west + 64.25, 100.5}, WRAP.toTwin(east + 64.25, 100.5));
        assertEquals(64.25, WRAP.depth(east + 64.25, 100.5));
        assertArrayEquals(new double[] {0.3, -0.1}, WRAP.rotateDirection(0.3, -0.1));
        assertEquals(-90.0F, WRAP.rotateYaw(-90.0F));
    }

    @Test
    void aTurnRotatesClockwiseLikeQuarterTurns() {
        // East turns into south, south into west: (dx, dz) -> (-dz, dx).
        assertArrayEquals(new double[] {0.0, 1.0}, TURNED.rotateDirection(1.0, 0.0), 0.0); // delta 0: -0.0 == 0.0
        assertArrayEquals(new double[] {-1.0, 0.0}, TURNED.rotateDirection(0.0, 1.0), 0.0);
        assertArrayEquals(new double[] {1000.0 - 2.0, 2000.0 + 1.0}, TURNED.toTwin(1.0, 2.0));
        // Yaw 0 looks south; a quarter turn clockwise looks west (90).
        assertEquals(90.0F, TURNED.rotateYaw(0.0F));
        assertEquals(-180.0F, TURNED.rotateYaw(90.0F));
    }

    @Test
    void fromTwinUndoesToTwin() {
        for (SeamBand band : new SeamBand[] {WRAP, TURNED}) {
            double[] twin = band.toTwin(-37.25, 12.75);
            double[] back = band.fromTwin(twin[0], twin[1]);
            assertEquals(-37.25, back[0], 1e-9);
            assertEquals(12.75, back[1], 1e-9);
        }
    }

    @Test
    void blocksMapToBlocksThroughTheirCentres() {
        int[] twin = TURNED.twinBlock(-3, 7);
        // Centre (-2.5, 7.5) turns to (-7.5, -2.5), then moves to (992.5, 1997.5): block (992, 1997).
        assertArrayEquals(new int[] {992, 1997}, twin);
        assertArrayEquals(new int[] {-3, 7}, TURNED.bandBlock(twin[0], twin[1]));
    }

    @Test
    void containsIsTheHalfOpenRectangle() {
        assertTrue(TURNED.contains(-100, 0));
        assertTrue(TURNED.contains(-1, 49));
        assertFalse(TURNED.contains(0, 10));
        assertFalse(TURNED.contains(-50, 50));
    }

    @Test
    void depthGrowsAwayFromTheEdgeOnEverySide() {
        int ox = HOME.faceOriginX(PlanetFace.E0);
        int oz = HOME.faceOriginZ(PlanetFace.E0);
        SeamBand west = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 0, 0, 0, 1, 1, 0, 0);
        SeamBand north = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.NORTH, PlanetFace.NORTH, 1, 0, 0, 1, 1, 0, 0);
        SeamBand south = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.SOUTH, PlanetFace.SOUTH, 3, 0, 0, 1, 1, 0, 0);
        assertEquals(5.0, west.depth(ox - 5.0, 0));
        assertEquals(5.0, north.depth(0, oz - 5.0));
        assertEquals(5.0, south.depth(0, oz + N + 5.0));
        assertEquals(-5.0, west.depth(ox + 5.0, 0)); // on the face
    }

    @Test
    void rejectsBadTurns() {
        assertThrows(IllegalArgumentException.class,
                () -> new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 4, 0, 0, 1, 1, 0, 0));
    }
}
