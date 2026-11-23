package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.LocalSky;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;

/**
 * The sky over a position in the universe level (M1f): which planet it is on, and how that point of the planet
 * sees the sky. In an overlap band it is the sky over the band's twin, turned into the band's grid.
 *
 * <p>Thread-safe. Each thread remembers its last block column, so asking about one place repeatedly costs one
 * native call.
 */
public final class LocalSkies {
    /** {@code planet}, and how one of its points sees the sky. */
    public record Place(PlanetSpec planet, LocalSky sky) {
    }

    private record Last(int x, int z, Place place) {
    }

    private static final ThreadLocal<Last> LAST = new ThreadLocal<>();

    private LocalSkies() {
    }

    /**
     * The place at level position ({@code x}, {@code z}), or null in empty space, in no-man's land, for a position
     * that isn't finite, or without the native core.
     */
    public static Place at(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            return null;
        }
        NativeCore core = WorldEngineMod.core();
        if (core == null) {
            return null;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Last last = LAST.get();
        if (last != null && last.x() == bx && last.z() == bz) {
            return last.place();
        }
        Place place = lookUp(core, x, z, bx, bz);
        LAST.set(new Last(bx, bz, place));
        return place;
    }

    private static Place lookUp(NativeCore core, double x, double z, int bx, int bz) {
        PlanetHit hit = Planets.at(bx, bz);
        double px = x;
        double pz = z;
        SeamBand band = null;
        if (hit == null) {
            band = PlanetTerrain.bands().at(bx, bz);
            if (band == null) {
                return null;
            }
            double[] twin = band.toTwin(x, z);
            px = twin[0];
            pz = twin[1];
            hit = Planets.at((int) Math.floor(px), (int) Math.floor(pz));
            if (hit == null) {
                return null;
            }
        }
        PlanetSpec planet = hit.planet();
        PlanetFace face = hit.cell().face();
        LocalSky sky = core.localSky(planet.cells(), face.id(), px - planet.layout().faceOriginX(face),
                pz - planet.layout().faceOriginZ(face));
        if (band != null && band.turns() != 0) {
            sky = new LocalSky(sky.longitude(), sky.latitude(), turned(sky.tilt(), (4 - band.turns()) % 4));
        }
        return new Place(planet, sky);
    }

    /**
     * {@code tilt} with each column turned by {@code turns} quarter turns about the vertical, as
     * {@link SeamBand#rotate} turns grid directions.
     */
    private static double[] turned(double[] tilt, int turns) {
        double[] t = tilt.clone();
        for (int c = 0; c < 3; c++) {
            double[] r = SeamBand.rotate(turns, tilt[3 * c], tilt[3 * c + 2]);
            t[3 * c] = r[0];
            t[3 * c + 2] = r[1];
        }
        return t;
    }
}
