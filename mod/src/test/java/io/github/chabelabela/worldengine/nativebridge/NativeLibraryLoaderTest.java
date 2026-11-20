package io.github.chabelabela.worldengine.nativebridge;

import static io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader.OVERRIDE_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLibraryLoaderTest {
    private String savedOverride;

    @BeforeEach
    void clearOverride() {
        savedOverride = System.getProperty(OVERRIDE_PROPERTY);
        System.clearProperty(OVERRIDE_PROPERTY);
    }

    @AfterEach
    void restoreOverride() {
        if (savedOverride != null) {
            System.setProperty(OVERRIDE_PROPERTY, savedOverride);
        }
    }

    @Test
    void overridePropertyWins(@TempDir Path cache) {
        System.setProperty(OVERRIDE_PROPERTY, "/some/where/libworldengine_core.so");
        assertEquals(Path.of("/some/where/libworldengine_core.so"), NativeLibraryLoader.locate(cache));
    }

    @Test
    void extractsBundledLibraryOnceAndLoadsIt() {
        // Not a @TempDir: the loaded library stays mapped for the JVM's lifetime,
        // and Windows cannot delete a loaded DLL.
        Path cache = Path.of(System.getProperty("worldengine.test.cacheDir", "build/test-native-cache"));
        Path first = NativeLibraryLoader.locate(cache);
        assertTrue(Files.isRegularFile(first));
        assertTrue(first.startsWith(cache));
        assertEquals(first, NativeLibraryLoader.locate(cache));
        assertEquals(NativeCore.EXPECTED_ABI_VERSION, NativeCore.load(first).abiVersion());
    }

    @Test
    void prunesOtherCacheFoldersButKeepsTheCurrentOne(@TempDir Path cache) throws IOException {
        Path current = Files.createDirectories(cache.resolve("aaaa")).resolve("libworldengine_core.so");
        Files.writeString(current, "current");
        Path stale = Files.createDirectories(cache.resolve("bbbb"));
        Files.writeString(stale.resolve("libworldengine_core.so"), "old");
        NativeLibraryLoader.pruneStaleCaches(cache, current);
        assertTrue(Files.exists(current));
        assertFalse(Files.exists(stale));
    }

    @Test
    void pruneLeavesTheCacheAloneForLibrariesOutsideIt(@TempDir Path cache) throws IOException {
        Path stale = Files.createDirectories(cache.resolve("bbbb"));
        NativeLibraryLoader.pruneStaleCaches(cache, Path.of("/elsewhere/libworldengine_core.so"));
        assertTrue(Files.exists(stale));
    }
}
