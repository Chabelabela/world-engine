package io.github.chabelabela.worldengine.physics;

import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a native physics world on a thread of its own, so the server thread never waits on it.
 *
 * <p>The server thread queues commands ({@link #submit}) and, once per server tick, {@link #tick()}: three physics
 * steps of 1/60 s, after which the ships' states are published. It reads the last published states with
 * {@link #latest()}, which never blocks: usually the previous tick's. Commands and ticks run in the order they were
 * queued.
 *
 * <p>If a command or a tick throws, the world stops: later commands and ticks are skipped, {@link #latest()} keeps
 * the last good states, and {@link #failure()} returns what was thrown, so the host can report it instead of
 * crashing the server. {@link #inline} runs everything on the calling thread instead, for deterministic tests.
 */
public final class PhysicsThread implements AutoCloseable {
    /** Physics steps per 20 Hz server tick. */
    public static final int STEPS_PER_TICK = 3;
    public static final String THREAD_NAME = "World Engine physics";

    private final NativeCore.PhysicsWorld world;
    private final ExecutorService executor;
    private volatile PhysicsSnapshot latest = PhysicsSnapshot.EMPTY;
    private volatile Throwable failure;
    private long ticks; // only touched by queued tasks
    private boolean closed; // only touched by the owning thread

    private PhysicsThread(NativeCore.PhysicsWorld world, ExecutorService executor) {
        this.world = world;
        this.executor = executor;
    }

    /** A new, empty physics world on its own daemon thread. */
    public static PhysicsThread start(NativeCore core) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        return new PhysicsThread(core.newPhysicsWorld(), executor);
    }

    /** A new, empty physics world whose commands and ticks run at once, on the calling thread. */
    public static PhysicsThread inline(NativeCore core) {
        return new PhysicsThread(core.newPhysicsWorld(), null);
    }

    /** Queues {@code command} to run on the physics thread with the world. */
    public void submit(Consumer<NativeCore.PhysicsWorld> command) {
        queue(() -> command.accept(world), true);
    }

    /** Queues one server tick: {@link #STEPS_PER_TICK} steps, then publishes the ships' states. */
    public void tick() {
        queue(() -> {
            world.step(STEPS_PER_TICK);
            ticks++;
            latest = new PhysicsSnapshot(ticks, world.ships());
        }, true);
    }

    /** The ships' states after the last tick that has finished. Never blocks. */
    public PhysicsSnapshot latest() {
        return latest;
    }

    /** What stopped the world, or null if it is running. */
    public Throwable failure() {
        return failure;
    }

    /**
     * Waits until everything queued so far has run, for at most {@code timeout}; true if it has. For tests and
     * shutdown only: the server thread must never wait on physics.
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        queue(done::countDown, false);
        return done.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void queue(Runnable task, boolean skipIfFailed) {
        if (closed) {
            throw new IllegalStateException("Physics thread closed");
        }
        Runnable guarded = () -> {
            if (skipIfFailed && failure != null) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                failure = t;
            }
        };
        if (executor == null) {
            guarded.run();
        } else {
            executor.execute(guarded);
        }
    }

    /** Runs what is queued, frees the world and stops the thread. Waits up to 5 seconds. */
    @Override
    public void close() throws InterruptedException {
        if (closed) {
            return;
        }
        queue(world::close, false);
        closed = true;
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The physics thread didn't stop within 5 seconds");
            }
        }
    }
}
