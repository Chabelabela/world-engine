package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import java.util.ArrayList;
import java.util.List;

/**
 * Every planet's overlap bands (ROADMAP §4.3; M1e-2). Beyond each of a planet's 7 seam edges, on both sides, lies a
 * band {@link #WIDTH} blocks deep holding a copy of the neighbouring face, so terrain carries on across the seam.
 * Something that walks into a band is carried to its twin on the neighbouring face once it is {@link #TELEPORT_DEPTH}
 * deep, the middle of the band (see {@link SeamCrossings}).
 *
 * <p>Near the 8 cube corners, the bands of the two seams meeting there claim the same {@code WIDTH × WIDTH} square of
 * the level. That square belongs to neither: it is no-man's land, left empty.
 */
public final class SeamBands {
    /** Blocks from a seam edge to the far side of its band. A multiple of 16, so every chunk is in one band or none. */
    public static final int WIDTH = 128;
    /** Depth into a band at which things are carried to the twin face: the band's middle. */
    public static final int TELEPORT_DEPTH = WIDTH / 2;

    private final List<SeamBand> bands;

    /** A band and a block column in it. */
    public record Column(SeamBand band, int x, int z) {
    }

    public SeamBands(List<SeamBand> bands) {
        this.bands = List.copyOf(bands);
    }

    /** All bands of all planets, built from the fold: one per seam edge per side. */
    public static SeamBands build(SeamCrossings.Folder folder) {
        List<SeamBand> bands = new ArrayList<>();
        for (PlanetSpec planet : Planets.ALL) {
            for (PlanetFace face : PlanetFace.values()) {
                for (SeamBand.Side side : SeamBand.Side.values()) {
                    SeamBand band = band(planet, face, side, folder);
                    if (band != null) {
                        bands.add(band);
                    }
                }
            }
        }
        return new SeamBands(bands);
    }

    private static SeamBand band(PlanetSpec planet, PlanetFace face, SeamBand.Side side, SeamCrossings.Folder folder) {
        PlanetLayout layout = planet.layout();
        int n = planet.cells();
        int ox = layout.faceOriginX(face);
        int oz = layout.faceOriginZ(face);
        // A grid point half a block beyond the edge's middle, and the band's rectangle in level coordinates.
        double gx;
        double gz;
        int[] rect;
        switch (side) {
            case WEST -> {
                gx = -0.5;
                gz = n / 2.0;
                rect = new int[] {ox - WIDTH, oz, ox, oz + n};
            }
            case EAST -> {
                gx = n + 0.5;
                gz = n / 2.0;
                rect = new int[] {ox + n, oz, ox + n + WIDTH, oz + n};
            }
            case NORTH -> {
                gx = n / 2.0;
                gz = -0.5;
                rect = new int[] {ox, oz - WIDTH, ox + n, oz};
            }
            default -> {
                gx = n / 2.0;
                gz = n + 0.5;
                rect = new int[] {ox, oz + n, ox + n, oz + n + WIDTH};
            }
        }
        double px = ox + gx;
        double pz = oz + gz;
        if (Planets.at((int) Math.floor(px), (int) Math.floor(pz)) != null) {
            return null; // the net joins this edge: no seam, no band
        }
        SeamCrossing c = folder.fold(n, face, gx, gz, 0, 0, 0);
        if (c == null) {
            throw new IllegalStateException("no fold across " + planet.name() + " " + face + " " + side);
        }
        PlanetFace twin = PlanetFace.fromId(c.face());
        double qx = layout.faceOriginX(twin) + c.x();
        double qz = layout.faceOriginZ(twin) + c.z();
        double[] rp = SeamBand.rotate(c.turns(), px, pz);
        return new SeamBand(planet, face, side, twin, c.turns(), rect[0], rect[1], rect[2], rect[3],
                qx - rp[0], qz - rp[1]);
    }

    public List<SeamBand> all() {
        return bands;
    }

    /** The band holding block column (x, z), or null: on a face, in empty space, or where two bands meet. */
    public SeamBand at(int x, int z) {
        SeamBand found = null;
        for (SeamBand band : bands) {
            if (band.contains(x, z)) {
                if (found != null) {
                    return null; // no-man's land near a cube corner
                }
                found = band;
            }
        }
        return found;
    }

    /**
     * Where pre-loading helps something at level point (x, z): the twin of the point where it would cross, for each
     * band it is in or within {@link #WIDTH} blocks of. Empty elsewhere. Returns {x, z} points.
     */
    public List<double[]> preloadTargets(double x, double z) {
        List<double[]> targets = new ArrayList<>();
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        SeamBand in = at(bx, bz);
        if (in != null) {
            targets.add(in.toTwin(x, z));
            return targets;
        }
        PlanetHit hit = Planets.at(bx, bz);
        if (hit == null) {
            return targets;
        }
        for (SeamBand band : bands) {
            if (band.planet() != hit.planet() || band.face() != hit.cell().face()) {
                continue;
            }
            double depth = band.depth(x, z); // negative on the face
            if (depth < -WIDTH) {
                continue;
            }
            double step = TELEPORT_DEPTH - depth;
            double px = x + step * switch (band.side()) {
                case WEST -> -1;
                case EAST -> 1;
                default -> 0;
            };
            double pz = z + step * switch (band.side()) {
                case NORTH -> -1;
                case SOUTH -> 1;
                default -> 0;
            };
            if (at((int) Math.floor(px), (int) Math.floor(pz)) == band) {
                targets.add(band.toTwin(px, pz));
            }
        }
        return targets;
    }

    /**
     * The band columns that copy face column (x, z): none, one, or two near a corner of the face. For a column that
     * isn't on a face, the result means nothing.
     */
    public List<Column> copiesOf(int x, int z) {
        List<Column> copies = new ArrayList<>();
        for (SeamBand band : bands) {
            int[] b = band.bandBlock(x, z);
            if (band.contains(b[0], b[1]) && at(b[0], b[1]) == band) {
                copies.add(new Column(band, b[0], b[1]));
            }
        }
        return copies;
    }
}
