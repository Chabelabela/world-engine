package io.github.chabelabela.worldengine.planet;

/** Finds dry land near a planet's E1 centre, for the world spawn and {@code /worldengine goto}. */
public final class SpawnSearch {
    /** Height in metres of one terrain column, given its face and cell. */
    @FunctionalInterface
    public interface Heights {
        int height(PlanetFace face, int x, int z);
    }

    /** How far out to look, in chunks: 1,024 blocks, at most 16,641 one-column samples. */
    public static final int MAX_RADIUS_CHUNKS = 64;

    private SpawnSearch() {
    }

    /**
     * The chunk nearest the planet's E1 centre, by square rings, whose centre column is at least 1 m above
     * sea level. Returns {chunkX, chunkZ}; the centre chunk itself if no ring has land.
     */
    public static int[] findLandChunk(PlanetSpec planet, Heights heights, int maxRadiusChunks) {
        int cx0 = Math.floorDiv(planet.centreX(), 16);
        int cz0 = Math.floorDiv(planet.centreZ(), 16);
        for (int r = 0; r <= maxRadiusChunks; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // inside the ring: already checked
                    }
                    int cx = cx0 + dx;
                    int cz = cz0 + dz;
                    FaceCell cell = planet.layout().cellAt(cx * 16 + 8, cz * 16 + 8);
                    if (cell != null && heights.height(cell.face(), cell.x(), cell.z()) >= 1) {
                        return new int[] {cx, cz};
                    }
                }
            }
        }
        return new int[] {cx0, cz0};
    }
}
