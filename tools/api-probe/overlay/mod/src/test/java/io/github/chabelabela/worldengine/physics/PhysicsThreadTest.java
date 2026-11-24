package io.github.chabelabela.worldengine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.ShipBlocks;
import io.github.chabelabela.worldengine.nativebridge.ShipMotion;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PhysicsThreadTest {
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    private static void addFallingBlock(PhysicsThread physics) {
        physics.submit(world -> world.addShip(1, ShipMotion.at(0, 100, 0), new ShipBlocks().full(0, 0, 0, 1)));
    }

    @Test
    void inlineTicksPublishAtOnce() throws InterruptedException {
        try (PhysicsThread physics = PhysicsThread.inline(core)) {
            assertEquals(PhysicsSnapshot.EMPTY, physics.latest());
            addFallingBlock(physics);
            for (int i = 0; i < 20; i++) {
                physics.tick();
            }
            PhysicsSnapshot s = physics.latest();
            assertEquals(20, s.tick());
            // One second of free fall: about 4.9 m.
            assertEquals(100 - 4.9, s.ship(1).motion().y(), 0.1);
            assertNull(s.ship(2));
        }
    }

    @Test
    void threadedTicksRunInOrderOnThePhysicsThread() throws InterruptedException {
        AtomicReference<String> thread = new AtomicReference<>();
        try (PhysicsThread physics = PhysicsThread.start(core)) {
            addFallingBlock(physics);
            physics.submit(world -> thread.set(Thread.currentThread().getName()));
            for (int i = 0; i < 20; i++) {
                physics.tick();
            }
            assertTrue(physics.awaitIdle(Duration.ofSeconds(10)));
            assertEquals(PhysicsThread.THREAD_NAME, thread.get());
            assertEquals(20, physics.latest().tick());
            assertEquals(100 - 4.9, physics.latest().ship(1).motion().y(), 0.1);
            assertNull(physics.failure());
        }
    }

    @Test
    void aFailedCommandStopsTheWorld() throws InterruptedException {
        try (PhysicsThread physics = PhysicsThread.inline(core)) {
            addFallingBlock(physics);
            physics.tick();
            physics.submit(world -> world.removeShip(42));
            assertInstanceOf(NativeCoreException.class, physics.failure());
            physics.tick();
            assertEquals(1, physics.latest().tick(), "ticks after the failure are skipped");
        }
    }

    @Test
    void aClosedThreadRefusesWork() throws InterruptedException {
        PhysicsThread physics = PhysicsThread.start(core);
        physics.close();
        physics.close();
        assertThrows(IllegalStateException.class, physics::tick);
    }
}
