package io.github.chabelabela.worldengine.planet;

/**
 * A cube face, numbered exactly as {@code we_frames::Face}. The numbers are part of the native ABI
 * ({@code we_terrain_columns} takes them).
 */
public enum PlanetFace {
    E0,
    E1,
    E2,
    E3,
    NORTH,
    SOUTH;

    private static final PlanetFace[] BY_ID = values();

    public int id() {
        return ordinal();
    }

    public static PlanetFace fromId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            throw new IllegalArgumentException("No face " + id);
        }
        return BY_ID[id];
    }
}
