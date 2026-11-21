package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import org.junit.jupiter.api.Test;

class PlanetsTest {
    // Part of the world format: changing these changes every generated world.
    private static final long HOME_SEED_12345 = -5_771_623_559_949_941_913L;
    private static final long MOON_SEED_12345 = -5_841_888_737_799_734_202L;

    @Test
    void homeFaceE1IsCentredOnTheLevelOrigin() {
        assertEquals(0, Planets.HOME.centreX());
        assertEquals(0, Planets.HOME.centreZ());
        PlanetHit hit = Planets.at(0, 0);
        assertSame(Planets.HOME, hit.planet());
        assertEquals(new FaceCell(PlanetFace.E1, 8_192, 8_192), hit.cell());
    }

    @Test
    void planetsMatchTheRoadmap() {
        assertEquals(16_384, Planets.HOME.cells());
        assertEquals(PlanetKind.TERRESTRIAL, Planets.HOME.kind());
        assertEquals(4_096, Planets.MOON.cells());
        assertEquals(PlanetKind.BARREN, Planets.MOON.kind());
        assertSame(Planets.MOON, Planets.byName("moon"));
        assertNull(Planets.byName("pluto"));
    }

    @Test
    void netsAreFarApart() {
        // Bounding boxes of the nets, grown by 1,024 blocks, must not touch.
        for (PlanetSpec a : Planets.ALL) {
            for (PlanetSpec b : Planets.ALL) {
                if (a != b) {
                    assertTrue(minX(a) - 1_024 >= maxX(b) || minX(b) - 1_024 >= maxX(a)
                            || minZ(a) - 1_024 >= maxZ(b) || minZ(b) - 1_024 >= maxZ(a), a.name() + " / " + b.name());
                }
            }
        }
        PlanetHit moon = Planets.at(Planets.MOON.centreX(), Planets.MOON.centreZ());
        assertSame(Planets.MOON, moon.planet());
        assertNull(Planets.at(70_000, 0));
    }

    @Test
    void planetSeedsAreDistinctAndPinned() {
        assertNotEquals(Planets.HOME.seed(42L), Planets.MOON.seed(42L));
        assertNotEquals(Planets.HOME.seed(42L), Planets.HOME.seed(43L));
        assertEquals(HOME_SEED_12345, Planets.HOME.seed(12_345L));
        assertEquals(MOON_SEED_12345, Planets.MOON.seed(12_345L));
    }

    private static long minX(PlanetSpec p) {
        return p.layout().originX();
    }

    private static long maxX(PlanetSpec p) {
        return p.layout().originX() + 4L * p.cells();
    }

    private static long minZ(PlanetSpec p) {
        return p.layout().originZ() - (long) p.cells();
    }

    private static long maxZ(PlanetSpec p) {
        return p.layout().originZ() + 2L * p.cells();
    }
}
