package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import java.util.List;

/**
 * The planets in the universe level. Their nets are far apart, so a column belongs to at most one planet.
 * The home planet's face E1 is centred on the level's origin.
 */
public final class Planets {
    /** ROADMAP §4.4: 16,384 blocks per face edge, radius 10,430 m. */
    public static final PlanetSpec HOME = new PlanetSpec(
            "home", 0x9E3779B97F4A7C15L, PlanetKind.TERRESTRIAL, new PlanetLayout(-24_576, -8_192, 16_384));
    /** 4,096 blocks per face edge, radius 2,608 m. */
    public static final PlanetSpec MOON = new PlanetSpec(
            "moon", 0xD1B54A32D192ED03L, PlanetKind.BARREN, new PlanetLayout(100_000, -2_048, 4_096));
    public static final List<PlanetSpec> ALL = List.of(HOME, MOON);

    private Planets() {
    }

    /** The planet and cell under block column ({@code blockX}, {@code blockZ}), or null for empty space. */
    public static PlanetHit at(int blockX, int blockZ) {
        for (PlanetSpec planet : ALL) {
            FaceCell cell = planet.layout().cellAt(blockX, blockZ);
            if (cell != null) {
                return new PlanetHit(planet, cell);
            }
        }
        return null;
    }

    /** The planet called {@code name}, or null. */
    public static PlanetSpec byName(String name) {
        for (PlanetSpec planet : ALL) {
            if (planet.name().equals(name)) {
                return planet;
            }
        }
        return null;
    }
}
