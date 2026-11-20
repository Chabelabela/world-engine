package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeCoreTest {
    /** Set by the Gradle test task to the freshly built library (see build.gradle). */
    private static final String LIBRARY_PROPERTY = "worldengine.native.path";

    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty(LIBRARY_PROPERTY);
        assertNotNull(path, "the Gradle test task must set " + LIBRARY_PROPERTY);
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void reportsAbiVersion() {
        assertEquals(NativeCore.EXPECTED_ABI_VERSION, core.abiVersion());
    }

    @Test
    void demoUniverseHasHomeAndMoon() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            assertEquals(2, u.bodyCount());
            assertEquals("home", u.bodyName(0));
            assertEquals("moon", u.bodyName(1));
        }
    }

    @Test
    void moonOrbitsAt60KmInTheXzPlane() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            u.setTime(500.0);
            double[] p = u.bodyPosition(1);
            assertEquals(60_000.0, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-6);
            assertEquals(0.0, p[1], 1e-9);
        }
    }

    @Test
    void rejectsBadIndex() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            assertThrows(NativeCoreException.class, () -> u.bodyPosition(9));
        }
    }

    @Test
    void rejectsNonFiniteTime() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            assertThrows(NativeCoreException.class, () -> u.setTime(Double.NaN));
        }
    }

    @Test
    void wrapsLoadFailuresInNativeCoreException(@TempDir Path dir) throws IOException {
        Path notALibrary = Files.writeString(dir.resolve("not-a-library.bin"), "hello");
        assertThrows(NativeCoreException.class, () -> NativeCore.load(notALibrary));
    }

    @Test
    void rejectsUseAfterClose() {
        NativeCore.Universe u = core.newDemoUniverse();
        u.close();
        u.close(); // idempotent
        assertThrows(IllegalStateException.class, u::bodyCount);
    }
}
