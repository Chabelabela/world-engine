package io.github.chabelabela.worldengine.nativebridge;

import java.util.Arrays;
import java.util.Objects;

/**
 * A row-major block of terrain columns from {@link NativeCore#terrainColumns}: index
 * {@code dz * countX + dx}.
 *
 * <p>The arrays are the record's backing storage and are deliberately not defensively copied:
 * copying would create per-chunk garbage on the world-generation hot path. Callers must not
 * modify them.
 */
public record TerrainColumns(int countX, int countZ, int[] heights, byte[] biomes) {

    /**
     * Guaranteed lower bound of {@link #height}, in blocks above the datum. Keep equal to
     * {@code we_planet::MIN_HEIGHT}.
     */
    public static final int MIN_HEIGHT = -400;

    /**
     * Guaranteed upper bound of {@link #height}, in blocks above the datum. Keep equal to
     * {@code we_planet::MAX_HEIGHT}.
     */
    public static final int MAX_HEIGHT = 700;

    public TerrainColumns {
        if (countX < 0 || countZ < 0) {
            throw new IllegalArgumentException("Negative size " + countX + " x " + countZ);
        }
        Objects.requireNonNull(heights, "heights");
        Objects.requireNonNull(biomes, "biomes");
        long n = (long) countX * countZ;
        if (heights.length != n || biomes.length != n) {
            throw new IllegalArgumentException("Expected " + n + " columns for " + countX + " x " + countZ
                    + " but got " + heights.length + " heights and " + biomes.length + " biomes");
        }
    }

    /** Surface height at ({@code dx}, {@code dz}), in whole blocks above the datum (sea level). */
    public int height(int dx, int dz) {
        return heights[index(dx, dz)];
    }

    public PlanetBiome biome(int dx, int dz) {
        return PlanetBiome.fromId(Byte.toUnsignedInt(biomes[index(dx, dz)]));
    }

    private int index(int dx, int dz) {
        Objects.checkIndex(dx, countX);
        Objects.checkIndex(dz, countZ);
        return dz * countX + dx;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TerrainColumns t
                && countX == t.countX
                && countZ == t.countZ
                && Arrays.equals(heights, t.heights)
                && Arrays.equals(biomes, t.biomes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * countX + countZ) + Arrays.hashCode(heights)) + Arrays.hashCode(biomes);
    }

    @Override
    public String toString() {
        return "TerrainColumns[" + countX + " x " + countZ + "]";
    }
}
