package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;

/**
 * Turns one terrain column (height and biome from the core) into blocks: bedrock at the bottom, stone,
 * three blocks of biome subsoil, a biome top block, then water up to sea level on terrestrial planets.
 */
public final class ColumnMaterials {
    /** Blocks of subsoil (dirt, sand, ...) between the top block and stone. */
    public static final int SUBSOIL_DEPTH = 3;

    private ColumnMaterials() {
    }

    /** The highest non-air block's y: the surface, or the water surface if that is higher. */
    public static int topY(int height, PlanetKind kind) {
        int surface = LevelShape.surfaceY(height);
        return hasOceans(kind) ? Math.max(surface, LevelShape.SEA_LEVEL - 1) : surface;
    }

    /** The block at {@code y} in a column {@code height} metres above the datum. */
    public static Ground at(int y, int height, PlanetBiome biome, PlanetKind kind) {
        if (y < LevelShape.MIN_Y || y > LevelShape.maxY()) {
            return Ground.AIR;
        }
        if (y == LevelShape.MIN_Y) {
            return Ground.BEDROCK;
        }
        int surface = LevelShape.surfaceY(height);
        if (y > surface) {
            return hasOceans(kind) && y < LevelShape.SEA_LEVEL ? Ground.WATER : Ground.AIR;
        }
        int depth = surface - y;
        if (depth == 0) {
            return top(biome, height < 0);
        }
        if (depth <= SUBSOIL_DEPTH) {
            return subsoil(biome);
        }
        return Ground.STONE;
    }

    private static boolean hasOceans(PlanetKind kind) {
        return kind == PlanetKind.TERRESTRIAL;
    }

    private static Ground top(PlanetBiome biome, boolean underWater) {
        return switch (biome) {
            case DEEP_OCEAN, BARREN -> Ground.GRAVEL;
            case OCEAN, BEACH, DESERT -> Ground.SAND;
            case MOUNTAINS -> Ground.STONE;
            case SNOWY_PEAKS -> Ground.SNOW;
            case PLAINS, FOREST, TAIGA, TUNDRA, SAVANNA, JUNGLE -> underWater ? Ground.DIRT : Ground.GRASS;
        };
    }

    private static Ground subsoil(PlanetBiome biome) {
        return switch (biome) {
            case DEEP_OCEAN -> Ground.GRAVEL;
            case OCEAN, BEACH -> Ground.SAND;
            case DESERT -> Ground.SANDSTONE;
            case MOUNTAINS, SNOWY_PEAKS, BARREN -> Ground.STONE;
            case PLAINS, FOREST, TAIGA, TUNDRA, SAVANNA, JUNGLE -> Ground.DIRT;
        };
    }
}
