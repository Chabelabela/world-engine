package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The overlap bands built from the real fold: their shape, and that their rigid map is the fold everywhere in them. */
class NativeBandTest {
    private static NativeCore core;
    private static SeamBands bands;

    @BeforeAll
    static void build() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
        bands = SeamBands.build((cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw));
    }

    @Test
    void everySeamHasABandOnBothSides() {
        // 7 seam edges per planet, a band on each side of each.
        assertEquals(28, bands.all().size());
        assertEquals(14, bands.all().stream().filter(b -> b.planet() == Planets.HOME).count());
    }

    /** Fold across one edge is a rigid motion: the band's map, made from one fold, is the fold at every depth. */
    @Test
    void theBandsMapIsTheFoldEverywhereInIt() {
        for (SeamBand band : bands.all()) {
            PlanetLayout layout = band.planet().layout();
            int n = band.planet().cells();
            for (double depth : new double[] {0.5, 17.25, 63.75, 64.25, 127.5}) {
                for (double along : new double[] {0.5, 200.25, n - 0.5}) {
                    double[] grid = switch (band.side()) {
                        case WEST -> new double[] {-depth, along};
                        case EAST -> new double[] {n + depth, along};
                        case NORTH -> new double[] {along, -depth};
                        case SOUTH -> new double[] {along, n + depth};
                    };
                    double x = layout.faceOriginX(band.face()) + grid[0];
                    double z = layout.faceOriginZ(band.face()) + grid[1];
                    SeamCrossing fold = core.seamCross(n, band.face().id(), grid[0], grid[1], 1.0, 0.0, 0.0F);
                    String label = band.planet().name() + " " + band.face() + " " + band.side() + " at " + depth;
                    assertEquals(band.twinFace().id(), fold.face(), label);
                    assertEquals(band.turns(), fold.turns(), label);
                    PlanetFace twin = PlanetFace.fromId(fold.face());
                    assertArrayEquals(new double[] {layout.faceOriginX(twin) + fold.x(), layout.faceOriginZ(twin) + fold.z()},
                            band.toTwin(x, z), 1e-9, label);
                    assertArrayEquals(new double[] {fold.vx(), fold.vz()}, band.rotateDirection(1.0, 0.0), 1e-12, label);
                    SeamCrossing turned = core.seamCross(n, band.face().id(), grid[0], grid[1], 1.0, 0.0, 37.5F);
                    assertEquals(turned.yaw(), band.rotateYaw(37.5F), 1e-4F, label);
                    assertEquals(depth, band.depth(x, z), 1e-9, label);
                }
            }
        }
    }

    @Test
    void bandColumnsCopyFaceColumnsNearTheSameEdge() {
        for (SeamBand band : bands.all()) {
            for (int x : new int[] {band.minX(), band.minX() + 7, band.maxX() - 1}) {
                for (int z : new int[] {band.minZ(), band.minZ() + 9, band.maxZ() - 1}) {
                    assertNull(Planets.at(x, z), "a band is off the net");
                    int[] twin = band.twinBlock(x, z);
                    PlanetHit hit = Planets.at(twin[0], twin[1]);
                    assertNotNull(hit);
                    assertEquals(band.planet(), hit.planet());
                    assertEquals(band.twinFace(), hit.cell().face());
                    assertTrue(SeamCrossings.nearEdge(hit, SeamBands.WIDTH));
                    assertArrayEquals(new int[] {x, z}, band.bandBlock(twin[0], twin[1]));
                    if (bands.at(x, z) == band) {
                        List<SeamBands.Column> copies = bands.copiesOf(twin[0], twin[1]);
                        assertTrue(copies.stream().anyMatch(c -> c.band() == band && c.x() == x && c.z() == z));
                    }
                }
            }
        }
    }

    /** Near the 4 inner corners of each net, two bands claim the same square: 8 squares of 128 × 128, left empty. */
    @Test
    void bandsMeetOnlyInNoMansLandSquares() {
        List<SeamBand> all = bands.all();
        int squares = 0;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                SeamBand a = all.get(i);
                SeamBand b = all.get(j);
                int w = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
                int h = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
                if (w > 0 && h > 0) {
                    squares++;
                    assertEquals(SeamBands.WIDTH, w);
                    assertEquals(SeamBands.WIDTH, h);
                    assertNull(bands.at(Math.max(a.minX(), b.minX()), Math.max(a.minZ(), b.minZ())));
                }
            }
        }
        assertEquals(8, squares);
    }
}
