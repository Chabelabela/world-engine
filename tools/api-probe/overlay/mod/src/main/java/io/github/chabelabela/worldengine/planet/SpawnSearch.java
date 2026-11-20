package io.github.chabelabela.worldengine.planet;

/** Finds dry land near a planet's E1 centre, for the world spawn and {@code /worldengine goto}. */
public final class SpawnSearch {
    /** Height in metres of one terrain column, given its face and cell. */
    @FunctionalInterface
    public interface Heights {
        int height(PlanetFace face, int x, int z);
    }

    /** Spacing of the samples: every 4th chunk (64 blocks) in each direction. */
    public static final int STEP_CHUNKS = 4;
    /**
     * How far out to look, in steps: 128 steps is 8,192 blocks, the whole of the home planet's face E1. Oceans
     * can span more than a kilometre, so a shorter search can miss land. At most 66,049 samples.
     */
    public static final int MAX_RADIUS_STEPS = 128;

    private SpawnSearch() {
    }

    /**
     * The sampled chunk nearest the planet's E1 centre, by square rings of {@link #STEP_CHUNKS}-chunk steps,
     * whose centre column is at least 1 m above sea level. Returns {chunkX, chunkZ}; the centre chunk itself if
     * no ring has land.
     */
    public static int[] findLandChunk(PlanetSpec planet, Heights heights, int maxRadiusSteps) {
        int cx0 = Math.floorDiv(planet.centreX(), 16);
        int cz0 = Math.floorDiv(planet.centreZ(), 16);
        for (int r = 0; r <= maxRadiusSteps; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // inside the ring: already checked
                    }
                    int cx = cx0 + dx * STEP_CHUNKS;
                    int cz = cz0 + dz * STEP_CHUNKS;
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
