package io.github.chabelabela.worldengine.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Finds the core library: a dev override path, or the copy bundled in the mod jar. */
public final class NativeLibraryLoader {
    /** Set to an absolute path of a freshly built library to bypass the bundled copy. */
    public static final String OVERRIDE_PROPERTY = "worldengine.native.path";

    /** Length of a cache folder name: the first 8 bytes of SHA-256, as lowercase hex. */
    private static final int HASH_HEX_LENGTH = 16;
    private static final Pattern HASH_FOLDER = Pattern.compile("[0-9a-f]{" + HASH_HEX_LENGTH + "}");

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
     * behind: every hash-named folder ({@value #HASH_HEX_LENGTH} lowercase hex
     * characters) in {@code cacheDir} except the one holding {@code library}.
     * Anything else (user folders, files, symlinks, junctions) is never touched,
     * and links are never followed. Does nothing if {@code library} is not in
     * the cache (the dev override). Failures are ignored: on Windows another
     * running game may still hold an old DLL open, and it will be pruned next
     * time.
     *
     * <p>Known race: two game instances of different mod versions sharing one
     * game directory can prune each other's folders. That is rare, and it
     * degrades to "core unavailable" for that launch, not a crash.
     */
    public static void pruneStaleCaches(Path cacheDir, Path library) {
        Path keep = library.getParent();
        if (keep == null || !cacheDir.equals(keep.getParent())) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheDir)) {
            for (Path entry : entries) {
                try {
                    if (!entry.equals(keep) && isPrunable(entry)) {
                        deleteRecursively(entry);
                    }
                } catch (IOException | RuntimeException e) {
                    // best effort: one bad entry must not stop the rest
                }
            }
        } catch (IOException | RuntimeException e) {
            // best effort (iteration throws DirectoryIteratorException lazily)
        }
    }

    private static boolean isPrunable(Path entry) throws IOException {
        Path name = entry.getFileName();
        if (name == null || !HASH_FOLDER.matcher(name.toString()).matches()) {
            return false;
        }
        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isDirectory() && !attrs.isOther() && !attrs.isSymbolicLink();
    }

    /** Deletes {@code dir} without following links; junctions and their targets are left alone. */
    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return attrs.isOther() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file); // a symlink is removed itself, never its target
                    } catch (IOException | RuntimeException e) {
                        // best effort
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    try {
                        Files.deleteIfExists(d);
                    } catch (IOException | RuntimeException e) {
                        // best effort (non-empty if something inside could not be deleted)
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    private static String shortSha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, HASH_HEX_LENGTH / 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
