package io.github.chabelabela.worldengine.nativebridge;

import java.util.Arrays;

/**
 * One 16x16x16 terrain section, for {@link NativeCore.PhysicsWorld#setSection}: which of its blocks are full cubes,
 * and the boxes of the others that collide (slabs, stairs, fences).
 */
public final class TerrainSection {
    private final long[] full = new long[64];
    private double[] boxes = new double[0];
    private int boxCount;

    /** Marks block ({@code x}, {@code y}, {@code z}) of the section, each 0-15, as a full cube. */
    public TerrainSection full(int x, int y, int z) {
        if ((x | y | z) >>> 4 != 0) {
            throw new IllegalArgumentException("(" + x + ", " + y + ", " + z + ") is outside a section");
        }
        int i = (y << 8) | (z << 4) | x; // Minecraft's section order
        full[i >>> 6] |= 1L << (i & 63);
        return this;
    }

    /** Adds one box in the section's frame: 0 to 16 on every axis, or up to 1 beyond for fences and walls. */
    public TerrainSection box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (6 * (boxCount + 1) > boxes.length) {
            boxes = Arrays.copyOf(boxes, Math.max(48, 2 * boxes.length));
        }
        double[] b = {minX, minY, minZ, maxX, maxY, maxZ};
        System.arraycopy(b, 0, boxes, 6 * boxCount, 6);
        boxCount++;
        return this;
    }

    /** True if nothing in the section collides. */
    public boolean isEmpty() {
        return boxCount == 0 && Arrays.stream(full).allMatch(w -> w == 0);
    }

    long[] fullMask() {
        return full.clone();
    }

    int boxCount() {
        return boxCount;
    }

    double[] boxArray() {
        return Arrays.copyOf(boxes, 6 * boxCount);
    }
}
