package io.github.chabelabela.worldengine.nativebridge;

/**
 * Surface biomes from the Rust terrain model. The declaration order is the C ABI id
 * ({@code we_planet::Biome}), so it is frozen: changing it requires bumping the ABI version.
 */
public enum PlanetBiome {
    DEEP_OCEAN,
    OCEAN,
    BEACH,
    PLAINS,
    FOREST,
    TAIGA,
    TUNDRA,
    DESERT,
    SAVANNA,
    JUNGLE,
    MOUNTAINS,
    SNOWY_PEAKS,
    BARREN;

    private static final PlanetBiome[] BY_ID = values();

    /** The biome with C ABI id {@code id}. */
    public static PlanetBiome fromId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            throw new NativeCoreException("Unknown biome id " + id);
        }
        return BY_ID[id];
    }

    public int id() {
        return ordinal();
    }

    public boolean isWater() {
        return this == DEEP_OCEAN || this == OCEAN;
    }
}
