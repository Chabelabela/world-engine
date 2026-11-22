package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import java.util.ArrayList;
import java.util.List;

/**
 * What happens to something that steps off a planet's net across a seam: one of the 7 cube edges that the
 * cross-shaped net doesn't join (ADR 0001; E2 in the M1b spike).
 *
 * <p>It lands on the neighbouring face at {@code we_frames::CubeSphere::fold}'s point (for a player, one tick of
 * travel short of it, see {@link Cross}), keeping its height, with its grid velocity and yaw turned by the fold's
 * quarter turns. Beyond a cube corner there is no neighbour, so it is put back where it last stood on the net.
 */
public final class SeamCrossings {
    /**
     * Something that moved further than this along either axis since its last position was teleported (a command, a
     * portal, a ship handoff), not walking, so it is left alone.
     */
    public static final int MAX_STEP = 16;

    /** Folds grid point (x, z) beyond an edge of {@code face}; null if it can't. In the mod: {@code NativeCore::seamCross}. */
    @FunctionalInterface
    public interface Folder {
        SeamCrossing fold(int cells, PlanetFace face, double x, double z, double vx, double vz, float yaw);
    }

    /** What to do with something that has left the net. */
    public sealed interface Outcome permits Cross, Bounce {
    }

    /**
     * Teleport to level position ({@code x}, {@code z}) on {@code face}, keeping the height, with horizontal velocity
     * ({@code vx}, {@code vz}) and {@code yaw}. ({@code foldX}, {@code foldZ}) is fold's point itself, always on the
     * face. ({@code x}, {@code z}) is fold's point minus one tick of (rotated) travel: a player's client advances one
     * more step right after a teleport, so that is where a player should land. Anything else has already had this
     * tick's movement applied, and lands at fold's point.
     */
    public record Cross(PlanetSpec planet, PlanetFace face, double x, double z, double vx, double vz, float yaw,
            double foldX, double foldZ) implements Outcome {
    }

    /** Beyond a corner: put it back at level position ({@code x}, {@code z}) and stop its horizontal movement. */
    public record Bounce(double x, double z) implements Outcome {
    }

    private SeamCrossings() {
    }

    /**
     * The outcome for something now at level position ({@code x}, {@code z}), moving ({@code vx}, {@code vz}) blocks
     * per tick and looking along {@code yaw}, whose last position on a planet's net was ({@code lastX}, {@code lastZ}).
     * Null if it is on a face (crossing an edge the net joins is just walking), it moved further than
     * {@link #MAX_STEP} since (it was teleported), or its last position wasn't on a planet.
     *
     * <p>The {@link Cross} has two landing points: fold's point, and one tick of (rotated) travel short of it. For a
     * player's client, which advances one more step right after a teleport, the latter keeps a crossing from gaining a
     * tick of travel (E2).
     */
    public static Outcome plan(double lastX, double lastZ, double x, double z, double vx, double vz, float yaw,
            Folder folder) {
        if (Planets.at(floor(x), floor(z)) != null) {
            return null;
        }
        if (Math.abs(x - lastX) > MAX_STEP || Math.abs(z - lastZ) > MAX_STEP) {
            return null;
        }
        PlanetHit last = Planets.at(floor(lastX), floor(lastZ));
        if (last == null) {
            return null;
        }
        PlanetSpec planet = last.planet();
        PlanetLayout layout = planet.layout();
        PlanetFace from = last.cell().face();
        SeamCrossing c = folder.fold(planet.cells(), from, x - layout.faceOriginX(from), z - layout.faceOriginZ(from),
                vx, vz, yaw);
        if (c == null) {
            return new Bounce(lastX, lastZ);
        }
        PlanetFace to = PlanetFace.fromId(c.face());
        double foldX = layout.faceOriginX(to) + c.x();
        double foldZ = layout.faceOriginZ(to) + c.z();
        return new Cross(planet, to, foldX - c.vx(), foldZ - c.vz(), c.vx(), c.vz(), c.yaw(), foldX, foldZ);
    }

    /** True if {@code hit}'s cell is within {@code margin} blocks of one of its face's four edges. */
    public static boolean nearEdge(PlanetHit hit, int margin) {
        FaceCell cell = hit.cell();
        int n = hit.planet().cells();
        return cell.x() < margin || cell.z() < margin || cell.x() >= n - margin || cell.z() >= n - margin;
    }

    /**
     * Level positions {x, z} of the far side of every seam within {@code margin} blocks of {@code hit}: where
     * something leaving straight across that edge would land. Edges the net joins are skipped. For pre-loading.
     */
    public static List<double[]> farSides(PlanetHit hit, int margin, Folder folder) {
        PlanetSpec planet = hit.planet();
        PlanetLayout layout = planet.layout();
        FaceCell cell = hit.cell();
        int n = planet.cells();
        double along = 0.5;
        double[][] beyond = {
            cell.x() < margin ? new double[] {-along, cell.z() + 0.5} : null,
            cell.x() >= n - margin ? new double[] {n + along, cell.z() + 0.5} : null,
            cell.z() < margin ? new double[] {cell.x() + 0.5, -along} : null,
            cell.z() >= n - margin ? new double[] {cell.x() + 0.5, n + along} : null,
        };
        List<double[]> sides = new ArrayList<>();
        for (double[] grid : beyond) {
            if (grid == null) {
                continue;
            }
            double lx = layout.faceOriginX(cell.face()) + grid[0];
            double lz = layout.faceOriginZ(cell.face()) + grid[1];
            if (Planets.at(floor(lx), floor(lz)) != null) {
                continue; // the net joins this edge
            }
            SeamCrossing c = folder.fold(n, cell.face(), grid[0], grid[1], 0, 0, 0);
            if (c != null) {
                PlanetFace to = PlanetFace.fromId(c.face());
                sides.add(new double[] {layout.faceOriginX(to) + c.x(), layout.faceOriginZ(to) + c.z()});
            }
        }
        return sides;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
