package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * E4 option B, client side: the client's own copy of the ship, independent of vanilla's chunk storage (which
 * drops everything outside the view distance around the player). E3 will mesh from this. After every packet the
 * copy's hash is compared with the one the server computed, which is E4's correctness check.
 */
final class ClientShipStore {
    private BlockPos origin;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private int[] states;
    private final Map<Integer, CompoundTag> blockEntities = new TreeMap<>();
    private int snapshots;
    private int deltas;
    private int mismatches;
    private boolean lastOk = true;
    /** Bumped on every change to the blocks, so the renderer knows when to re-mesh. */
    private int version;
    // E3 pose (surface frame), from ShipPayloads.Pose.
    private Vec3 posePos = Vec3.ZERO;
    private final Quaternionf baseRotation = new Quaternionf();
    private float spinDegPerSec;
    private int copies = 1;
    private long poseNanos = System.nanoTime();
    private static final Vector3f SPIN_AXIS = new Vector3f(0.3F, 1.0F, 0.2F).normalize();

    void onSnapshot(ShipPayloads.Snapshot s) {
        origin = s.origin();
        sizeX = s.sizeX();
        sizeY = s.sizeY();
        sizeZ = s.sizeZ();
        states = s.states().clone();
        blockEntities.clear();
        blockEntities.putAll(s.blockEntities());
        snapshots++;
        version++;
        check("snapshot", s.states().length, s.hash());
    }

    void onDelta(ShipPayloads.Delta d) {
        if (states == null) {
            mismatches++;
            WorldEngineMod.LOGGER.warn("[spike] E4 client delta without a snapshot ({} cells)", d.cells().length);
            return;
        }
        for (int i = 0; i < d.cells().length; i++) {
            int cell = d.cells()[i];
            states[cell] = d.states()[i];
            CompoundTag tag = d.blockEntities().get(cell);
            if (tag != null) {
                blockEntities.put(cell, tag);
            } else {
                blockEntities.remove(cell);
            }
        }
        deltas++;
        version++;
        check("delta", d.cells().length, d.hash());
    }

    void onForget(ShipPayloads.Forget f) {
        version++;
        states = null;
        blockEntities.clear();
        WorldEngineMod.LOGGER.info("[spike] E4 client forget ship {}", f.shipId());
    }

    private void check(String kind, int cells, int expected) {
        int actual = ShipPayloads.hash(states, blockEntities);
        lastOk = actual == expected;
        if (!lastOk) {
            mismatches++;
        }
        WorldEngineMod.LOGGER.info("[spike] E4 client {}: {} cells, {} block entities, hash {}{}", kind, cells,
                blockEntities.size(), lastOk ? "ok" : "MISMATCH",
                lastOk ? "" : String.format(Locale.ROOT, " (expected %d, got %d)", expected, actual));
    }

    /** One HUD line, or null before the first snapshot. */
    String hudLine() {
        if (snapshots == 0) {
            return null;
        }
        if (states == null) {
            return String.format(Locale.ROOT, "ship copy: none (forgotten) | snapshots %d, deltas %d, mismatches %d",
                    snapshots, deltas, mismatches);
        }
        return String.format(Locale.ROOT, "ship copy: %dx%dx%d at %s, %d BE | snapshots %d, deltas %d | hash %s, mismatches %d",
                sizeX, sizeY, sizeZ, origin.toShortString(), blockEntities.size(), snapshots, deltas,
                lastOk ? "ok" : "MISMATCH", mismatches);
    }

    void onPose(ShipPayloads.Pose p) {
        // The server's rotation is authoritative; the spin phase restarts at each pose update (rare in E3).
        baseRotation.set(p.qx(), p.qy(), p.qz(), p.qw());
        posePos = new Vec3(p.x(), p.y(), p.z());
        spinDegPerSec = p.spinDegPerSec();
        copies = p.copies();
        poseNanos = System.nanoTime();
    }

    /** The ship's orientation now: the base rotation, then the spin since the last pose. */
    Quaternionf rotationNow() {
        double seconds = (System.nanoTime() - poseNanos) / 1e9;
        float angle = (float) Math.toRadians(spinDegPerSec * seconds);
        return new Quaternionf(baseRotation).rotateAxis(angle, SPIN_AXIS);
    }

    boolean has() {
        return states != null;
    }

    int version() {
        return version;
    }

    int sizeX() {
        return sizeX;
    }

    int sizeY() {
        return sizeY;
    }

    int sizeZ() {
        return sizeZ;
    }

    /** The global block-state id at local cell (x, y, z). */
    int stateAt(int x, int y, int z) {
        return states[x + sizeX * (z + sizeZ * y)];
    }

    Vec3 posePos() {
        return posePos;
    }

    int copies() {
        return copies;
    }

    void clear() {
        version++;
        states = null;
        blockEntities.clear();
    }
}
