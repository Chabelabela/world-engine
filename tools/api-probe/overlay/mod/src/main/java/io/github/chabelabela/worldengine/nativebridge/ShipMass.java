package io.github.chabelabela.worldengine.nativebridge;

/**
 * A ship's {@code mass} (kg), its centre of mass ({@code cx}, {@code cy}, {@code cz}) in its own frame, and its
 * {@code inertia} tensor about that centre (kg m², 3x3, row by row). It holds an array, so compare it with
 * {@code Arrays.equals}.
 */
public record ShipMass(double mass, double cx, double cy, double cz, double[] inertia) {
    public ShipMass {
        if (inertia.length != 9) {
            throw new IllegalArgumentException("inertia must have 9 numbers");
        }
    }
}
