package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import java.util.Objects;

/**
 * Everything world generation needs to know about one planet: its name (as in {@code we-orbit}), how its
 * terrain seed derives from the world seed, its kind, and where its faces sit in the level.
 */
public record PlanetSpec(String name, long seedSalt, PlanetKind kind, PlanetLayout layout) {
    public PlanetSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(layout, "layout");
    }

    /** Blocks per face edge. */
    public int cells() {
        return layout.cells();
    }

    /** This planet's terrain seed in a world with seed {@code worldSeed}. */
    public long seed(long worldSeed) {
        return mix64(worldSeed ^ seedSalt);
    }

    /** Block x of the centre of face E1, where searches for a spawn point start. */
    public int centreX() {
        return layout.faceOriginX(PlanetFace.E1) + cells() / 2;
    }

    /** Block z of the centre of face E1. */
    public int centreZ() {
        return layout.faceOriginZ(PlanetFace.E1) + cells() / 2;
    }

    /** SplitMix64's finalizer: a bijection, so distinct salts give distinct seeds. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
