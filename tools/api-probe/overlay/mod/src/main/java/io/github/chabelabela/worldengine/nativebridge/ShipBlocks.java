package io.github.chabelabela.worldengine.nativebridge;

import java.util.Arrays;

/**
 * The blocks of a ship, for {@link NativeCore.PhysicsWorld#addShip}: where each sits on the ship's grid (block
 * (x, y, z) fills [x, x + 1) × [y, y + 1) × [z, z + 1) of the ship's frame), its mass in kilograms, and how it
 * collides. Positions must be distinct and masses positive.
 */
public final class ShipBlocks {
    /** Most blocks a ship may have; keep equal to {@code we_physics::MAX_SHIP_BLOCKS}. */
    public static final int MAX_BLOCKS = 32_768;
    /** Most boxes one block may have; keep equal to {@code we_physics::MAX_BLOCK_BOXES}. */
    public static final int MAX_BOXES_PER_BLOCK = 16;
    /** {@code WE_PHYSICS_SHAPE_FULL}. */
    private static final int FULL = -1;

    private int[] blocks = new int[4 * 16];
    private double[] masses = new double[16];
    private double[] boxes = new double[6 * 16];
    private int count;
    private int boxCount;

    /** A full cube at ({@code x}, {@code y}, {@code z}). */
    public ShipBlocks full(int x, int y, int z, double mass) {
        return add(x, y, z, mass, FULL);
    }

    /**
     * A block at ({@code x}, {@code y}, {@code z}) that collides as the given boxes, six numbers each (min x, y, z,
     * then max x, y, z) in the block's frame: 0 to 1, or up to 1 beyond for fences and walls. Its mass is spread
     * over the boxes.
     */
    public ShipBlocks boxes(int x, int y, int z, double mass, double... minMax) {
        int n = minMax.length / 6;
        if (minMax.length % 6 != 0 || n < 1 || n > MAX_BOXES_PER_BLOCK) {
            throw new IllegalArgumentException("A block has 1 to " + MAX_BOXES_PER_BLOCK + " boxes of 6 numbers");
        }
        if (6 * (boxCount + n) > boxes.length) {
            boxes = Arrays.copyOf(boxes, Math.max(2 * boxes.length, 6 * (boxCount + n)));
        }
        System.arraycopy(minMax, 0, boxes, 6 * boxCount, minMax.length);
        boxCount += n;
        return add(x, y, z, mass, n);
    }

    /** A block at ({@code x}, {@code y}, {@code z}) that doesn't collide (a torch, a flower); its mass still counts. */
    public ShipBlocks empty(int x, int y, int z, double mass) {
        return add(x, y, z, mass, 0);
    }

    private ShipBlocks add(int x, int y, int z, double mass, int shape) {
        if (count == MAX_BLOCKS) {
            throw new IllegalStateException("A ship has at most " + MAX_BLOCKS + " blocks");
        }
        if (4 * (count + 1) > blocks.length) {
            blocks = Arrays.copyOf(blocks, 2 * blocks.length);
            masses = Arrays.copyOf(masses, 2 * masses.length);
        }
        blocks[4 * count] = x;
        blocks[4 * count + 1] = y;
        blocks[4 * count + 2] = z;
        blocks[4 * count + 3] = shape;
        masses[count] = mass;
        count++;
        return this;
    }

    public int size() {
        return count;
    }

    int boxCount() {
        return boxCount;
    }

    /** Four ints per block: x, y, z, and -1 (full), 0 (no collision) or its number of boxes. */
    int[] blockArray() {
        return Arrays.copyOf(blocks, 4 * count);
    }

    double[] massArray() {
        return Arrays.copyOf(masses, count);
    }

    double[] boxArray() {
        return Arrays.copyOf(boxes, 6 * boxCount);
    }
}
