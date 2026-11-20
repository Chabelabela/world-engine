package io.github.chabelabela.worldengine.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/** Finds the core library: a dev override path, or the copy bundled in the mod jar. */
public final class NativeLibraryLoader {
    /** Set to an absolute path of a freshly built library to bypass the bundled copy. */
    public static final String OVERRIDE_PROPERTY = "worldengine.native.path";

    private NativeLibraryLoader() {}

    /**
     * Returns a filesystem path to the core library for this platform. The bundled
     * copy is extracted to {@code cacheDir/<content-hash>/} once and reused.
     */
    public static Path locate(Path cacheDir) {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        NativePlatform platform = NativePlatform.current();
        String resource = platform.resourcePath();
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new NativeCoreException(
                        "No World Engine native library bundled for " + platform.key() + " (looked for " + resource + ")");
            }
            byte[] bytes = in.readAllBytes();
            Path dir = cacheDir.resolve(shortSha256(bytes));
            Path target = dir.resolve(platform.libraryFileName());
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "extract", ".tmp");
                try {
                    Files.write(tmp, bytes);
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    if (!Files.exists(target)) {
                        throw e; // a concurrent launch winning the race is fine; anything else is not
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract the World Engine native library", e);
        }
    }

    /**
     * Best-effort removal of the cache folders other versions of the core left
     * behind: every folder in {@code cacheDir} except the one holding
     * {@code library}. Does nothing if {@code library} is not in the cache (the
     * dev override). Failures are ignored: on Windows another running game may
     * still hold an old DLL open, and it will be pruned next time.
     */
    public static void pruneStaleCaches(Path cacheDir, Path library) {
        Path keep = library.getParent();
        if (keep == null || !cacheDir.equals(keep.getParent())) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheDir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && !entry.equals(keep)) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException e) {
            // best effort
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best effort
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    private static String shortSha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
