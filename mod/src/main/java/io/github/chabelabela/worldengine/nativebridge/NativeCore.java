package io.github.chabelabela.worldengine.nativebridge;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Java view of the Rust core's C ABI ({@code core/crates/we-ffi/src/lib.rs}).
 * Keep the descriptors here in lock-step with the Rust signatures and bump
 * {@link #EXPECTED_ABI_VERSION} together with {@code WE_ABI_VERSION}.
 */
public final class NativeCore {
    public static final int EXPECTED_ABI_VERSION = 2;

    private final MethodHandle abiVersionFn;
    private final MethodHandle newDemoFn;
    private final MethodHandle freeFn;
    private final MethodHandle setTimeFn;
    private final MethodHandle bodyCountFn;
    private final MethodHandle bodyPositionFn;
    private final MethodHandle bodyNameFn;
    private final MethodHandle terrainColumnsFn;

    private NativeCore(SymbolLookup lib) {
        Linker linker = Linker.nativeLinker();
        abiVersionFn = linker.downcallHandle(find(lib, "we_abi_version"), FunctionDescriptor.of(JAVA_INT));
        newDemoFn = linker.downcallHandle(find(lib, "we_universe_new_demo"), FunctionDescriptor.of(ADDRESS));
        freeFn = linker.downcallHandle(find(lib, "we_universe_free"), FunctionDescriptor.ofVoid(ADDRESS));
        setTimeFn = linker.downcallHandle(
                find(lib, "we_universe_set_time"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE));
        bodyCountFn = linker.downcallHandle(
                find(lib, "we_universe_body_count"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bodyPositionFn = linker.downcallHandle(
                find(lib, "we_universe_body_position"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        bodyNameFn = linker.downcallHandle(
                find(lib, "we_universe_body_name"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        terrainColumnsFn = linker.downcallHandle(
                find(lib, "we_terrain_columns"),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    }

    /** Load the library at {@code library} for the lifetime of the JVM and check its ABI version. */
    public static NativeCore load(Path library) {
        SymbolLookup lib;
        try {
            lib = SymbolLookup.libraryLookup(library, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new NativeCoreException("Could not load the World Engine core from " + library, e);
        }
        // Check the version before looking up anything newer, so a stale library
        // reports a version mismatch instead of a missing symbol.
        int abi = readAbiVersion(lib);
        if (abi != EXPECTED_ABI_VERSION) {
            throw new NativeCoreException("World Engine core at " + library + " has ABI v" + abi
                    + ", but this mod needs v" + EXPECTED_ABI_VERSION);
        }
        return new NativeCore(lib);
    }

    private static int readAbiVersion(SymbolLookup lib) {
        MethodHandle abiVersion = Linker.nativeLinker()
                .downcallHandle(find(lib, "we_abi_version"), FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    private static MemorySegment find(SymbolLookup lib, String name) {
        return lib.find(name).orElseThrow(() -> new NativeCoreException("Missing native symbol " + name));
    }

    private static NativeCoreException wrap(Throwable t) {
        return t instanceof NativeCoreException e ? e : new NativeCoreException("Native call failed", t);
    }

    private static void check(int status, String call) {
        if (status < 0) {
            throw new NativeCoreException(call + " failed with status " + status);
        }
    }

    public int abiVersion() {
        try {
            return (int) abiVersionFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Terrain for a {@code countX} by {@code countZ} block of columns whose north-west cell is
     * ({@code x0}, {@code z0}) on {@code face} (0-5, {@code we_frames::Face}) of a planet with
     * {@code cells} blocks per face edge, generated from {@code seed}.
     *
     * <p>Unlike {@link Universe}, this is stateless and thread-safe: world-generation threads may
     * call it concurrently.
     */
    public TerrainColumns terrainColumns(
            long seed, int cells, PlanetKind kind, int face, int x0, int z0, int countX, int countZ) {
        try (Arena arena = Arena.ofConfined()) {
            if (countX < 0 || countZ < 0) {
                throw new NativeCoreException("Negative region size " + countX + " x " + countZ);
            }
            int n = Math.multiplyExact(countX, countZ);
            MemorySegment heights = arena.allocate(JAVA_INT, Math.max(n, 1));
            MemorySegment biomes = arena.allocate(JAVA_BYTE, Math.max(n, 1));
            check((int) terrainColumnsFn.invokeExact(
                    seed, cells, kind.id(), face, x0, z0, countX, countZ, heights, biomes),
                    "we_terrain_columns");
            return new TerrainColumns(countX, countZ,
                    heights.asSlice(0, (long) n * Integer.BYTES).toArray(JAVA_INT),
                    biomes.asSlice(0, n).toArray(JAVA_BYTE));
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** A home planet with a moon; see {@code CelestialSystem::demo} in we-orbit. */
    public Universe newDemoUniverse() {
        MemorySegment handle;
        try {
            handle = (MemorySegment) newDemoFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new NativeCoreException("we_universe_new_demo returned null");
        }
        return new Universe(handle);
    }

    /**
     * Owns one native universe. Not thread-safe: create, use and close it on one
     * thread (the server thread). It holds native memory, so always {@link #close()}
     * it (the mod does this on server stop); there is no cleaner.
     */
    public final class Universe implements AutoCloseable {
        private MemorySegment handle;

        private Universe(MemorySegment handle) {
            this.handle = handle;
        }

        private MemorySegment live() {
            if (handle == null) {
                throw new IllegalStateException("Universe already closed");
            }
            return handle;
        }

        public void setTime(double seconds) {
            MemorySegment h = live();
            try {
                check((int) setTimeFn.invokeExact(h, seconds), "we_universe_set_time");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        public int bodyCount() {
            MemorySegment h = live();
            try {
                return (int) bodyCountFn.invokeExact(h);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Position in metres (Y-up, relative to the root body) at the current time. */
        public double[] bodyPosition(int index) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE, 3);
                check((int) bodyPositionFn.invokeExact(h, index, out), "we_universe_body_position(" + index + ")");
                return out.toArray(JAVA_DOUBLE);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        public String bodyName(int index) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(64);
                int len = (int) bodyNameFn.invokeExact(h, index, buf, (int) buf.byteSize());
                check(len, "we_universe_body_name(" + index + ")");
                if (len > buf.byteSize()) {
                    buf = arena.allocate(len);
                    len = (int) bodyNameFn.invokeExact(h, index, buf, (int) buf.byteSize());
                    check(len, "we_universe_body_name(" + index + ")");
                }
                return new String(buf.asSlice(0, len).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        @Override
        public void close() {
            if (handle == null) {
                return;
            }
            MemorySegment h = handle;
            handle = null;
            try {
                freeFn.invokeExact(h);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }
    }
}
