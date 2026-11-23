package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lookups over hand-made bands; {@code NativeBandTest} checks the bands built from the real fold. */
class SeamBandsTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int W = SeamBands.WIDTH;
    private static final int E0X = HOME.faceOriginX(PlanetFace.E0);
    private static final int E0Z = HOME.faceOriginZ(PlanetFace.E0);

    /** E0's west band, a stand-in mapping onto E3's east edge (as the real equator wrap does). */
    private static final SeamBand WEST = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 0,
            E0X - W, E0Z, E0X, E0Z + N, 4.0 * N, 0.0);
    /** A band overlapping WEST's north end, to make a no-man's-land square. */
    private static final SeamBand CROSSING = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.NORTH,
            PlanetFace.NORTH, 0, E0X - W, E0Z, E0X, E0Z + W, 0.0, -5_000.0);
    private static final SeamBands BANDS = new SeamBands(List.of(WEST, CROSSING));

    @Test
    void aColumnInOneBandBelongsToIt() {
        assertSame(WEST, BANDS.at(E0X - 10, E0Z + 500));
    }

    @Test
    void whereTwoBandsMeetIsNoMansLand() {
        assertNull(BANDS.at(E0X - 10, E0Z + 10));
    }

    @Test
    void faceColumnsAndEmptySpaceAreInNoBand() {
        assertNull(BANDS.at(E0X + 10, E0Z + 500));
        assertNull(BANDS.at(E0X - W - 1, E0Z + 500));
    }

    @Test
    void copiesOfFindsTheBandColumnsShowingAFaceColumn() {
        int[] twin = WEST.twinBlock(E0X - 10, E0Z + 500);
        List<SeamBands.Column> copies = BANDS.copiesOf(twin[0], twin[1]);
        assertEquals(1, copies.size());
        assertSame(WEST, copies.get(0).band());
        assertEquals(E0X - 10, copies.get(0).x());
        assertEquals(E0Z + 500, copies.get(0).z());
        // The twin of a no-man's-land column has no copy.
        int[] lost = WEST.twinBlock(E0X - 10, E0Z + 10);
        assertTrue(BANDS.copiesOf(lost[0], lost[1]).stream().noneMatch(c -> c.band() == WEST));
    }

    @Test
    void preloadTargetsAreWhereACrossingWouldLand() {
        // In the band: the twin of where it would land walking straight out, not of where it stands.
        List<double[]> inBand = BANDS.preloadTargets(E0X - 10.5, E0Z + 500.5);
        assertEquals(1, inBand.size());
        assertArrayEquals(WEST.toTwin(E0X - SeamBands.TELEPORT_DEPTH, E0Z + 500.5), inBand.get(0));
        // On the face, 20 blocks in: the twin of the band's middle straight ahead.
        List<double[]> onFace = BANDS.preloadTargets(E0X + 20.5, E0Z + 500.5);
        assertEquals(1, onFace.size());
        assertArrayEquals(WEST.toTwin(E0X - SeamBands.TELEPORT_DEPTH, E0Z + 500.5), onFace.get(0));
        // A point in the band and a face point on the same line straight out get the same target.
        assertArrayEquals(inBand.get(0), onFace.get(0));
        // Far from the edge: nothing.
        assertTrue(BANDS.preloadTargets(E0X + W + 20.5, E0Z + 500.5).isEmpty());
    }
}
