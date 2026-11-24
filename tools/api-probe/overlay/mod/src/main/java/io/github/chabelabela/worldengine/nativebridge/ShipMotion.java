package io.github.chabelabela.worldengine.nativebridge;

/**
 * Where a ship is and how it moves: the position of its frame's origin ({@code x}, {@code y}, {@code z}, world
 * coordinates), the unit quaternion ({@code qx}, {@code qy}, {@code qz}, {@code qw}) that turns its frame into the
 * world's, the velocity of its centre of mass ({@code vx}, {@code vy}, {@code vz}, m/s), and its angular velocity
 * ({@code wx}, {@code wy}, {@code wz}, rad/s about the world axes).
 */
public record ShipMotion(
        double x, double y, double z,
        double qx, double qy, double qz, double qw,
        double vx, double vy, double vz,
        double wx, double wy, double wz) {
    /** Doubles in the native layout. */
    static final int LENGTH = 13;

    /** At rest at ({@code x}, {@code y}, {@code z}), unrotated. */
    public static ShipMotion at(double x, double y, double z) {
        return new ShipMotion(x, y, z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
    }

    double[] toArray() {
        return new double[] {x, y, z, qx, qy, qz, qw, vx, vy, vz, wx, wy, wz};
    }

    static ShipMotion fromArray(double[] a, int offset) {
        return new ShipMotion(a[offset], a[offset + 1], a[offset + 2], a[offset + 3], a[offset + 4], a[offset + 5],
                a[offset + 6], a[offset + 7], a[offset + 8], a[offset + 9], a[offset + 10], a[offset + 11],
                a[offset + 12]);
    }
}
