package io.github.chabelabela.worldengine.planet;

import java.util.Objects;

/**
 * One overlap band: the strip of level space {@link SeamBands#WIDTH} blocks deep beyond a seam edge of {@code face},
 * holding a copy of the neighbouring face {@code twinFace} along the same cube edge, turned by {@code turns}
 * clockwise quarter turns. The band's blocks are generated from, and kept in step with, that strip of the twin face.
 *
 * <p>Across a seam the cube unfolds rigidly, so a level point p in the band and its twin q are related by a rotation
 * and a translation: q = R(turns)·p + (tx, tz). Block columns map to block columns, through their centres.
 *
 * @param minX first level x of the band (inclusive); {@code maxX} is exclusive, and likewise for z
 */
public record SeamBand(PlanetSpec planet, PlanetFace face, Side side, PlanetFace twinFace, int turns,
        int minX, int minZ, int maxX, int maxZ, double tx, double tz) {
    /** A face's edge, on its block grid: x grows east, z grows south. */
    public enum Side {
        WEST, EAST, NORTH, SOUTH
    }

    public SeamBand {
        Objects.requireNonNull(planet, "planet");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(twinFace, "twinFace");
        if (turns < 0 || turns > 3) {
            throw new IllegalArgumentException("turns must be 0-3, got " + turns);
        }
    }

    /** True if block column (x, z) lies in this band's rectangle. */
    public boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    /** How far level point (x, z) lies beyond the face edge, in blocks: 0 at the edge, {@link SeamBands#WIDTH} at the far side. */
    public double depth(double x, double z) {
        PlanetLayout layout = planet.layout();
        return switch (side) {
            case WEST -> layout.faceOriginX(face) - x;
            case EAST -> x - (layout.faceOriginX(face) + planet.cells());
            case NORTH -> layout.faceOriginZ(face) - z;
            case SOUTH -> z - (layout.faceOriginZ(face) + planet.cells());
        };
    }

    /** The twin of level point (x, z): where it lies on {@link #twinFace}. Returns {x, z}. */
    public double[] toTwin(double x, double z) {
        double[] r = rotate(turns, x, z);
        return new double[] {r[0] + tx, r[1] + tz};
    }

    /** The band point whose twin is level point (x, z): the inverse of {@link #toTwin}. Returns {x, z}. */
    public double[] fromTwin(double x, double z) {
        return rotate((4 - turns) % 4, x - tx, z - tz);
    }

    /** The twin of block column (x, z), through its centre. Returns {x, z}. */
    public int[] twinBlock(int x, int z) {
        double[] t = toTwin(x + 0.5, z + 0.5);
        return new int[] {(int) Math.floor(t[0]), (int) Math.floor(t[1])};
    }

    /** The band column whose twin is block column (x, z), through its centre. Returns {x, z}. */
    public int[] bandBlock(int x, int z) {
        double[] b = fromTwin(x + 0.5, z + 0.5);
        return new int[] {(int) Math.floor(b[0]), (int) Math.floor(b[1])};
    }

    /** A grid direction (dx east, dz south) turned from the band's grid onto the twin face's. Returns {dx, dz}. */
    public double[] rotateDirection(double dx, double dz) {
        return rotate(turns, dx, dz);
    }

    /**
     * A Minecraft yaw (0 = south, 90 = west) turned like {@link #rotateDirection}, wrapped into [-180, 180). The same
     * f32 arithmetic as {@code we_frames::QuarterTurns::rotate_yaw}.
     */
    public float rotateYaw(float yaw) {
        float r = (yaw + 90.0F * turns + 180.0F) % 360.0F;
        if (r < 0) {
            r += 360.0F;
        }
        return r - 180.0F;
    }

    /** Quarter turns clockwise seen from above: east turns into south. Like {@code we_frames::QuarterTurns::apply}. */
    static double[] rotate(int turns, double x, double z) {
        return switch (turns) {
            case 0 -> new double[] {x, z};
            case 1 -> new double[] {-z, x};
            case 2 -> new double[] {-x, -z};
            default -> new double[] {z, -x};
        };
    }
}
