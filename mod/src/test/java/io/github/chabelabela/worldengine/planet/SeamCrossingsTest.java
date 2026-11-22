package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeamCrossingsTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();
    /** Level x of E3's east edge (a seam: the equator wraps onto E0's west edge). */
    private static final int E3_EAST = HOME.faceOriginX(PlanetFace.E3) + N;

    /** Stand-in for the native fold: the equator wrap only, like {@code we_seam_cross} does there. */
    private static final SeamCrossings.Folder WRAP = (cells, face, x, z, vx, vz, yaw) ->
            face == PlanetFace.E3 && x > cells && z >= 0 && z <= cells
                    ? new SeamCrossing(PlanetFace.E0.id(), x - cells, z, 0, vx, vz, yaw)
                    : null;

    @Test
    void onTheNetNothingHappens() {
        assertNull(SeamCrossings.plan(5, 5, 6, 5, 1, 0, 0, WRAP));
        // E1's east edge is joined to E2 in the net: crossing it is just walking.
        int e2 = HOME.faceOriginX(PlanetFace.E2);
        assertNull(SeamCrossings.plan(e2 - 0.5, 5, e2 + 0.5, 5, 1, 0, 0, WRAP));
    }

    @Test
    void somethingThatWasNotOnAPlanetIsLeftAlone() {
        assertNull(SeamCrossings.plan(70_000, 0, 70_001, 0, 1, 0, 0, WRAP));
    }

    @Test
    void aCrossingOffersFoldsPointAndOneTickShortOfIt() {
        SeamCrossings.Outcome outcome =
                SeamCrossings.plan(E3_EAST - 4.5, 100.5, E3_EAST + 0.25, 100.5, 0.3, 0, -90, WRAP);
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class, outcome);
        int e0 = HOME.faceOriginX(PlanetFace.E0);
        assertEquals(Planets.HOME, cross.planet());
        assertEquals(PlanetFace.E0, cross.face());
        assertEquals(e0 + 0.25, cross.foldX());
        assertEquals(100.5, cross.foldZ());
        assertEquals(e0 + 0.25 - 0.3, cross.x(), 1e-12);
        assertEquals(100.5, cross.z());
        assertEquals(0.3, cross.vx());
        assertEquals(0.0, cross.vz());
        assertEquals(-90.0F, cross.yaw());
    }

    @Test
    void somethingTeleportedFarAwayIsLeftAlone() {
        assertNull(SeamCrossings.plan(HOME.faceOriginX(PlanetFace.E0) + 2.5, 100.5,
                HOME.faceOriginX(PlanetFace.E0) - 20_000 + 0.5, 100.5, -1, 0, 0, WRAP));
    }

    @Test
    void theFolderGetsGridCoordinatesOnTheLastFace() {
        List<Object[]> calls = new ArrayList<>();
        SeamCrossings.Folder spy = (cells, face, x, z, vx, vz, yaw) -> {
            calls.add(new Object[] {cells, face, x, z, vx, vz, yaw});
            return new SeamCrossing(PlanetFace.SOUTH.id(), 10.0, 20.0, 1, -vz, vx, yaw + 90);
        };
        double z = HOME.faceOriginZ(PlanetFace.E3) + 7.25;
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E3_EAST - 1.5, z, E3_EAST + 0.75, z, 0.2, 0.1, 10, spy));
        assertArrayEquals(new Object[] {N, PlanetFace.E3, N + 0.75, 7.25, 0.2, 0.1, 10.0F}, calls.get(0));
        // Fold's point is translated into level coordinates on the face it names.
        assertEquals(HOME.faceOriginX(PlanetFace.SOUTH) + 10.0, cross.foldX());
        assertEquals(HOME.faceOriginZ(PlanetFace.SOUTH) + 20.0, cross.foldZ());
        assertEquals(cross.foldX() + 0.1, cross.x(), 1e-12); // minus the rotated velocity (-0.1, 0.2)
        assertEquals(cross.foldZ() - 0.2, cross.z(), 1e-12);
        assertEquals(100.0F, cross.yaw());
    }

    @Test
    void beyondACornerItGoesBackToWhereItLastStood() {
        int e0x = HOME.faceOriginX(PlanetFace.E0);
        int e0z = HOME.faceOriginZ(PlanetFace.E0);
        SeamCrossings.Outcome outcome = SeamCrossings.plan(e0x + 2.5, e0z + 2.5, e0x - 0.5, e0z - 0.5, -1, -1, 0, WRAP);
        SeamCrossings.Bounce bounce = assertInstanceOf(SeamCrossings.Bounce.class, outcome);
        assertEquals(e0x + 2.5, bounce.x());
        assertEquals(e0z + 2.5, bounce.z());
    }

    @Test
    void nearEdgeLooksAtAllFourEdges() {
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 3, 8_000)), 16));
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 8_000, N - 1)), 16));
        assertFalse(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 16, N - 17)), 16));
    }

    @Test
    void farSidesAreOnlyTheSeams() {
        List<Object[]> calls = new ArrayList<>();
        SeamCrossings.Folder anywhere = (cells, face, x, z, vx, vz, yaw) -> {
            calls.add(new Object[] {face, x, z});
            return new SeamCrossing(PlanetFace.E2.id(), 1.0, 2.0, 0, vx, vz, yaw);
        };
        // Every edge of E1 is joined in the net (E0, E2, North, South): nothing to pre-load.
        assertTrue(SeamCrossings.farSides(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 3, 3)), 16, anywhere)
                .isEmpty());
        // E0's west edge (the equator wrap) and north edge are seams: near its corner there are two far sides.
        List<double[]> sides =
                SeamCrossings.farSides(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E0, 3, 3)), 16, anywhere);
        assertEquals(2, sides.size());
        double[] far = {HOME.faceOriginX(PlanetFace.E2) + 1.0, HOME.faceOriginZ(PlanetFace.E2) + 2.0};
        assertArrayEquals(far, sides.get(0));
        assertArrayEquals(far, sides.get(1));
        // Straight across the west edge, then across the north edge.
        assertEquals(2, calls.size());
        assertArrayEquals(new Object[] {PlanetFace.E0, -0.5, 3.5}, calls.get(0));
        assertArrayEquals(new Object[] {PlanetFace.E0, 3.5, -0.5}, calls.get(1));
        // Away from E0's edges: none.
        assertTrue(SeamCrossings.farSides(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E0, 500, 500)), 16,
                anywhere).isEmpty());
    }
}
