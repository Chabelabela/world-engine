package io.github.chabelabela.worldengine.physics;

import io.github.chabelabela.worldengine.nativebridge.ShipState;
import java.util.List;

/** Every ship's state after physics tick {@code tick} (0 before the first), by id. */
public record PhysicsSnapshot(long tick, List<ShipState> ships) {
    public static final PhysicsSnapshot EMPTY = new PhysicsSnapshot(0, List.of());

    public PhysicsSnapshot {
        ships = List.copyOf(ships);
    }

    /** Ship {@code id}'s state, or null if there was no such ship. */
    public ShipState ship(int id) {
        for (ShipState s : ships) {
            if (s.id() == id) {
                return s;
            }
        }
        return null;
    }
}
