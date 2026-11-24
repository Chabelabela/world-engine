package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The physics world through the bridge (we-physics, M2a). */
class NativePhysicsTest {
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    /** A section whose 16x16x16 blocks are all full cubes. */
    private static TerrainSection solid() {
        TerrainSection section = new TerrainSection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.full(x, y, z);
                }
            }
        }
        return section;
    }

    private static ShipBlocks rod() {
        return new ShipBlocks().full(0, 0, 0, 1000).full(1, 0, 0, 1000).full(2, 0, 0, 1000);
    }

    @Test
    void aShipFallsOntoTheTerrainAndSleeps() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            world.setSection(0, -1, 0, solid()); // its top is at y = 0
            world.addShip(1, ShipMotion.at(4, 3, 4), new ShipBlocks().full(0, 0, 0, 2500).full(1, 0, 0, 2500));
            for (int i = 0; i < 20; i++) {
                world.step(60);
            }
            ShipState s = world.ships().get(0);
            assertEquals(1, s.id());
            assertEquals(0, s.motion().y(), 1e-3);
            assertTrue(s.sleeping());
        }
    }

    @Test
    void massPropertiesComeFromTheBlocks() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            world.addShip(7, ShipMotion.at(0, 50, 0), rod());
            ShipMass m = world.shipMass(7);
            assertEquals(3000, m.mass(), 1e-9);
            assertEquals(1.5, m.cx(), 1e-12);
            assertEquals(0.5, m.cy(), 1e-12);
            assertEquals(500, m.inertia()[0], 1e-6); // about x
            assertEquals(2500, m.inertia()[4], 1e-6); // about y
            assertEquals(0, m.inertia()[1], 1e-6);
        }
    }

    @Test
    void anOffCentreForceSpinsTheShip() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            world.addShip(1, ShipMotion.at(0, 0, 0), rod());
            world.setShipGravity(1, 0);
            world.setShipControls(1, List.of(new ShipForce(0, 1000, 0, 3, 0.5, 0.5)), 0, 0, 0);
            world.step(1);
            ShipMotion m = world.ships().get(0).motion();
            assertEquals(0.01, m.wz(), 1e-9); // 1,000 N x 1.5 m / 2,500 kg m² / 60 s
            assertEquals(1000.0 / 3000 / 60, m.vy(), 1e-9);
        }
    }

    @Test
    void slabsCollideAsTheirBoxes() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            // A terrain section that is only a slab floor, and a top slab dropped on it.
            world.setSection(0, 0, 0, new TerrainSection().box(0, 0, 0, 16, 0.5, 16));
            world.addShip(1, ShipMotion.at(6, 3, 6), new ShipBlocks().boxes(0, 0, 0, 1250, 0, 0.5, 0, 1, 1, 1));
            for (int i = 0; i < 10; i++) {
                world.step(60);
            }
            assertEquals(0.0, world.ships().get(0).motion().y(), 1e-3, "the top slab's underside lands at 0.5");
        }
    }

    @Test
    void shipsAreListedByIdAndCanBeMovedAndRemoved() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            for (int id : new int[] {9, 2, 5}) {
                world.addShip(id, ShipMotion.at(10 * id, 50, 0), new ShipBlocks().full(0, 0, 0, 1));
            }
            assertEquals(List.of(2, 5, 9), world.ships().stream().map(ShipState::id).toList());
            world.setShipMotion(5, new ShipMotion(1, 2, 3, 0, 1, 0, 0, 4, 0, 0, 0, 0, 0));
            ShipMotion m = world.ships().get(1).motion();
            assertEquals(1, m.x());
            assertEquals(1, m.qy());
            assertEquals(4, m.vx());
            world.removeShip(5);
            assertEquals(2, world.ships().size());
            world.clearSection(0, 0, 0);
        }
    }

    @Test
    void badCallsThrow() {
        NativeCore.PhysicsWorld world = core.newPhysicsWorld();
        world.addShip(1, ShipMotion.at(0, 0, 0), new ShipBlocks().full(0, 0, 0, 1));
        NativeCoreException e = assertThrows(NativeCoreException.class,
                () -> world.addShip(1, ShipMotion.at(0, 0, 0), new ShipBlocks().full(0, 0, 0, 1)));
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        e = assertThrows(NativeCoreException.class, () -> world.removeShip(2));
        assertTrue(e.getMessage().contains("no ship 2"), e.getMessage());
        assertThrows(NativeCoreException.class, () -> world.addShip(3, ShipMotion.at(0, 0, 0), new ShipBlocks()));
        assertThrows(NativeCoreException.class,
                () -> world.addShip(3, ShipMotion.at(0, 0, 0), new ShipBlocks().full(0, 0, 0, -1)));
        assertThrows(NativeCoreException.class, () -> world.step(601));
        assertThrows(IllegalArgumentException.class, () -> new ShipBlocks().boxes(0, 0, 0, 1, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerrainSection().full(16, 0, 0));
        world.close();
        world.close();
        assertThrows(IllegalStateException.class, () -> world.step(1));
    }
}
