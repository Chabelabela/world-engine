package io.github.chabelabela.worldengine.nativebridge;

/** How a planet's terrain is generated. The ids match {@code we_planet::PlanetKind} across the C ABI. */
public enum PlanetKind {
    /** Oceans, climate bands and biomes (the home planet). */
    TERRESTRIAL(0),
    /** Airless, waterless rolling regolith (the moon). */
    BARREN(1);

    private final int id;

    PlanetKind(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
