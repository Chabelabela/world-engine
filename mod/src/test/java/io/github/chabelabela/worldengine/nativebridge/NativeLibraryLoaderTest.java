package io.github.chabelabela.worldengine.nativebridge;

import static io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader.OVERRIDE_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

    private static final String CURRENT = "aaaaaaaaaaaaaaaa";
    private static final String STALE = "bbbbbbbbbbbbbbbb";
    private static final String LIB = "libworldengine_core.so";

    private static Path currentLibrary(Path cache) throws IOException {
        Path current = Files.createDirectories(cache.resolve(CURRENT)).resolve(LIB);
        Files.writeString(current, "current");
        return current;
    }

    @Test
    void prunesOtherCacheFoldersButKeepsTheCurrentOne(@TempDir Path cache) throws IOException {
        Path current = currentLibrary(cache);
        Path stale = Files.createDirectories(cache.resolve(STALE));
        Files.writeString(stale.resolve(LIB), "old");
        NativeLibraryLoader.pruneStaleCaches(cache, current);
        assertTrue(Files.exists(current));
        assertFalse(Files.exists(stale));
    }

    @Test
    void pruneRemovesNestedStaleFolders(@TempDir Path cache) throws IOException {
        Path current = currentLibrary(cache);
        Path stale = cache.resolve(STALE);
        Files.createDirectories(stale.resolve("a/b/c"));
        Files.writeString(stale.resolve("a/b/c/deep.bin"), "x");
        Files.writeString(stale.resolve("a/top.bin"), "x");
        Files.writeString(stale.resolve(LIB), "old");
        NativeLibraryLoader.pruneStaleCaches(cache, current);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(current));
    }

    @Test
    void pruneLeavesNonHashFoldersAndFilesAlone(@TempDir Path cache) throws IOException {
        Path current = currentLibrary(cache);
        Path notes = Files.createDirectories(cache.resolve("my-notes"));
        Files.writeString(notes.resolve("todo.txt"), "keep");
        Path upper = Files.createDirectories(cache.resolve("BBBBBBBBBBBBBBBB"));
        Path file = cache.resolve("cccccccccccccccc");
        Files.writeString(file, "a regular file named like a hash");
        NativeLibraryLoader.pruneStaleCaches(cache, current);
        assertTrue(Files.exists(notes.resolve("todo.txt")));
        assertTrue(Files.exists(upper));
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void pruneDoesNotFollowSymlinksOutOfTheCache(@TempDir Path cache, @TempDir Path outside) throws IOException {
        Path current = currentLibrary(cache);
        Path precious = Files.createDirectories(outside.resolve("precious"));
        Files.writeString(precious.resolve("data.txt"), "keep");
        Path link = cache.resolve(STALE);
        try {
            Files.createSymbolicLink(link, precious);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "cannot create symbolic links here: " + e);
        }
        NativeLibraryLoader.pruneStaleCaches(cache, current);
        assertTrue(Files.exists(precious.resolve("data.txt")));
        assertTrue(Files.isDirectory(precious));
    }

    @Test
    void pruneDoesNotThrowForAMissingCacheDir(@TempDir Path root) {
        Path cache = root.resolve("absent");
        assertDoesNotThrow(() -> NativeLibraryLoader.pruneStaleCaches(cache, cache.resolve(CURRENT).resolve(LIB)));
    }

    @Test
    void pruneDoesNotThrowWhenSomethingCannotBeDeleted(@TempDir Path cache) throws IOException {
        Assumptions.assumeTrue(cache.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "needs POSIX permissions");
        Path current = currentLibrary(cache);
        Path locked = Files.createDirectories(cache.resolve(STALE).resolve("locked"));
        Files.writeString(locked.resolve("f.bin"), "x");
        try {
            Files.setPosixFilePermissions(locked, Set.of());
            assertDoesNotThrow(() -> NativeLibraryLoader.pruneStaleCaches(cache, current));
        } finally {
            if (Files.exists(locked, LinkOption.NOFOLLOW_LINKS)) { // gone if the tests run as root
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    @Test
    void pruneLeavesTheCacheAloneForLibrariesOutsideIt(@TempDir Path cache, @TempDir Path elsewhere) throws IOException {
        Path stale = Files.createDirectories(cache.resolve(STALE));
        NativeLibraryLoader.pruneStaleCaches(cache, elsewhere.resolve(LIB));
        assertTrue(Files.exists(stale));
    }
}
