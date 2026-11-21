package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

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

    void onSnapshot(ShipPayloads.Snapshot s) {
        origin = s.origin();
        sizeX = s.sizeX();
        sizeY = s.sizeY();
        sizeZ = s.sizeZ();
        states = s.states().clone();
        blockEntities.clear();
        blockEntities.putAll(s.blockEntities());
        snapshots++;
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
        check("delta", d.cells().length, d.hash());
    }

    void onForget(ShipPayloads.Forget f) {
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

    void clear() {
        states = null;
        blockEntities.clear();
    }
}
