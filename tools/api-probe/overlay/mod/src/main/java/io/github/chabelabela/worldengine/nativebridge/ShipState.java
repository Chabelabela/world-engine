package io.github.chabelabela.worldengine.nativebridge;

/** Ship {@code id}'s motion after a step, and whether it is asleep (at rest, costing almost nothing). */
public record ShipState(int id, ShipMotion motion, boolean sleeping) {
}
