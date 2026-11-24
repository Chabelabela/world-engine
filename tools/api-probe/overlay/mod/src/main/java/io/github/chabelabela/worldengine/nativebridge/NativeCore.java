package io.github.chabelabela.worldengine.nativebridge;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Java view of the Rust core's C ABI ({@code core/crates/we-ffi/src/lib.rs}).
 * Keep the descriptors here in lock-step with the Rust signatures and bump
 * {@link #EXPECTED_ABI_VERSION} together with {@code WE_ABI_VERSION}.
 */
public final class NativeCore {
    public static final int EXPECTED_ABI_VERSION = 6;

    /** Largest region {@link #terrainColumns} accepts, in columns. */
    public static final int MAX_COLUMNS = 1 << 20; // keep equal to we_planet::MAX_COLUMNS
    /** {@code WE_SEAM_NOT_FOLDABLE}: {@code we_seam_cross} left the point where it is. */
    private static final int SEAM_NOT_FOLDABLE = 1;
    /** Doubles per ship record from {@code we_physics_read_ships}: the id, the motion, then 1 if asleep. */
    private static final int SHIP_RECORD = 15;

    private final MethodHandle abiVersionFn;
    private final MethodHandle newDemoFn;
    private final MethodHandle freeFn;
    private final MethodHandle setTimeFn;
    private final MethodHandle bodyCountFn;
    private final MethodHandle bodyPositionFn;
    private final MethodHandle bodyNameFn;
    private final MethodHandle terrainColumnsFn;
    private final MethodHandle terrainVersionFn;
    private final MethodHandle seamCrossFn;
    private final MethodHandle localSkyFn;
    private final MethodHandle physicsNewFn;
    private final MethodHandle physicsFreeFn;
    private final MethodHandle setSectionFn;
    private final MethodHandle clearSectionFn;
    private final MethodHandle addShipFn;
    private final MethodHandle removeShipFn;
    private final MethodHandle setShipMotionFn;
    private final MethodHandle setShipGravityFn;
    private final MethodHandle setShipControlsFn;
    private final MethodHandle stepFn;
    private final MethodHandle shipCountFn;
    private final MethodHandle readShipsFn;
    private final MethodHandle shipMassFn;

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
        terrainVersionFn = linker.downcallHandle(find(lib, "we_terrain_version"), FunctionDescriptor.of(JAVA_INT));
        seamCrossFn = linker.downcallHandle(
                find(lib, "we_seam_cross"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                        JAVA_FLOAT, ADDRESS));
        localSkyFn = linker.downcallHandle(
                find(lib, "we_local_sky"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
        physicsNewFn = linker.downcallHandle(find(lib, "we_physics_new"), FunctionDescriptor.of(ADDRESS));
        physicsFreeFn = linker.downcallHandle(find(lib, "we_physics_free"), FunctionDescriptor.ofVoid(ADDRESS));
        setSectionFn = linker.downcallHandle(
                find(lib, "we_physics_set_section"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        clearSectionFn = linker.downcallHandle(
                find(lib, "we_physics_clear_section"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        addShipFn = linker.downcallHandle(
                find(lib, "we_physics_add_ship"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT));
        removeShipFn = linker.downcallHandle(
                find(lib, "we_physics_remove_ship"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        setShipMotionFn = linker.downcallHandle(
                find(lib, "we_physics_set_ship_motion"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        setShipGravityFn = linker.downcallHandle(
                find(lib, "we_physics_set_ship_gravity"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE));
        setShipControlsFn = linker.downcallHandle(
                find(lib, "we_physics_set_ship_controls"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        stepFn = linker.downcallHandle(
                find(lib, "we_physics_step"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        shipCountFn = linker.downcallHandle(
                find(lib, "we_physics_ship_count"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
        readShipsFn = linker.downcallHandle(
                find(lib, "we_physics_read_ships"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        shipMassFn = linker.downcallHandle(
                find(lib, "we_physics_ship_mass"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
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
        if (t instanceof Error e) {
            throw e; // OutOfMemoryError, StackOverflowError etc. must not be disguised
        }
        return t instanceof NativeCoreException e ? e : new NativeCoreException("Native call failed", t);
    }

    private static void check(int status, String call) {
        if (status < 0) {
            throw new NativeCoreException(call + " failed with status " + status);
        }
    }

    /** Like {@link #check}, naming the statuses a physics call about ship {@code id} can return. */
    private static void checkShip(int status, String call, int id) {
        switch (status) {
            case -2 -> throw new NativeCoreException(call + ": no ship " + id);
            case -3 -> throw new NativeCoreException(call + ": invalid arguments for ship " + id);
            case -4 -> throw new NativeCoreException(call + ": ship " + id + " already exists");
            default -> check(status, call);
        }
    }

    /** A segment holding {@code values}, or NULL if there are none (the core accepts NULL for an empty array). */
    private static MemorySegment doubles(Arena arena, double[] values) {
        return values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_DOUBLE, values);
    }

    public int abiVersion() {
        try {
            return (int) abiVersionFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** The terrain model's version ({@code we_planet::TERRAIN_VERSION}); worlds record it. */
    public int terrainVersion() {
        try {
            return (int) terrainVersionFn.invokeExact();
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
        Objects.requireNonNull(kind, "kind");
        if (countX < 0 || countZ < 0) {
            throw new NativeCoreException("Negative region size " + countX + " x " + countZ);
        }
        long n = (long) countX * countZ;
        if (n > MAX_COLUMNS) {
            throw new NativeCoreException("Region of " + n + " columns exceeds " + MAX_COLUMNS);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment heights = arena.allocate(JAVA_INT, Math.max((int) n, 1));
            MemorySegment biomes = arena.allocate(JAVA_BYTE, Math.max((int) n, 1));
            check((int) terrainColumnsFn.invokeExact(
                    seed, cells, kind.id(), face, x0, z0, countX, countZ, heights, biomes),
                    "we_terrain_columns");
            return new TerrainColumns(countX, countZ,
                    heights.asSlice(0, n * Integer.BYTES).toArray(JAVA_INT),
                    biomes.asSlice(0, n).toArray(JAVA_BYTE));
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Carries grid point ({@code x}, {@code z}) of {@code face} (0-5), which lies beyond one of the face's edges, onto
     * the neighbouring face of a planet with {@code cells} blocks per face edge, rotating the grid velocity
     * ({@code vx} east, {@code vz} south) and the Minecraft {@code yaw} with it. Returns null if the point can't be
     * folded: it is on its face, beyond a corner, or more than one face width out.
     *
     * <p>Stateless and thread-safe, like {@link #terrainColumns}.
     */
    public SeamCrossing seamCross(int cells, int face, double x, double z, double vx, double vz, float yaw) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, 7);
            int status = (int) seamCrossFn.invokeExact(cells, face, x, z, vx, vz, yaw, out);
            if (status == SEAM_NOT_FOLDABLE) {
                return null;
            }
            check(status, "we_seam_cross");
            double[] r = out.toArray(JAVA_DOUBLE);
            return new SeamCrossing((int) r[0], r[1], r[2], (int) r[3], r[4], r[5], (float) r[6]);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * The sky over grid point ({@code x}, {@code z}) of {@code face} (0-5) on a planet with {@code cells} blocks per
     * face edge: its longitude and latitude, and the tilt that turns Minecraft's equatorial sky into the sky there.
     *
     * <p>Stateless and thread-safe, like {@link #terrainColumns}.
     */
    public LocalSky localSky(int cells, int face, double x, double z) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, 11);
            check((int) localSkyFn.invokeExact(cells, face, x, z, out), "we_local_sky");
            double[] r = out.toArray(JAVA_DOUBLE);
            return new LocalSky(r[0], r[1], Arrays.copyOfRange(r, 2, 11));
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

    /** A new, empty physics world (we-physics): terrain sections and ships, stepped at 60 Hz. */
    public PhysicsWorld newPhysicsWorld() {
        MemorySegment handle;
        try {
            handle = (MemorySegment) physicsNewFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new NativeCoreException("we_physics_new returned null");
        }
        return new PhysicsWorld(handle);
    }

    /**
     * Owns one native physics world: terrain fed a 16x16x16 section at a time, and ships, each a rigid body made of
     * its blocks. Not thread-safe: calls must not overlap, though they may come from different threads in turn
     * ({@code physics.PhysicsThread} keeps it on one). It holds native memory, so always {@link #close()} it; there
     * is no cleaner.
     */
    public final class PhysicsWorld implements AutoCloseable {
        private MemorySegment handle;

        private PhysicsWorld(MemorySegment handle) {
            this.handle = handle;
        }

        private MemorySegment live() {
            if (handle == null) {
                throw new IllegalStateException("Physics world already closed");
            }
            return handle;
        }

        /**
         * Sets terrain section ({@code sx}, {@code sy}, {@code sz}), replacing what was there, and wakes ships near
         * it.
         */
        public void setSection(int sx, int sy, int sz, TerrainSection section) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment full = arena.allocateFrom(JAVA_LONG, section.fullMask());
                MemorySegment boxes = doubles(arena, section.boxArray());
                check((int) setSectionFn.invokeExact(h, sx, sy, sz, full, boxes, section.boxCount()),
                        "we_physics_set_section(" + sx + ", " + sy + ", " + sz + ")");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Removes terrain section ({@code sx}, {@code sy}, {@code sz}), if it was set. */
        public void clearSection(int sx, int sy, int sz) {
            MemorySegment h = live();
            try {
                check((int) clearSectionFn.invokeExact(h, sx, sy, sz), "we_physics_clear_section");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Adds ship {@code id}, made of {@code blocks}, moving as {@code motion} says. */
        public void addShip(int id, ShipMotion motion, ShipBlocks blocks) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = arena.allocateFrom(JAVA_DOUBLE, motion.toArray());
                int[] codes = blocks.blockArray();
                MemorySegment b = codes.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, codes);
                MemorySegment masses = doubles(arena, blocks.massArray());
                MemorySegment boxes = doubles(arena, blocks.boxArray());
                checkShip((int) addShipFn.invokeExact(h, id, m, b, masses, blocks.size(), boxes, blocks.boxCount()),
                        "we_physics_add_ship", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        public void removeShip(int id) {
            MemorySegment h = live();
            try {
                checkShip((int) removeShipFn.invokeExact(h, id), "we_physics_remove_ship", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Moves ship {@code id} to {@code motion} at once (a teleport), and wakes it. */
        public void setShipMotion(int id, ShipMotion motion) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = arena.allocateFrom(JAVA_DOUBLE, motion.toArray());
                checkShip((int) setShipMotionFn.invokeExact(h, id, m), "we_physics_set_ship_motion", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Ship {@code id}'s gravity, in m/s² along -Y; a new ship has 9.81. */
        public void setShipGravity(int id, double gravity) {
            MemorySegment h = live();
            try {
                checkShip((int) setShipGravityFn.invokeExact(h, id, gravity), "we_physics_set_ship_gravity", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /**
         * The forces and the torque ({@code tx}, {@code ty}, {@code tz}, N m), all in the ship's frame, that act on
         * ship {@code id} at every step until they are set again. No forces and no torque leave it coasting.
         */
        public void setShipControls(int id, List<ShipForce> forces, double tx, double ty, double tz) {
            MemorySegment h = live();
            double[] f = new double[6 * forces.size()];
            for (int i = 0; i < forces.size(); i++) {
                ShipForce force = forces.get(i);
                System.arraycopy(new double[] {force.fx(), force.fy(), force.fz(), force.px(), force.py(), force.pz()},
                        0, f, 6 * i, 6);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment torque = arena.allocateFrom(JAVA_DOUBLE, tx, ty, tz);
                checkShip((int) setShipControlsFn.invokeExact(h, id, doubles(arena, f), forces.size(), torque),
                        "we_physics_set_ship_controls", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Advances {@code steps} steps of 1/60 s each (at most 600). */
        public void step(int steps) {
            MemorySegment h = live();
            try {
                check((int) stepFn.invokeExact(h, steps), "we_physics_step(" + steps + ")");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Every ship's state, by id. */
        public List<ShipState> ships() {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                int count = (int) shipCountFn.invokeExact(h);
                check(count, "we_physics_ship_count");
                if (count == 0) {
                    return List.of();
                }
                MemorySegment out = arena.allocate(JAVA_DOUBLE, (long) SHIP_RECORD * count);
                check((int) readShipsFn.invokeExact(h, out, count), "we_physics_read_ships");
                double[] r = out.toArray(JAVA_DOUBLE);
                List<ShipState> ships = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int o = SHIP_RECORD * i;
                    ships.add(new ShipState((int) (long) r[o], ShipMotion.fromArray(r, o + 1), r[o + 14] != 0));
                }
                return List.copyOf(ships);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Ship {@code id}'s mass properties, summed from its blocks. */
        public ShipMass shipMass(int id) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE, 13);
                checkShip((int) shipMassFn.invokeExact(h, id, out), "we_physics_ship_mass", id);
                double[] r = out.toArray(JAVA_DOUBLE);
                return new ShipMass(r[0], r[1], r[2], r[3], Arrays.copyOfRange(r, 4, 13));
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
                physicsFreeFn.invokeExact(h);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }
    }
}
