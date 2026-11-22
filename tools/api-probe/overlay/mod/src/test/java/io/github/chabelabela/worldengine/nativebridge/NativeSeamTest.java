package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code we_seam_cross} through the bridge, and the Java net layout against the Rust fold on every seam. */
class NativeSeamTest {
    private static NativeCore core;
    private static SeamCrossings.Folder folder;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
        folder = (cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw);
    }

    @Test
    void theEquatorWrapIsATranslation() {
        assertEquals(new SeamCrossing(0, 0.25, 100.5, 0, 0.3, 0.0, -90.0F),
                core.seamCross(16_384, 3, 16_384.25, 100.5, 0.3, 0.0, -90.0F));
    }

    @Test
    void aRotatedSeamTurnsVelocityAndYaw() {
        // North's west edge meets E0 three quarter turns round: running west continues south (E2 in the spike).
        SeamCrossing c = core.seamCross(16_384, 4, -0.25, 100.5, -0.281, 0.0, 90.0F);
        assertEquals(0, c.face());
        assertEquals(3, c.turns());
        assertEquals(0.0, c.vx());
        assertEquals(0.281, c.vz());
        assertEquals(0.0F, c.yaw());
    }

    @Test
    void pointsItCannotFoldGiveNull() {
        assertNull(core.seamCross(16_384, 0, 10.0, 10.0, 0, 0, 0)); // on the face
        assertNull(core.seamCross(16_384, 0, -1.0, -1.0, 0, 0, 0)); // beyond a corner
    }

    @Test
    void badInputThrows() {
        assertThrows(NativeCoreException.class, () -> core.seamCross(16_384, 6, -1.0, 10.0, 0, 0, 0));
        assertThrows(NativeCoreException.class, () -> core.seamCross(16_383, 0, -1.0, 10.0, 0, 0, 0));
    }

    /**
     * Every directed seam of both planets (7 edges, both ways, 28 in all), found from the Java layout as face edges
     * whose far side is on no face: the Rust fold lands on another face of the same planet where the Java layout
     * agrees, and crossing back from there returns to the start.
     */
    @Test
    void theJavaNetAndTheRustFoldAgreeOnEverySeam() {
        int seams = 0;
        for (PlanetSpec planet : Planets.ALL) {
            PlanetLayout layout = planet.layout();
            int n = planet.cells();
            for (PlanetFace face : PlanetFace.values()) {
                double[][] sides = {
                    {-0.25, 777.25, -1, 0}, {n + 0.25, 777.25, 1, 0}, {777.25, -0.25, 0, -1}, {777.25, n + 0.25, 0, 1},
                };
                for (double[] side : sides) {
                    double x = layout.faceOriginX(face) + side[0];
                    double z = layout.faceOriginZ(face) + side[1];
                    if (Planets.at((int) Math.floor(x), (int) Math.floor(z)) != null) {
                        continue; // an edge the net joins
                    }
                    seams++;
                    String label = planet.name() + " " + face + " (" + side[2] + ", " + side[3] + ")";
                    SeamCrossings.Cross there = assertInstanceOf(SeamCrossings.Cross.class, SeamCrossings.plan(
                            x - 2 * side[2], z - 2 * side[3], x, z, 0.1 * side[2], 0.1 * side[3], 0, folder), label);
                    PlanetHit landed = Planets.at((int) Math.floor(there.foldX()), (int) Math.floor(there.foldZ()));
                    assertNotNull(landed, label);
                    assertEquals(planet, landed.planet(), label);
                    assertEquals(there.face(), landed.cell().face(), label);
                    assertNotEquals(face, there.face(), label);
                    // Half a block back out across the landing edge returns to where it left, half a block in.
                    SeamCrossings.Cross back = assertInstanceOf(SeamCrossings.Cross.class, SeamCrossings.plan(
                            there.foldX(), there.foldZ(), there.foldX() - 5 * there.vx(), there.foldZ() - 5 * there.vz(),
                            -there.vx(), -there.vz(), 0, folder), label);
                    assertEquals(face, back.face(), label);
                    assertEquals(x - 0.5 * side[2], back.foldX(), 1e-6, label);
                    assertEquals(z - 0.5 * side[3], back.foldZ(), 1e-6, label);
                }
            }
        }
        assertEquals(28, seams);
    }
}
