package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainStampTest {
    private static final TerrainStamp CURRENT = new TerrainStamp(2, 3);

    @Test
    void anUnstampedWorldNeverWarns() {
        assertNull(TerrainStamp.NONE.warningFor(CURRENT));
    }

    @Test
    void theSameTerrainVersionDoesNotWarn() {
        assertNull(new TerrainStamp(2, 3).warningFor(CURRENT));
        assertNull(new TerrainStamp(2, 1).warningFor(CURRENT)); // only the ABI changed
    }

    @Test
    void aDifferentTerrainVersionWarns() {
        String warning = new TerrainStamp(1, 3).warningFor(CURRENT);
        assertTrue(warning.contains("version 1") && warning.contains("version 2"), warning);
    }
}
