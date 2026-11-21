package io.github.chabelabela.worldengine.planet;

/** A block column that lies on a planet: which planet, and which cell of which face. */
public record PlanetHit(PlanetSpec planet, FaceCell cell) {
}
