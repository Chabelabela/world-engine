package io.github.chabelabela.worldengine.planet;

/**
 * Where a planet's six faces sit in the level: the cross-shaped net of {@code we_frames::PlanetLayout}.
 *
 * <p>E0, E1, E2 and E3 lie in a row starting at ({@code originX}, {@code originZ}), with North above E1
 * and South below it. Each face is {@code cells} by {@code cells} blocks, and block
 * (faceOriginX + x, faceOriginZ + z) is cell (x, z) of that face: a pure translation, no rotation.
 *
 * <p>Origins and {@code cells} are multiples of 16, so every chunk lies on exactly one face or on none.
 */
public record PlanetLayout(int originX, int originZ, int cells) {
    /** Blocks from the level's centre to the vanilla world border's edge. The whole net stays inside. */
    public static final int WORLD_LIMIT = 29_999_984;

    public PlanetLayout {
        if (cells <= 0 || cells % 16 != 0) {
            throw new IllegalArgumentException("cells must be a positive multiple of 16, got " + cells);
        }
        if (originX % 16 != 0 || originZ % 16 != 0) {
            throw new IllegalArgumentException("origin must be chunk-aligned, got (" + originX + ", " + originZ + ")");
        }
        if ((long) originX < -WORLD_LIMIT || (long) originX + 4L * cells > WORLD_LIMIT
                || (long) originZ - cells < -WORLD_LIMIT || (long) originZ + 2L * cells > WORLD_LIMIT) {
            throw new IllegalArgumentException("net does not fit inside the world border");
        }
    }

    /** The cell under block column ({@code blockX}, {@code blockZ}), or null if it isn't on a face. */
    public FaceCell cellAt(int blockX, int blockZ) {
        long rx = (long) blockX - originX;
        long rz = (long) blockZ - originZ;
        long column = Math.floorDiv(rx, cells);
        long row = Math.floorDiv(rz, cells);
        PlanetFace face = faceAt(row, column);
        if (face == null) {
            return null;
        }
        return new FaceCell(face, (int) (rx - column * cells), (int) (rz - row * cells));
    }

    /** Block x of cell x = 0 on {@code face}. */
    public int faceOriginX(PlanetFace face) {
        return originX + column(face) * cells;
    }

    /** Block z of cell z = 0 on {@code face}. */
    public int faceOriginZ(PlanetFace face) {
        return originZ + row(face) * cells;
    }

    private static PlanetFace faceAt(long row, long column) {
        if (row == 0 && column >= 0 && column <= 3) {
            return PlanetFace.fromId((int) column);
        }
        if (column == 1 && row == -1) {
            return PlanetFace.NORTH;
        }
        if (column == 1 && row == 1) {
            return PlanetFace.SOUTH;
        }
        return null;
    }

    private static int column(PlanetFace face) {
        return switch (face) {
            case E0 -> 0;
            case E1, NORTH, SOUTH -> 1;
            case E2 -> 2;
            case E3 -> 3;
        };
    }

    private static int row(PlanetFace face) {
        return switch (face) {
            case NORTH -> -1;
            case SOUTH -> 1;
            default -> 0;
        };
    }
}
