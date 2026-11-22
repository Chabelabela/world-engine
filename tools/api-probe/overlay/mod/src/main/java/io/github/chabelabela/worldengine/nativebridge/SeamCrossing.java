package io.github.chabelabela.worldengine.nativebridge;

/**
 * Where a point that left its face across a seam edge lands ({@code we_seam_cross}): the new face (0-5, numbered as
 * {@code we_frames::Face}), the grid point on it, the fold's clockwise quarter turns, and the grid velocity and yaw
 * rotated by them.
 */
public record SeamCrossing(int face, double x, double z, int turns, double vx, double vz, float yaw) {
}
