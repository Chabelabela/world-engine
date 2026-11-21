package io.github.chabelabela.worldengine.planet;

/**
 * The terrain and ABI versions a world was generated with. Version 0 means "not stamped yet".
 */
public record TerrainStamp(int terrainVersion, int abiVersion) {
    public static final TerrainStamp NONE = new TerrainStamp(0, 0);

    public boolean isStamped() {
        return terrainVersion != 0;
    }

    /**
     * A warning if chunks generated now won't match chunks generated with {@code this} stamp, else null.
     * A changed ABI alone is harmless: only the terrain model decides what blocks a chunk gets.
     */
    public String warningFor(TerrainStamp current) {
        if (!isStamped() || terrainVersion == current.terrainVersion) {
            return null;
        }
        return "This world's terrain was generated with terrain version " + terrainVersion
                + ", but this World Engine generates version " + current.terrainVersion
                + ". New chunks will not line up with old ones at their borders.";
    }
}
