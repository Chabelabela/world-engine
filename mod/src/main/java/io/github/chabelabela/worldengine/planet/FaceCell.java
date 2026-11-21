package io.github.chabelabela.worldengine.planet;

import java.util.Objects;

/** Cell ({@code x}, {@code z}) of a face's block grid: x grows east, z grows south. */
public record FaceCell(PlanetFace face, int x, int z) {
    public FaceCell {
        Objects.requireNonNull(face, "face");
    }
}
