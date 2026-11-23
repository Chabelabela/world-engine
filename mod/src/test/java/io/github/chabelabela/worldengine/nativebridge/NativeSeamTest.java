package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code we_seam_cross} through the bridge. */
class NativeSeamTest {
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
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
}
