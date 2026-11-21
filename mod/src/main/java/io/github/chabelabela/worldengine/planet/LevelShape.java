package io.github.chabelabela.worldengine.planet;

/**
 * The universe level's vertical layout. Must match {@code data/worldengine/dimension_type/universe.json}
 * (a unit test checks).
 *
 * <p>Terrain heights are metres above the datum (sea level) and lie within
 * {@code TerrainColumns.MIN_HEIGHT..MAX_HEIGHT} (-400..700). A column of height {@code h} has its top solid
 * block at {@code SEA_LEVEL - 1 + h}, so {@code h = 0} is a beach level with the water surface and
 * {@code h < 0} is under water. This gives top blocks from y = -338 to y = 762.
 */
public final class LevelShape {
    /** Lowest block y: 46 blocks of rock under the deepest possible sea floor. Bedrock sits here. */
    public static final int MIN_Y = -384;
    /** Blocks from MIN_Y to the build limit: the top is y = 895, 133 blocks above the highest peak. */
    public static final int HEIGHT = 1280;
    /** Vanilla's convention: water fills every y below this on terrestrial planets. */
    public static final int SEA_LEVEL = 63;

    private LevelShape() {
    }

    /** Highest buildable y. */
    public static int maxY() {
        return MIN_Y + HEIGHT - 1;
    }

    /** The top solid block's y for a column {@code height} metres above the datum. */
    public static int surfaceY(int height) {
        return SEA_LEVEL - 1 + height;
    }
}
