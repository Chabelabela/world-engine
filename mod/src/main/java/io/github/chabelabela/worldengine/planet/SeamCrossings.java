package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;

/**
 * What happens to something that leaves a planet's net across a seam: one of the 7 cube edges that the cross-shaped
 * net doesn't join (ADR 0001; E2 in the M1b spike; M1e-2).
 *
 * <p>Beyond each seam lies an overlap band holding a copy of the neighbouring face ({@link SeamBands}). Walking into
 * the band is just walking. At the band's middle, {@link SeamBands#TELEPORT_DEPTH} blocks out, it is carried to the
 * same spot on the neighbouring face, keeping its height, with its grid velocity and yaw turned by the fold's quarter
 * turns. Where two bands meet near a cube corner there is nothing, so it is put back where it last stood.
 */
public final class SeamCrossings {
    /**
     * Something that moved further than this along either axis since its last position was teleported (a command, a
     * portal, a ship handoff), not walking, so it is left alone.
     */
    public static final int MAX_STEP = 16;

    /** Folds grid point (x, z) beyond an edge of {@code face}; null if it can't. In the mod: {@code NativeCore::seamCross}. */
    @FunctionalInterface
    public interface Folder {
        SeamCrossing fold(int cells, PlanetFace face, double x, double z, double vx, double vz, float yaw);
    }

    /** What to do with something in a band or beyond the net. */
    public sealed interface Outcome permits Cross, Bounce {
    }

    /**
     * Carry it to its twin ({@code twinX}, {@code twinZ}) on {@code face}, keeping its height, with horizontal velocity
     * ({@code vx}, {@code vz}) and {@code yaw}. ({@code x}, {@code z}) is one tick of that velocity short of the twin:
     * where a player lands, because a player's client advances once more after a teleport (E2). Things the server
     * moves land on the twin itself.
     */
    public record Cross(PlanetSpec planet, PlanetFace face, double x, double z, double vx, double vz, float yaw,
            double twinX, double twinZ) implements Outcome {
    }

    /** Nowhere to go: put it back at level position ({@code x}, {@code z}) and stop its horizontal movement. */
    public record Bounce(double x, double z) implements Outcome {
    }

    private SeamCrossings() {
    }

    /**
     * The outcome for something now at level position ({@code x}, {@code z}), moving ({@code vx}, {@code vz}) blocks
     * per tick and looking along {@code yaw}, whose last position on a face or in a band was ({@code lastX},
     * {@code lastZ}). Null if it is on a face, in a band short of its middle, moved more than {@link #MAX_STEP}, or its
     * last position wasn't on a face or in a band.
     */
    public static Outcome plan(double lastX, double lastZ, double x, double z, double vx, double vz, float yaw,
            SeamBands bands) {
        if (Math.abs(x - lastX) > MAX_STEP || Math.abs(z - lastZ) > MAX_STEP) {
            return null;
        }
        if (Planets.at(floor(x), floor(z)) != null) {
            return null;
        }
        SeamBand band = bands.at(floor(x), floor(z));
        if (band != null) {
            if (band.depth(x, z) < SeamBands.TELEPORT_DEPTH) {
                return null;
            }
            double[] twin = band.toTwin(x, z);
            double[] v = band.rotateDirection(vx, vz);
            return new Cross(band.planet(), band.twinFace(), twin[0] - v[0], twin[1] - v[1], v[0], v[1],
                    band.rotateYaw(yaw), twin[0], twin[1]);
        }
        if (Planets.at(floor(lastX), floor(lastZ)) == null && bands.at(floor(lastX), floor(lastZ)) == null) {
            return null;
        }
        return new Bounce(lastX, lastZ);
    }

    /** True if {@code hit}'s cell is within {@code margin} blocks of one of its face's four edges. */
    public static boolean nearEdge(PlanetHit hit, int margin) {
        FaceCell cell = hit.cell();
        int n = hit.planet().cells();
        return cell.x() < margin || cell.z() < margin || cell.x() >= n - margin || cell.z() >= n - margin;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
