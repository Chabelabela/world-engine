package io.github.chabelabela.worldengine.nativebridge;

import java.util.Objects;

/**
 * A row-major block of terrain columns from {@link NativeCore#terrainColumns}: index
 * {@code dz * countX + dx}.
 */
public record TerrainColumns(int countX, int countZ, int[] heights, byte[] biomes) {

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
}
