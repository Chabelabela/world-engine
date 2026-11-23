package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code we_local_sky} through the bridge. */
class NativeLocalSkyTest {
    private static final int N = 16_384;
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void spawnSeesMinecraftsOwnSky() {
        LocalSky sky = core.localSky(N, 1, N / 2.0, N / 2.0);
        assertEquals(0.0, sky.longitude(), 1e-12);
        assertEquals(0.0, sky.latitude(), 1e-12);
        assertArrayEquals(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, sky.tilt(), 1e-12);
    }

    @Test
    void eastIsAhead() {
        assertEquals(Math.PI / 2, core.localSky(N, 2, N / 2.0, N / 2.0).longitude(), 1e-12);
        assertEquals(-Math.PI / 2, core.localSky(N, 0, N / 2.0, N / 2.0).longitude(), 1e-12);
    }

    @Test
    void overTheNorthPoleTheCelestialPoleIsOverhead() {
        LocalSky sky = core.localSky(N, 4, N / 2.0, N / 2.0);
        assertEquals(Math.PI / 2, sky.latitude(), 1e-12);
        // Minecraft's pole is on the northern horizon (-z); here it is straight up.
        assertArrayEquals(new double[] {0, 1, 0}, sky.apply(0, 0, -1), 1e-12);
    }

    @Test
    void badInputThrows() {
        assertThrows(NativeCoreException.class, () -> core.localSky(N, 6, 1.0, 1.0));
        assertThrows(NativeCoreException.class, () -> core.localSky(N - 1, 0, 1.0, 1.0));
        assertThrows(NativeCoreException.class, () -> core.localSky(N, 0, Double.NaN, 1.0));
    }
}
