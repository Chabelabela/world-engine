package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PlanetLayoutTest {
    private static final int N = 16_384;
    // Same layout as we_frames' layout tests, so the two implementations are checked on the same cases.
    private static final PlanetLayout LAYOUT = new PlanetLayout(-40_000, 1_000_000, N);

    @Test
    void blocksMapToCellsLikeWeFrames() {
        int ox = LAYOUT.originX();
        int oz = LAYOUT.originZ();
        assertEquals(new FaceCell(PlanetFace.E0, 0, 0), LAYOUT.cellAt(ox, oz));
        assertEquals(new FaceCell(PlanetFace.NORTH, 3, N - 1), LAYOUT.cellAt(ox + N + 3, oz - 1));
        assertNull(LAYOUT.cellAt(ox - 1, oz));
    }

    @Test
    void faceIdsAreTheAbiNumbering() {
        // These ids cross the native ABI and match we_frames::Face.
        assertEquals(0, PlanetFace.E0.id());
        assertEquals(1, PlanetFace.E1.id());
        assertEquals(2, PlanetFace.E2.id());
        assertEquals(3, PlanetFace.E3.id());
        assertEquals(4, PlanetFace.NORTH.id());
        assertEquals(5, PlanetFace.SOUTH.id());
        for (int i = 0; i < 6; i++) {
            assertEquals(i, PlanetFace.fromId(i).id());
        }
    }

    @Test
    void outsideTheNetIsNoFace() {
        int ox = LAYOUT.originX();
        int oz = LAYOUT.originZ();
        int[][] offsets = {{-1, 5}, {4 * N + 1, 5}, {5, -5}, {2 * N + N / 2, N + 5}};
        for (int[] o : offsets) {
            assertNull(LAYOUT.cellAt(ox + o[0], oz + o[1]), () -> o[0] + ", " + o[1]);
        }
    }

    @Test
    void faceOriginsRoundTrip() {
        int[][] cells = {{0, 0}, {N - 1, N - 1}, {9_000, 77}};
        for (PlanetFace face : PlanetFace.values()) {
            for (int[] c : cells) {
                FaceCell expected = new FaceCell(face, c[0], c[1]);
                assertEquals(expected, LAYOUT.cellAt(LAYOUT.faceOriginX(face) + c[0], LAYOUT.faceOriginZ(face) + c[1]));
            }
        }
    }

    @Test
    void everyChunkLiesOnOneFaceOrNone() {
        // Chunks straddling every tile boundary of the net: all 256 columns agree on the face.
        for (int row = -2; row <= 2; row++) {
            for (int column = -1; column <= 4; column++) {
                int bx = LAYOUT.originX() + column * N;
                int bz = LAYOUT.originZ() + row * N;
                for (int cx = bx - 16; cx <= bx; cx += 16) {
                    for (int cz = bz - 16; cz <= bz; cz += 16) {
                        FaceCell first = LAYOUT.cellAt(cx, cz);
                        for (int dx = 0; dx < 16; dx++) {
                            for (int dz = 0; dz < 16; dz++) {
                                FaceCell cell = LAYOUT.cellAt(cx + dx, cz + dz);
                                assertEquals(first == null, cell == null);
                                if (first != null) {
                                    assertEquals(first.face(), cell.face());
                                    assertEquals(first.x() + dx, cell.x());
                                    assertEquals(first.z() + dz, cell.z());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void rejectsBadLayouts() {
        assertThrows(IllegalArgumentException.class, () -> new PlanetLayout(0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new PlanetLayout(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlanetLayout(8, 0, N));
        assertThrows(IllegalArgumentException.class, () -> new PlanetLayout(0, 8, N));
        assertThrows(IllegalArgumentException.class, () -> new PlanetLayout(29_999_984 - 4 * N + 16, 0, N));
        assertThrows(IllegalArgumentException.class, () -> new PlanetLayout(0, -29_999_984 + N - 16, N));
    }
}
