package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;

/**
 * Which Minecraft biome stands in for each planet biome. Vanilla biomes bring their trees, grass, ores and
 * mobs; World Engine adds {@code worldengine:barren} (airless rock) and {@code worldengine:void} (space
 * between and around the planets' nets).
 */
public final class BiomeTable {
    public static final String VOID = "worldengine:void";

    private BiomeTable() {
    }

    public static String biomeId(PlanetBiome biome) {
        return switch (biome) {
            case DEEP_OCEAN -> "minecraft:deep_ocean";
            case OCEAN -> "minecraft:ocean";
            case BEACH -> "minecraft:beach";
            case PLAINS -> "minecraft:plains";
            case FOREST -> "minecraft:forest";
            case TAIGA -> "minecraft:taiga";
            case TUNDRA -> "minecraft:snowy_plains";
            case DESERT -> "minecraft:desert";
            case SAVANNA -> "minecraft:savanna";
            case JUNGLE -> "minecraft:jungle";
            case MOUNTAINS -> "minecraft:stony_peaks";
            case SNOWY_PEAKS -> "minecraft:frozen_peaks";
            case BARREN -> "worldengine:barren";
        };
    }
}
