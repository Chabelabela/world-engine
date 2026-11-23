package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SeamCrossingsTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int W = SeamBands.WIDTH;
    /** Level x of E3's east edge (a seam: the equator wraps onto E0's west edge). */
    private static final int E3_EAST = HOME.faceOriginX(PlanetFace.E3) + N;
    private static final int E0_WEST = HOME.faceOriginX(PlanetFace.E0);
    private static final int Z0 = HOME.faceOriginZ(PlanetFace.E3);

    /** E3's east band as the real fold makes it: a pure translation four faces west. */
    private static final SeamBand WRAP = new SeamBand(Planets.HOME, PlanetFace.E3, SeamBand.Side.EAST, PlanetFace.E0, 0,
            E3_EAST, Z0, E3_EAST + W, Z0 + N, -4.0 * N, 0.0);
    /** A made-up turned band beyond E0's west edge, to check that velocity and yaw are turned. */
    private static final SeamBand TURNED = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3,
            1, E0_WEST - W, Z0, E0_WEST, Z0 + N, 5_000.0, 7_000.0);
    /** Overlaps TURNED's north end: no-man's land. */
    private static final SeamBand OVERLAP = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.NORTH,
            PlanetFace.NORTH, 0, E0_WEST - W, Z0, E0_WEST, Z0 + W, 0.0, 0.0);
    private static final SeamBands BANDS = new SeamBands(List.of(WRAP, TURNED, OVERLAP));

    @Test
    void onTheNetNothingHappens() {
        assertNull(SeamCrossings.plan(5, 5, 6, 5, 1, 0, 0, BANDS));
        // E1's east edge is joined to E2 in the net: crossing it is just walking.
        int e2 = HOME.faceOriginX(PlanetFace.E2);
        assertNull(SeamCrossings.plan(e2 - 0.5, 5, e2 + 0.5, 5, 1, 0, 0, BANDS));
    }

    @Test
    void walkingIntoABandIsJustWalking() {
        assertNull(SeamCrossings.plan(E3_EAST - 2.5, 100.5, E3_EAST + 10.5, 100.5, 0.3, 0, -90, BANDS));
        assertNull(SeamCrossings.plan(E3_EAST + 60.5, 100.5, E3_EAST + 63.75, 100.5, 0.3, 0, -90, BANDS));
    }

    @Test
    void atTheBandsMiddleItIsCarriedToTheTwin() {
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E3_EAST + 63.75, 100.5, E3_EAST + 64.25, 100.5, 0.3, 0, -90, BANDS));
        assertEquals(Planets.HOME, cross.planet());
        assertEquals(PlanetFace.E0, cross.face());
        assertEquals(E0_WEST + 64.25, cross.twinX());
        assertEquals(100.5, cross.twinZ());
        assertEquals(E0_WEST + 64.25 - 0.3, cross.x(), 1e-12); // a player lands one tick short of the twin
        assertEquals(100.5, cross.z());
        assertEquals(0.3, cross.vx());
        assertEquals(0.0, cross.vz());
        assertEquals(-90.0F, cross.yaw());
    }

    @Test
    void aTurnedBandTurnsVelocityAndYaw() {
        double x = E0_WEST - 64.5;
        double z = Z0 + 500.5;
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(x + 0.4, z, x, z, -0.4, 0.1, 90, BANDS));
        double[] twin = TURNED.toTwin(x, z);
        assertEquals(twin[0], cross.twinX());
        assertEquals(twin[1], cross.twinZ());
        // A quarter turn clockwise: (dx, dz) -> (-dz, dx); yaw + 90.
        assertEquals(-0.1, cross.vx(), 1e-12);
        assertEquals(-0.4, cross.vz(), 1e-12);
        assertEquals(-180.0F, cross.yaw());
        assertEquals(twin[0] + 0.1, cross.x(), 1e-12);
        assertEquals(twin[1] + 0.4, cross.z(), 1e-12);
    }

    @Test
    void theMiddleIsExact() {
        double z = Z0 + 100.5;
        assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E3_EAST + 60.5, z, E3_EAST + 64.0, z, 0.3, 0, -90, BANDS));
        assertNull(SeamCrossings.plan(E3_EAST + 60.5, z, E3_EAST + 63.999, z, 0.3, 0, -90, BANDS));
        // A west-side band, whose depth grows towards -x.
        double tz = Z0 + 500.5;
        assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E0_WEST - 60.5, tz, E0_WEST - 64.0, tz, -0.3, 0, 90, BANDS));
        assertNull(SeamCrossings.plan(E0_WEST - 60.5, tz, E0_WEST - 63.999, tz, -0.3, 0, 90, BANDS));
    }

    @Test
    void aCrossingLandsOnTheFace() {
        double z = Z0 + 100.5;
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E3_EAST + 63.75, z, E3_EAST + 64.25, z, 0.3, 0, -90, BANDS));
        assertNotNull(Planets.at((int) Math.floor(cross.x()), (int) Math.floor(cross.z())));
        assertNotNull(Planets.at((int) Math.floor(cross.twinX()), (int) Math.floor(cross.twinZ())));
        // One tick further along the turned velocity, it is walking on the face: no ping-pong.
        assertNull(SeamCrossings.plan(cross.x(), cross.z(), cross.x() + cross.vx(), cross.z() + cross.vz(),
                cross.vx(), cross.vz(), cross.yaw(), BANDS));
    }

    @Test
    void teleportingIntoNoMansLandIsLeftAlone() {
        // Last in empty space north of TURNED, now in the square where TURNED and OVERLAP meet.
        int lastX = (int) Math.floor(E0_WEST - 10.5);
        assertNull(Planets.at(lastX, Z0 - 6));
        assertNull(BANDS.at(lastX, Z0 - 6));
        assertNull(BANDS.at(lastX, Z0)); // two bands claim it
        assertNull(SeamCrossings.plan(E0_WEST - 10.5, Z0 - 5.5, E0_WEST - 10.5, Z0 + 0.5, 0, 1, 0, BANDS));
    }

    @Test
    void inNoMansLandItGoesBackToWhereItLastStood() {
        SeamCrossings.Bounce bounce = assertInstanceOf(SeamCrossings.Bounce.class,
                SeamCrossings.plan(E0_WEST - 10.5, Z0 + W + 0.5, E0_WEST - 10.5, Z0 + W - 0.5, 0, -1, 180, BANDS));
        assertEquals(E0_WEST - 10.5, bounce.x());
        assertEquals(Z0 + W + 0.5, bounce.z());
    }

    @Test
    void somethingTeleportedFarAwayIsLeftAlone() {
        assertNull(SeamCrossings.plan(E3_EAST - 2.5, 100.5, E3_EAST + 20_000.5, 100.5, 0, 0, 0, BANDS));
        assertNull(SeamCrossings.plan(E3_EAST + 10.5, 100.5, E3_EAST + 10.5 + SeamCrossings.MAX_STEP + 1, 100.5, 0, 0, 0,
                BANDS));
    }

    @Test
    void somethingThatWasNotOnAPlanetIsLeftAlone() {
        assertNull(SeamCrossings.plan(70_000, 0, 70_001, 0, 1, 0, 0, BANDS));
    }

    @Test
    void nearEdgeLooksAtAllFourEdges() {
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 3, 8_000)), 16));
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 8_000, N - 1)), 16));
        assertFalse(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 16, N - 17)), 16));
    }
}
