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
 * sees the sky. In an overlap band it is the sky over the band's twin, the spot on the neighbouring face it copies.
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

    /** The place at level position ({@code x}, {@code z}), or null in empty space or without the native core. */
    public static Place at(double x, double z) {
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
        if (hit == null) {
            SeamBand band = PlanetTerrain.bands().at(bx, bz);
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
        return new Place(planet, sky);
    }
}
