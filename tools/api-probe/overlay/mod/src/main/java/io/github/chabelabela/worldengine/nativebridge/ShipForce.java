package io.github.chabelabela.worldengine.nativebridge;

/**
 * A force of ({@code fx}, {@code fy}, {@code fz}) newtons at point ({@code px}, {@code py}, {@code pz}), both in the
 * ship's frame, so they turn with the ship: a thruster's push.
 */
public record ShipForce(double fx, double fy, double fz, double px, double py, double pz) {
}
