package io.github.chabelabela.worldengine.spike;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.chabelabela.worldengine.planet.PlanetFace;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * E2 (spike): {@code we_frames::CubeSphere::fold} plus {@code QuarterTurns::apply} / {@code rotate_yaw}, called
 * through the spike-only native function {@code we_spike_cross}. Dev runs only: the library comes from
 * {@code -Dworldengine.native.path}.
 */
final class SeamFold {
    /** The result of carrying a point across a face edge. Grid coordinates are on {@code face}. */
    record Crossed(PlanetFace face, double x, double z, int turns, double vx, double vz, float yaw) {
    }

    private static MethodHandle cross;

    private SeamFold() {
    }

    private static synchronized MethodHandle handle() {
        if (cross == null) {
            String path = System.getProperty("worldengine.native.path");
            if (path == null) {
                throw new IllegalStateException("E2 needs -Dworldengine.native.path (dev runs set it)");
            }
            SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(path), Arena.global());
            cross = Linker.nativeLinker().downcallHandle(
                    lib.find("we_spike_cross").orElseThrow(() -> new IllegalStateException("no we_spike_cross in " + path)),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                            JAVA_FLOAT, ADDRESS));
        }
        return cross;
    }

    /**
     * Folds grid point ({@code x}, {@code z}) of {@code face}, which lies beyond one of its edges, onto the neighbour,
     * rotating the grid velocity ({@code vx}, {@code vz}) and {@code yaw}. Null if it can't be folded (inside the
     * face, beyond a corner, or too far out).
     */
    static Crossed cross(int cells, PlanetFace face, double x, double z, double vx, double vz, float yaw) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, 7);
            int status = (int) handle().invokeExact(cells, face.id(), x, z, vx, vz, yaw, out);
            if (status == 1) {
                return null;
            }
            if (status != 0) {
                throw new IllegalArgumentException("we_spike_cross failed: " + status);
            }
            double[] r = out.toArray(JAVA_DOUBLE);
            return new Crossed(PlanetFace.fromId((int) r[0]), r[1], r[2], (int) r[3], r[4], r[5], (float) r[6]);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
