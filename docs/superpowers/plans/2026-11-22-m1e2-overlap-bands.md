# M1e-2 Overlap Bands and Edit Mirroring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Terrain carries on across every seam. Beyond each of a planet's 7 seam edges, on both sides, an **overlap band** 128 blocks deep holds an exact copy of the neighbouring face's strip along the same cube edge, turned to match:
- **Walking into a band is walking on real blocks.** At the band's middle, 64 blocks out, you are carried to the same spot on the neighbouring face. Every block around you is the same, so the crossing shows no jump in the terrain; only chunk loading remains.
- **Editing is mirrored both ways.** A block placed or broken in a band appears on the face it copies, and the other way round, with stairs, doors and other facing blocks turned by the seam's quarter turns.
- **No-man's land:** near the 4 inner corners of each net, the two bands of the seams meeting there would claim the same 128 × 128 square. That square stays empty, and anything that wanders in is put back.

This finishes M1's "terrain never visibly breaks at seams" ([M1 overview](2026-11-20-m1-overview.md)) and the ROADMAP §4.3 seam design, together with [M1e-1](2026-11-22-m1e1-seam-crossing.md).

**Architecture:**
- **Pure Java** (`planet`):
  - `SeamBand` is one band: its rectangle in the level, its depth, and its rigid map onto the twin face (a quarter-turn rotation plus a translation, `q = R·p + t`). Across one cube edge, `we_frames::CubeSphere::fold` *is* such a rigid motion. So one fold at the edge's midpoint fixes the map for the whole band, and a native test checks that it equals the fold at every depth.
  - `SeamBands` builds all 28 bands (7 seams × 2 sides × 2 planets) from the fold. It answers which band holds a column (none where two meet), which band columns copy a face column, and where to pre-load.
  - `SeamCrossings.plan` moves the teleport from the seam edge to the band's middle.
- **Minecraft:**
  - **Generation:** `PlanetTerrain` generates a band chunk from its twin's terrain, with one batched native call, as for any face chunk. `UniverseChunkGenerator` skips vanilla decoration (trees, ores, structures) in bands and in the face strips they copy, so both sides match block for block.
  - **Crossing:** `SeamCrossingHandler` teleports at the band's middle and bounces things out of no-man's land.
  - **Mirroring:** `SeamMirror` copies each edit across. It is fed by the mod's first mixin, at the head of `ServerLevel.sendBlockUpdated`, the hook the M1b spike used for ship edits. The copy is set without neighbour updates, shape updates or drops, so it can't start chains of updates or duplicate items.
- **No ABI change:** everything is built on M1e-1's `we_seam_cross` (ABI v4).

**Tech Stack:** Java 25 · Minecraft 26.3 + Fabric API 0.166.2 (lifecycle events, GameTest API v1) · Mixin (Fabric Loader 0.20.1) · JUnit 5

**What has been checked before execution (2026-11-22):** every file in this plan was built and run from a scratch worktree of `9b3eb42`.
- ✅ **Pure Java:** the 50 `worldengine.planet` JUnit tests pass on JDK 21.
- ✅ **Bands against the real core, on JDK 21:**
  - 28 bands, 14 per planet.
  - At every point checked, from 0.5 to 127.5 blocks deep along each band, the band's map equals `fold` with **error 0.0**.
  - Exactly 8 overlap squares of 128 × 128.
  - Every band column's twin lies on the twin face, within 128 blocks of the edge, and maps back.
- ✅ **Minecraft side, in CI** ([mc-api run #11](https://github.com/Chabelabela/world-engine/actions/runs/45077493523); a temporary workflow, removed afterwards): the whole mod compiled against Minecraft 26.3, and `./gradlew build` passed. That includes the native `NativeBandTest` and all 19 GameTests:
  - a band chunk equals its twin chunk block by block, for an unturned seam, a three-quarter-turn seam and the moon;
  - a block placed or broken in a band appears on its twin, and the other way round;
  - stairs facing west in North's west band face south on E0;
  - crossing at the band's middle;
  - the bounce out of no-man's land.
- ✅ **The plan reproduces that code:** replaying this plan's steps onto a clean `9b3eb42` gives the same files, byte for byte, as the tree CI built and tested.
- ⚠️ **Not checked:**
  - In a real client: how a band looks from the face, a crossing, building across a seam, and water or redstone near a seam.
  - Decoration being skipped in bands and strips. A real world decorates through `applyBiomeDecoration`, which the GameTests' stand-alone chunks never reach. It's a manual check.

**Post-execution note (2026-11-23):** executed with Haiku implementers and Sonnet reviewers. Every task's files matched the CI-verified reference byte for byte, and CI on the executed plan was green ([run #52](https://github.com/Chabelabela/world-engine/actions/runs/45165505308)). The code reviews then found the problems below, fixed in three follow-up commits (`9d5fb39`, `3ac74bd`, `0f6a766`). So **the repository, not these code blocks, is authoritative**:
- **Band biomes now match quart for quart.**
  - Minecraft stores one biome per 4×4×4 quart, sampled at the quart's centre.
  - A band chunk used to sample each quart at the twin of its centre column. A quarter turn puts that column one block off the centre of the twin's quart, so near a biome border the band could store the neighbouring biome.
  - A quarter turn maps a chunk's quarts onto whole quarts of its twin. So a band quart now takes the biome at its twin quart's centre (`ChunkTerrain.quartBiome`, `PlanetTerrain.quartBiome`), and stores exactly its twin's biomes.
  - `bandsCopyTheirTwin` now checks all 16 quarts of each chunk. Its old single check compared two different columns.
- **A ring of undecorated chunks around everything mirrored.**
  - A vanilla feature (a tree, an ore vein) may write one chunk beyond its own. A decorated chunk next to a copied strip could leave blocks in the strip that its band copy lacks.
  - Now a chunk is decorated only if neither it nor any of its 8 neighbours is mirrored (`PlanetTerrain.isUndecorated`). The bare zone on the face side is therefore 144 blocks deep.
  - A new GameTest, `decorationStaysClearOfBands`, checks the strip, the ring and the decorated chunks beyond.
- **Pre-loading from inside a band aims at the landing.**
  - In a band, `SeamBands.preloadTargets` returned the twin of where the player stood, up to 64 blocks short of where they land.
  - The face-side tickets could expire (5 s) before the in-band ones covered the landing.
  - It now aims at the point straight out at the band's middle, as it does on the face.
- **Smaller fixes:**
  - generating a band chunk fails loudly if the chunk doesn't map onto one 16×16 block of its twin face;
  - `SeamBands.build` rejects faces smaller than two bands;
  - the Javadoc of `SeamCrossings.plan` and of the handler now says exactly when something crosses or bounces;
  - the handler updates remembered positions in place instead of allocating every tick;
  - new unit tests check that:
    - the middle is exact (64.0 blocks crosses, 63.999 doesn't, on an east side and a west side);
    - a landing is on a face and doesn't cross back;
    - something teleported into no-man's land is left alone;
  - `NativeBandTest` also checks each band's yaw turn against the fold.

**Environment note:** without JDK 25 (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 7). Don't download JDKs. The pure planet tests run on any JDK ≥ 21 with the JUnit console launcher.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `mod/src/main/java/…/planet/SeamBand.java` | Create | One band: rectangle, depth, rigid map to its twin |
| `mod/src/main/java/…/planet/SeamBands.java` | Create | All bands from the fold; lookups |
| `mod/src/main/java/…/planet/SeamCrossings.java` | Modify (whole file) | Teleport at the band's middle; bounce from no-man's land |
| `mod/src/main/java/…/level/PlanetTerrain.java` | Modify | Bands, band chunks' terrain from their twin, which chunks stay undecorated |
| `mod/src/main/java/…/level/UniverseChunkGenerator.java` | Modify | Band chunks; no decoration in bands and strips |
| `mod/src/main/java/…/level/UniverseBiomeSource.java` | Modify | A band column's biome is its twin's |
| `mod/src/main/java/…/level/SeamCrossingHandler.java` | Modify (whole file) | Crossing at the band's middle |
| `mod/src/main/java/…/level/SeamMirror.java` | Create | Copies edits between a band and its strip |
| `mod/src/main/java/…/mixin/ServerLevelMixin.java` | Create | Feeds `SeamMirror` every block change |
| `mod/src/main/resources/worldengine.mixins.json` | Create | Mixin config |
| `mod/src/main/resources/fabric.mod.json` | Modify | Lists the mixin config |
| `mod/src/main/java/…/WorldEngineCommands.java` | Modify | `/worldengine where` in a band |
| `mod/src/test/java/…/planet/SeamBandTest.java`, `SeamBandsTest.java` | Create | Unit tests |
| `mod/src/test/java/…/planet/SeamCrossingsTest.java` | Modify (whole file) | Unit tests for the new crossing rules |
| `mod/src/test/java/…/nativebridge/NativeBandTest.java` | Create | Bands from the real fold |
| `mod/src/test/java/…/nativebridge/NativeSeamTest.java` | Modify (whole file) | Drops the edge-crossing net test, which `NativeBandTest` replaces |
| `mod/src/gametest/java/…/gametest/UniverseGameTests.java` | Modify | A band chunk is its twin chunk |
| `mod/src/gametest/java/…/gametest/SeamGameTests.java` | Modify (whole file) | Crossings at the band's middle; no-man's land |
| `mod/src/gametest/java/…/gametest/SeamMirrorGameTests.java` | Create | Mirroring, including turned facing |
| `mod/src/gametest/resources/fabric.mod.json` | Modify | Lists the new GameTest class |

(`…` is `io/github/chabelabela/worldengine`.)

---

### Task 1: One band (`SeamBand`)

A band is the strip of level space `SeamBands.WIDTH` (128) blocks deep beyond a seam edge of a face. Its twin is the strip of the neighbouring face along the same cube edge. Across one edge, the unfolded cube maps one onto the other by a rigid motion, so the record carries:
- the turns, 0–3 clockwise like `QuarterTurns`;
- a translation, so that twin q = R(turns)·p + (tx, tz).

Block columns map through their centres. Yaw turns with the same f32 arithmetic as `QuarterTurns::rotate_yaw`.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/SeamBand.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/SeamBandTest.java`

`SeamBandTest` refers to `SeamBands.WIDTH`, which Task 2 adds. So in this task, write the test, see it fail to compile, and write `SeamBand`. It compiles once Task 2's `SeamBands` exists, and both tasks' tests run at the end of Task 2.

- [ ] **Step 1: Write the test.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeamBandTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();

    /** E3's east band: the equator wraps onto E0's west edge, a pure translation 4 faces west. */
    private static final SeamBand WRAP = new SeamBand(Planets.HOME, PlanetFace.E3, SeamBand.Side.EAST, PlanetFace.E0, 0,
            HOME.faceOriginX(PlanetFace.E3) + N, HOME.faceOriginZ(PlanetFace.E3),
            HOME.faceOriginX(PlanetFace.E3) + N + SeamBands.WIDTH, HOME.faceOriginZ(PlanetFace.E3) + N, -4.0 * N, 0.0);

    /** A made-up band turned one quarter clockwise about the origin, then moved by (1000, 2000). */
    private static final SeamBand TURNED = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 1,
            -100, 0, 0, 50, 1000.0, 2000.0);

    @Test
    void theWrapIsATranslation() {
        double east = HOME.faceOriginX(PlanetFace.E3) + N;
        double west = HOME.faceOriginX(PlanetFace.E0);
        assertArrayEquals(new double[] {west + 64.25, 100.5}, WRAP.toTwin(east + 64.25, 100.5));
        assertEquals(64.25, WRAP.depth(east + 64.25, 100.5));
        assertArrayEquals(new double[] {0.3, -0.1}, WRAP.rotateDirection(0.3, -0.1));
        assertEquals(-90.0F, WRAP.rotateYaw(-90.0F));
    }

    @Test
    void aTurnRotatesClockwiseLikeQuarterTurns() {
        // East turns into south, south into west: (dx, dz) -> (-dz, dx).
        assertArrayEquals(new double[] {0.0, 1.0}, TURNED.rotateDirection(1.0, 0.0), 0.0); // delta 0: -0.0 == 0.0
        assertArrayEquals(new double[] {-1.0, 0.0}, TURNED.rotateDirection(0.0, 1.0), 0.0);
        assertArrayEquals(new double[] {1000.0 - 2.0, 2000.0 + 1.0}, TURNED.toTwin(1.0, 2.0));
        // Yaw 0 looks south; a quarter turn clockwise looks west (90).
        assertEquals(90.0F, TURNED.rotateYaw(0.0F));
        assertEquals(-180.0F, TURNED.rotateYaw(90.0F));
    }

    @Test
    void fromTwinUndoesToTwin() {
        for (SeamBand band : new SeamBand[] {WRAP, TURNED}) {
            double[] twin = band.toTwin(-37.25, 12.75);
            double[] back = band.fromTwin(twin[0], twin[1]);
            assertEquals(-37.25, back[0], 1e-9);
            assertEquals(12.75, back[1], 1e-9);
        }
    }

    @Test
    void blocksMapToBlocksThroughTheirCentres() {
        int[] twin = TURNED.twinBlock(-3, 7);
        // Centre (-2.5, 7.5) turns to (-7.5, -2.5), then moves to (992.5, 1997.5): block (992, 1997).
        assertArrayEquals(new int[] {992, 1997}, twin);
        assertArrayEquals(new int[] {-3, 7}, TURNED.bandBlock(twin[0], twin[1]));
    }

    @Test
    void containsIsTheHalfOpenRectangle() {
        assertTrue(TURNED.contains(-100, 0));
        assertTrue(TURNED.contains(-1, 49));
        assertFalse(TURNED.contains(0, 10));
        assertFalse(TURNED.contains(-50, 50));
    }

    @Test
    void depthGrowsAwayFromTheEdgeOnEverySide() {
        int ox = HOME.faceOriginX(PlanetFace.E0);
        int oz = HOME.faceOriginZ(PlanetFace.E0);
        SeamBand west = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 0, 0, 0, 1, 1, 0, 0);
        SeamBand north = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.NORTH, PlanetFace.NORTH, 1, 0, 0, 1, 1, 0, 0);
        SeamBand south = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.SOUTH, PlanetFace.SOUTH, 3, 0, 0, 1, 1, 0, 0);
        assertEquals(5.0, west.depth(ox - 5.0, 0));
        assertEquals(5.0, north.depth(0, oz - 5.0));
        assertEquals(5.0, south.depth(0, oz + N + 5.0));
        assertEquals(-5.0, west.depth(ox + 5.0, 0)); // on the face
    }

    @Test
    void rejectsBadTurns() {
        assertThrows(IllegalArgumentException.class,
                () -> new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 4, 0, 0, 1, 1, 0, 0));
    }
}
```

- [ ] **Step 2: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

import java.util.Objects;

/**
 * One overlap band: the strip of level space {@link SeamBands#WIDTH} blocks deep beyond a seam edge of {@code face},
 * holding a copy of the neighbouring face {@code twinFace} along the same cube edge, turned by {@code turns}
 * clockwise quarter turns. The band's blocks are generated from, and kept in step with, that strip of the twin face.
 *
 * <p>Across a seam the cube unfolds rigidly, so a level point p in the band and its twin q are related by a rotation
 * and a translation: q = R(turns)·p + (tx, tz). Block columns map to block columns, through their centres.
 *
 * @param minX first level x of the band (inclusive); {@code maxX} is exclusive, and likewise for z
 */
public record SeamBand(PlanetSpec planet, PlanetFace face, Side side, PlanetFace twinFace, int turns,
        int minX, int minZ, int maxX, int maxZ, double tx, double tz) {
    /** A face's edge, on its block grid: x grows east, z grows south. */
    public enum Side {
        WEST, EAST, NORTH, SOUTH
    }

    public SeamBand {
        Objects.requireNonNull(planet, "planet");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(twinFace, "twinFace");
        if (turns < 0 || turns > 3) {
            throw new IllegalArgumentException("turns must be 0-3, got " + turns);
        }
    }

    /** True if block column (x, z) lies in this band's rectangle. */
    public boolean contains(int x, int z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    /** How far level point (x, z) lies beyond the face edge, in blocks: 0 at the edge, {@link SeamBands#WIDTH} at the far side. */
    public double depth(double x, double z) {
        PlanetLayout layout = planet.layout();
        return switch (side) {
            case WEST -> layout.faceOriginX(face) - x;
            case EAST -> x - (layout.faceOriginX(face) + planet.cells());
            case NORTH -> layout.faceOriginZ(face) - z;
            case SOUTH -> z - (layout.faceOriginZ(face) + planet.cells());
        };
    }

    /** The twin of level point (x, z): where it lies on {@link #twinFace}. Returns {x, z}. */
    public double[] toTwin(double x, double z) {
        double[] r = rotate(turns, x, z);
        return new double[] {r[0] + tx, r[1] + tz};
    }

    /** The band point whose twin is level point (x, z): the inverse of {@link #toTwin}. Returns {x, z}. */
    public double[] fromTwin(double x, double z) {
        return rotate((4 - turns) % 4, x - tx, z - tz);
    }

    /** The twin of block column (x, z), through its centre. Returns {x, z}. */
    public int[] twinBlock(int x, int z) {
        double[] t = toTwin(x + 0.5, z + 0.5);
        return new int[] {(int) Math.floor(t[0]), (int) Math.floor(t[1])};
    }

    /** The band column whose twin is block column (x, z), through its centre. Returns {x, z}. */
    public int[] bandBlock(int x, int z) {
        double[] b = fromTwin(x + 0.5, z + 0.5);
        return new int[] {(int) Math.floor(b[0]), (int) Math.floor(b[1])};
    }

    /** A grid direction (dx east, dz south) turned from the band's grid onto the twin face's. Returns {dx, dz}. */
    public double[] rotateDirection(double dx, double dz) {
        return rotate(turns, dx, dz);
    }

    /**
     * A Minecraft yaw (0 = south, 90 = west) turned like {@link #rotateDirection}, wrapped into [-180, 180). The same
     * f32 arithmetic as {@code we_frames::QuarterTurns::rotate_yaw}.
     */
    public float rotateYaw(float yaw) {
        float r = (yaw + 90.0F * turns + 180.0F) % 360.0F;
        if (r < 0) {
            r += 360.0F;
        }
        return r - 180.0F;
    }

    /** Quarter turns clockwise seen from above: east turns into south. Like {@code we_frames::QuarterTurns::apply}. */
    static double[] rotate(int turns, double x, double z) {
        return switch (turns) {
            case 0 -> new double[] {x, z};
            case 1 -> new double[] {-z, x};
            case 2 -> new double[] {-x, -z};
            default -> new double[] {z, -x};
        };
    }
}
```

- [ ] **Step 3: Commit** (the test compiles after Task 2).

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet/SeamBand.java mod/src/test/java/io/github/chabelabela/worldengine/planet/SeamBandTest.java
git commit -m "M1e-2: SeamBand, one overlap band and its map to its twin"
```

---

### Task 2: All the bands (`SeamBands`)

`SeamBands.build(folder)` makes a band for every face edge whose far side is on no face: 7 seams, both sides, both planets, 28 bands. For each, one fold of a point half a block beyond the edge's midpoint gives the twin face, the turns and the translation.

Lookups:
- `at(x, z)` is the band holding a column. It is null on a face, in empty space, and in the 8 squares where two bands overlap: no-man's land.
- `copiesOf(x, z)` gives the band columns that copy a face column. That's one, or two near a face corner, or none.
- `preloadTargets(x, z)` gives where pre-loading helps someone in, or within 128 blocks of, a band.

`TELEPORT_DEPTH` (64) is the band's middle.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/SeamBands.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/SeamBandsTest.java`, `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeBandTest.java`

- [ ] **Step 1: Write the failing tests.** The unit test uses hand-made bands. The native test builds the real bands, so it needs the native library: Gradle only.

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lookups over hand-made bands; {@code NativeBandTest} checks the bands built from the real fold. */
class SeamBandsTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int W = SeamBands.WIDTH;
    private static final int E0X = HOME.faceOriginX(PlanetFace.E0);
    private static final int E0Z = HOME.faceOriginZ(PlanetFace.E0);

    /** E0's west band, a stand-in mapping onto E3's east edge (as the real equator wrap does). */
    private static final SeamBand WEST = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3, 0,
            E0X - W, E0Z, E0X, E0Z + N, 4.0 * N, 0.0);
    /** A band overlapping WEST's north end, to make a no-man's-land square. */
    private static final SeamBand CROSSING = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.NORTH,
            PlanetFace.NORTH, 0, E0X - W, E0Z, E0X, E0Z + W, 0.0, -5_000.0);
    private static final SeamBands BANDS = new SeamBands(List.of(WEST, CROSSING));

    @Test
    void aColumnInOneBandBelongsToIt() {
        assertSame(WEST, BANDS.at(E0X - 10, E0Z + 500));
    }

    @Test
    void whereTwoBandsMeetIsNoMansLand() {
        assertNull(BANDS.at(E0X - 10, E0Z + 10));
    }

    @Test
    void faceColumnsAndEmptySpaceAreInNoBand() {
        assertNull(BANDS.at(E0X + 10, E0Z + 500));
        assertNull(BANDS.at(E0X - W - 1, E0Z + 500));
    }

    @Test
    void copiesOfFindsTheBandColumnsShowingAFaceColumn() {
        int[] twin = WEST.twinBlock(E0X - 10, E0Z + 500);
        List<SeamBands.Column> copies = BANDS.copiesOf(twin[0], twin[1]);
        assertEquals(1, copies.size());
        assertSame(WEST, copies.get(0).band());
        assertEquals(E0X - 10, copies.get(0).x());
        assertEquals(E0Z + 500, copies.get(0).z());
        // The twin of a no-man's-land column has no copy.
        int[] lost = WEST.twinBlock(E0X - 10, E0Z + 10);
        assertTrue(BANDS.copiesOf(lost[0], lost[1]).stream().noneMatch(c -> c.band() == WEST));
    }

    @Test
    void preloadTargetsAreWhereACrossingWouldLand() {
        // In the band: its own twin.
        List<double[]> inBand = BANDS.preloadTargets(E0X - 10.5, E0Z + 500.5);
        assertEquals(1, inBand.size());
        assertArrayEquals(WEST.toTwin(E0X - 10.5, E0Z + 500.5), inBand.get(0));
        // On the face, 20 blocks in: the twin of the band's middle straight ahead.
        List<double[]> onFace = BANDS.preloadTargets(E0X + 20.5, E0Z + 500.5);
        assertEquals(1, onFace.size());
        assertArrayEquals(WEST.toTwin(E0X - SeamBands.TELEPORT_DEPTH, E0Z + 500.5), onFace.get(0));
        // Far from the edge: nothing.
        assertTrue(BANDS.preloadTargets(E0X + W + 20.5, E0Z + 500.5).isEmpty());
    }
}
```

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The overlap bands built from the real fold: their shape, and that their rigid map is the fold everywhere in them. */
class NativeBandTest {
    private static NativeCore core;
    private static SeamBands bands;

    @BeforeAll
    static void build() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
        bands = SeamBands.build((cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw));
    }

    @Test
    void everySeamHasABandOnBothSides() {
        // 7 seam edges per planet, a band on each side of each.
        assertEquals(28, bands.all().size());
        assertEquals(14, bands.all().stream().filter(b -> b.planet() == Planets.HOME).count());
    }

    /** Fold across one edge is a rigid motion: the band's map, made from one fold, is the fold at every depth. */
    @Test
    void theBandsMapIsTheFoldEverywhereInIt() {
        for (SeamBand band : bands.all()) {
            PlanetLayout layout = band.planet().layout();
            int n = band.planet().cells();
            for (double depth : new double[] {0.5, 17.25, 63.75, 64.25, 127.5}) {
                for (double along : new double[] {0.5, 200.25, n - 0.5}) {
                    double[] grid = switch (band.side()) {
                        case WEST -> new double[] {-depth, along};
                        case EAST -> new double[] {n + depth, along};
                        case NORTH -> new double[] {along, -depth};
                        case SOUTH -> new double[] {along, n + depth};
                    };
                    double x = layout.faceOriginX(band.face()) + grid[0];
                    double z = layout.faceOriginZ(band.face()) + grid[1];
                    SeamCrossing fold = core.seamCross(n, band.face().id(), grid[0], grid[1], 1.0, 0.0, 0.0F);
                    String label = band.planet().name() + " " + band.face() + " " + band.side() + " at " + depth;
                    assertEquals(band.twinFace().id(), fold.face(), label);
                    assertEquals(band.turns(), fold.turns(), label);
                    PlanetFace twin = PlanetFace.fromId(fold.face());
                    assertArrayEquals(new double[] {layout.faceOriginX(twin) + fold.x(), layout.faceOriginZ(twin) + fold.z()},
                            band.toTwin(x, z), 1e-9, label);
                    assertArrayEquals(new double[] {fold.vx(), fold.vz()}, band.rotateDirection(1.0, 0.0), 1e-12, label);
                    assertEquals(depth, band.depth(x, z), 1e-9, label);
                }
            }
        }
    }

    @Test
    void bandColumnsCopyFaceColumnsNearTheSameEdge() {
        for (SeamBand band : bands.all()) {
            for (int x : new int[] {band.minX(), band.minX() + 7, band.maxX() - 1}) {
                for (int z : new int[] {band.minZ(), band.minZ() + 9, band.maxZ() - 1}) {
                    assertNull(Planets.at(x, z), "a band is off the net");
                    int[] twin = band.twinBlock(x, z);
                    PlanetHit hit = Planets.at(twin[0], twin[1]);
                    assertNotNull(hit);
                    assertEquals(band.planet(), hit.planet());
                    assertEquals(band.twinFace(), hit.cell().face());
                    assertTrue(SeamCrossings.nearEdge(hit, SeamBands.WIDTH));
                    assertArrayEquals(new int[] {x, z}, band.bandBlock(twin[0], twin[1]));
                    if (bands.at(x, z) == band) {
                        List<SeamBands.Column> copies = bands.copiesOf(twin[0], twin[1]);
                        assertTrue(copies.stream().anyMatch(c -> c.band() == band && c.x() == x && c.z() == z));
                    }
                }
            }
        }
    }

    /** Near the 4 inner corners of each net, two bands claim the same square: 8 squares of 128 × 128, left empty. */
    @Test
    void bandsMeetOnlyInNoMansLandSquares() {
        List<SeamBand> all = bands.all();
        int squares = 0;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                SeamBand a = all.get(i);
                SeamBand b = all.get(j);
                int w = Math.min(a.maxX(), b.maxX()) - Math.max(a.minX(), b.minX());
                int h = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.minZ(), b.minZ());
                if (w > 0 && h > 0) {
                    squares++;
                    assertEquals(SeamBands.WIDTH, w);
                    assertEquals(SeamBands.WIDTH, h);
                    assertNull(bands.at(Math.max(a.minX(), b.minX()), Math.max(a.minZ(), b.minZ())));
                }
            }
        }
        assertEquals(8, squares);
    }
}
```

- [ ] **Step 2: Run the unit tests to see them fail.**

Run: `cd mod && ./gradlew test --tests '*SeamBandTest' --tests '*SeamBandsTest'`
Expected: compilation fails (`SeamBands` doesn't exist).

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import java.util.ArrayList;
import java.util.List;

/**
 * Every planet's overlap bands (ROADMAP §4.3; M1e-2). Beyond each of a planet's 7 seam edges, on both sides, lies a
 * band {@link #WIDTH} blocks deep holding a copy of the neighbouring face, so terrain carries on across the seam.
 * Something that walks into a band is carried to its twin on the neighbouring face once it is {@link #TELEPORT_DEPTH}
 * deep, the middle of the band (see {@link SeamCrossings}).
 *
 * <p>Near the 8 cube corners, the bands of the two seams meeting there claim the same {@code WIDTH × WIDTH} square of
 * the level. That square belongs to neither: it is no-man's land, left empty.
 */
public final class SeamBands {
    /** Blocks from a seam edge to the far side of its band. A multiple of 16, so every chunk is in one band or none. */
    public static final int WIDTH = 128;
    /** Depth into a band at which things are carried to the twin face: the band's middle. */
    public static final int TELEPORT_DEPTH = WIDTH / 2;

    private final List<SeamBand> bands;

    /** A band and a block column in it. */
    public record Column(SeamBand band, int x, int z) {
    }

    public SeamBands(List<SeamBand> bands) {
        this.bands = List.copyOf(bands);
    }

    /** All bands of all planets, built from the fold: one per seam edge per side. */
    public static SeamBands build(SeamCrossings.Folder folder) {
        List<SeamBand> bands = new ArrayList<>();
        for (PlanetSpec planet : Planets.ALL) {
            for (PlanetFace face : PlanetFace.values()) {
                for (SeamBand.Side side : SeamBand.Side.values()) {
                    SeamBand band = band(planet, face, side, folder);
                    if (band != null) {
                        bands.add(band);
                    }
                }
            }
        }
        return new SeamBands(bands);
    }

    private static SeamBand band(PlanetSpec planet, PlanetFace face, SeamBand.Side side, SeamCrossings.Folder folder) {
        PlanetLayout layout = planet.layout();
        int n = planet.cells();
        int ox = layout.faceOriginX(face);
        int oz = layout.faceOriginZ(face);
        // A grid point half a block beyond the edge's middle, and the band's rectangle in level coordinates.
        double gx;
        double gz;
        int[] rect;
        switch (side) {
            case WEST -> {
                gx = -0.5;
                gz = n / 2.0;
                rect = new int[] {ox - WIDTH, oz, ox, oz + n};
            }
            case EAST -> {
                gx = n + 0.5;
                gz = n / 2.0;
                rect = new int[] {ox + n, oz, ox + n + WIDTH, oz + n};
            }
            case NORTH -> {
                gx = n / 2.0;
                gz = -0.5;
                rect = new int[] {ox, oz - WIDTH, ox + n, oz};
            }
            default -> {
                gx = n / 2.0;
                gz = n + 0.5;
                rect = new int[] {ox, oz + n, ox + n, oz + n + WIDTH};
            }
        }
        double px = ox + gx;
        double pz = oz + gz;
        if (Planets.at((int) Math.floor(px), (int) Math.floor(pz)) != null) {
            return null; // the net joins this edge: no seam, no band
        }
        SeamCrossing c = folder.fold(n, face, gx, gz, 0, 0, 0);
        if (c == null) {
            throw new IllegalStateException("no fold across " + planet.name() + " " + face + " " + side);
        }
        PlanetFace twin = PlanetFace.fromId(c.face());
        double qx = layout.faceOriginX(twin) + c.x();
        double qz = layout.faceOriginZ(twin) + c.z();
        double[] rp = SeamBand.rotate(c.turns(), px, pz);
        return new SeamBand(planet, face, side, twin, c.turns(), rect[0], rect[1], rect[2], rect[3],
                qx - rp[0], qz - rp[1]);
    }

    public List<SeamBand> all() {
        return bands;
    }

    /** The band holding block column (x, z), or null: on a face, in empty space, or where two bands meet. */
    public SeamBand at(int x, int z) {
        SeamBand found = null;
        for (SeamBand band : bands) {
            if (band.contains(x, z)) {
                if (found != null) {
                    return null; // no-man's land near a cube corner
                }
                found = band;
            }
        }
        return found;
    }

    /**
     * Where pre-loading helps something at level point (x, z): the twin of the point where it would cross, for each
     * band it is in or within {@link #WIDTH} blocks of. Empty elsewhere. Returns {x, z} points.
     */
    public List<double[]> preloadTargets(double x, double z) {
        List<double[]> targets = new ArrayList<>();
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        SeamBand in = at(bx, bz);
        if (in != null) {
            targets.add(in.toTwin(x, z));
            return targets;
        }
        PlanetHit hit = Planets.at(bx, bz);
        if (hit == null) {
            return targets;
        }
        for (SeamBand band : bands) {
            if (band.planet() != hit.planet() || band.face() != hit.cell().face()) {
                continue;
            }
            double depth = band.depth(x, z); // negative on the face
            if (depth < -WIDTH) {
                continue;
            }
            double step = TELEPORT_DEPTH - depth;
            double px = x + step * switch (band.side()) {
                case WEST -> -1;
                case EAST -> 1;
                default -> 0;
            };
            double pz = z + step * switch (band.side()) {
                case NORTH -> -1;
                case SOUTH -> 1;
                default -> 0;
            };
            if (at((int) Math.floor(px), (int) Math.floor(pz)) == band) {
                targets.add(band.toTwin(px, pz));
            }
        }
        return targets;
    }

    /**
     * The band columns that copy face column (x, z): none, one, or two near a corner of the face. For a column that
     * isn't on a face, the result means nothing.
     */
    public List<Column> copiesOf(int x, int z) {
        List<Column> copies = new ArrayList<>();
        for (SeamBand band : bands) {
            int[] b = band.bandBlock(x, z);
            if (band.contains(b[0], b[1]) && at(b[0], b[1]) == band) {
                copies.add(new Column(band, b[0], b[1]));
            }
        }
        return copies;
    }
}
```

- [ ] **Step 4: Run the tests.**

Run: `cd mod && ./gradlew test --tests '*SeamBandTest' --tests '*SeamBandsTest' --tests '*NativeBandTest'`
Expected: all pass.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/test/java/io/github/chabelabela/worldengine
git commit -m "M1e-2: SeamBands, all overlap bands from the fold"
```

---

### Task 3: Band chunks are generated from their twin

A band chunk lies entirely in one band, because bands are 128 deep and start at chunk-aligned edges. Its twin is a chunk-aligned 16 × 16 block of the twin face, turned. So the twin's terrain comes from one batched native call, and each band column reads its twin column's height and biome. Heights and biomes match exactly, so the blocks match too.

Vanilla decoration (trees, grass, ores, structures) would differ on the two sides, because it is randomised per chunk position. So `applyBiomeDecoration` is skipped in bands and in the face strips they copy. M1e-2's bands are bare, as a v1 limit (see Known limits).

**Files:**
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/level/PlanetTerrain.java`, `UniverseChunkGenerator.java`, `UniverseBiomeSource.java`
- Modify: `mod/src/gametest/java/io/github/chabelabela/worldengine/gametest/UniverseGameTests.java`

- [ ] **Step 1: Write the failing GameTest.** In `UniverseGameTests.java`, add these imports among the existing ones:

```java
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import net.minecraft.world.level.block.state.BlockState;
```

and this test before `spaceIsEmpty`:

```java
    /** A band chunk is its twin chunk, turned: every block of every column, from bedrock to sky. */
    @GameTest
    public void bandsCopyTheirTwin(GameTestHelper helper) {
        Universe u = universe(helper);
        SeamBands bands = PlanetTerrain.bands();
        int n = Planets.HOME.cells();
        PlanetLayout home = Planets.HOME.layout();
        PlanetLayout moon = Planets.MOON.layout();
        int[][] bandColumns = {
            {home.faceOriginX(PlanetFace.E3) + n + 40, home.faceOriginZ(PlanetFace.E3) + 5_000}, // the equator wrap
            {home.faceOriginX(PlanetFace.NORTH) - 70, home.faceOriginZ(PlanetFace.NORTH) + 9_000}, // three quarter turns
            {moon.faceOriginX(PlanetFace.E2) + 1_000, moon.faceOriginZ(PlanetFace.E2) - 20}, // the moon
        };
        for (int[] c : bandColumns) {
            SeamBand band = bands.at(c[0], c[1]);
            check(helper, band != null, "(" + c[0] + ", " + c[1] + ") is in a band");
            ProtoChunk chunk = u.generate(new ChunkPos(c[0] >> 4, c[1] >> 4));
            int[] corner = band.twinBlock(c[0], c[1]);
            ProtoChunk twinChunk = u.generate(new ChunkPos(corner[0] >> 4, corner[1] >> 4));
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int[] twin = band.twinBlock(minX + dx, minZ + dz);
                    check(helper, twin[0] >> 4 == twinChunk.getPos().x() && twin[1] >> 4 == twinChunk.getPos().z(),
                            "a band chunk's twin is one chunk");
                    for (int y = LevelShape.MIN_Y; y <= LevelShape.maxY(); y++) {
                        BlockState here = chunk.getBlockState(new BlockPos(minX + dx, y, minZ + dz));
                        BlockState there = twinChunk.getBlockState(new BlockPos(twin[0], y, twin[1]));
                        if (here != there) {
                            helper.fail(band.planet().name() + " " + band.face() + " " + band.side() + " band block ("
                                    + (minX + dx) + ", " + y + ", " + (minZ + dz) + ") is " + here + ", its twin "
                                    + there);
                        }
                    }
                }
            }
            check(helper, noiseBiome(chunk, QuartPos.fromBlock(minX + 2), QuartPos.fromBlock(64),
                    QuartPos.fromBlock(minZ + 2)).equals(noiseBiome(twinChunk,
                    QuartPos.fromBlock(band.twinBlock(minX + 2, minZ + 2)[0]), QuartPos.fromBlock(64),
                    QuartPos.fromBlock(band.twinBlock(minX + 2, minZ + 2)[1]))), "the band has its twin's biome");
        }
        helper.succeed();
    }
```

- [ ] **Step 2: Bands and band terrain in `PlanetTerrain`.** Replace the whole file with:

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import io.github.chabelabela.worldengine.planet.SpawnSearch;

/**
 * Terrain samples for world generation, from the native core. A column in an overlap band has its twin's terrain
 * ({@link SeamBands}), so the band copies the neighbouring face exactly.
 *
 * <p>Failure policy: if the core is missing or a call fails, throw. On a worldgen thread that crashes the
 * server with this message instead of saving a hole or a half-written chunk into the world for good.
 */
public final class PlanetTerrain {
    private static volatile SeamBands bands;

    /** A chunk's 16x16 columns: the planet's kind, and each column's height and biome. */
    public static final class ChunkTerrain {
        private final PlanetKind kind;
        private final TerrainColumns columns;
        private final int[] index; // band column dz * 16 + dx -> twin column, or null on a face

        private ChunkTerrain(PlanetKind kind, TerrainColumns columns, int[] index) {
            this.kind = kind;
            this.columns = columns;
            this.index = index;
        }

        public PlanetKind kind() {
            return kind;
        }

        public int height(int dx, int dz) {
            int i = index == null ? dz * 16 + dx : index[dz * 16 + dx];
            return columns.height(i % 16, i / 16);
        }

        public PlanetBiome biome(int dx, int dz) {
            int i = index == null ? dz * 16 + dx : index[dz * 16 + dx];
            return columns.biome(i % 16, i / 16);
        }
    }

    /** One column's planet kind, height and biome. */
    public record ColumnTerrain(PlanetKind kind, int height, PlanetBiome biome) {
    }

    private PlanetTerrain() {
    }

    /** Every planet's overlap bands, built once from the core's fold. */
    public static SeamBands bands() {
        SeamBands built = bands;
        if (built == null) {
            built = SeamBands.build(folder());
            bands = built; // a race only builds the same bands twice
        }
        return built;
    }

    /** The core's fold, for {@link SeamBands} and {@link SeamCrossings}. */
    public static SeamCrossings.Folder folder() {
        NativeCore core = core();
        return (cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw);
    }

    /**
     * The terrain of the chunk whose north-west block column is ({@code minX}, {@code minZ}): its own on a face, its
     * twin's in a band (one batched call either way), or null in empty space.
     */
    public static ChunkTerrain chunk(int minX, int minZ, long worldSeed) {
        PlanetHit hit = Planets.at(minX, minZ);
        if (hit != null) {
            return new ChunkTerrain(hit.planet().kind(), sample(hit, worldSeed, 16, 16), null);
        }
        SeamBand band = bands().at(minX, minZ);
        if (band == null) {
            return null;
        }
        // The twin of a chunk-aligned band chunk is a chunk-aligned 16x16 block of the twin face, turned.
        int[] twinX = new int[256];
        int[] twinZ = new int[256];
        int minTwinX = Integer.MAX_VALUE;
        int minTwinZ = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            int[] twin = band.twinBlock(minX + (i & 15), minZ + (i >> 4));
            twinX[i] = twin[0];
            twinZ[i] = twin[1];
            minTwinX = Math.min(minTwinX, twin[0]);
            minTwinZ = Math.min(minTwinZ, twin[1]);
        }
        int[] index = new int[256];
        for (int i = 0; i < 256; i++) {
            index[i] = (twinZ[i] - minTwinZ) * 16 + (twinX[i] - minTwinX);
        }
        PlanetHit twin = Planets.at(minTwinX, minTwinZ);
        return new ChunkTerrain(band.planet().kind(), sample(twin, worldSeed, 16, 16), index);
    }

    /** One column's terrain: its own on a face, its twin's in a band, or null in empty space. */
    public static ColumnTerrain at(int x, int z, long worldSeed) {
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            SeamBand band = bands().at(x, z);
            if (band == null) {
                return null;
            }
            int[] twin = band.twinBlock(x, z);
            hit = Planets.at(twin[0], twin[1]);
        }
        TerrainColumns column = column(hit, worldSeed);
        return new ColumnTerrain(hit.planet().kind(), column.height(0, 0), column.biome(0, 0));
    }

    /**
     * True if the chunk at ({@code minX}, {@code minZ}) is in a band or is copied into one. Vanilla doesn't decorate
     * those chunks (no trees, ores or structures), so a band and the face strip it copies match block for block.
     */
    public static boolean isMirrored(int minX, int minZ) {
        if (Planets.at(minX, minZ) == null) {
            return bands().at(minX, minZ) != null;
        }
        return !bands().copiesOf(minX, minZ).isEmpty();
    }

    /** The {@code countX} by {@code countZ} columns whose north-west cell is {@code hit}'s cell. */
    public static TerrainColumns sample(PlanetHit hit, long worldSeed, int countX, int countZ) {
        PlanetSpec planet = hit.planet();
        FaceCell cell = hit.cell();
        try {
            return core().terrainColumns(planet.seed(worldSeed), planet.cells(), planet.kind(), cell.face().id(),
                    cell.x(), cell.z(), countX, countZ);
        } catch (NativeCoreException e) {
            throw new IllegalStateException("World Engine terrain failed on " + planet.name() + " face " + cell.face()
                    + " at cell (" + cell.x() + ", " + cell.z() + ")", e);
        }
    }

    /** One column. */
    public static TerrainColumns column(PlanetHit hit, long worldSeed) {
        return sample(hit, worldSeed, 1, 1);
    }

    /** {chunkX, chunkZ} of dry land near {@code planet}'s E1 centre; see {@link SpawnSearch}. */
    public static int[] landChunk(PlanetSpec planet, long worldSeed) {
        NativeCore core = core();
        long seed = planet.seed(worldSeed);
        try {
            return SpawnSearch.findLandChunk(planet, (face, x, z) ->
                    core.terrainColumns(seed, planet.cells(), planet.kind(), face.id(), x, z, 1, 1).height(0, 0),
                    SpawnSearch.MAX_RADIUS_STEPS);
        } catch (NativeCoreException e) {
            throw new IllegalStateException("World Engine terrain failed while looking for land on " + planet.name(), e);
        }
    }

    static NativeCore core() {
        NativeCore core = WorldEngineMod.core();
        if (core == null) {
            throw new IllegalStateException("World Engine's native core failed to load (see the log above), "
                    + "and a World Engine world can't be generated without it");
        }
        return core;
    }
}
```

- [ ] **Step 3: The generator.** In `UniverseChunkGenerator.java`:
  - Replace the class Javadoc's first paragraph (the one beginning "The universe level's generator") with:

```java
 * The universe level's generator: planet terrain from the native core where a chunk lies on a planet's
 * net (see {@link Planets}), a copy of the neighbouring face in the overlap bands beyond each seam (see
 * {@link io.github.chabelabela.worldengine.planet.SeamBands}), empty space everywhere else. Vanilla then decorates
 * the terrain through the biomes (trees, grass, ores, mobs) and places structures, except in the bands and the face
 * strips they copy, so those match block for block.
```

  - Remove the import of `TerrainColumns`, and add `import net.minecraft.world.level.WorldGenLevel;` after the `StructureManager` import.
  - Replace the four methods `createBiomes`, `buildTerrain`, `getBaseHeight` and `getBaseColumn` with the following. They contain the new `applyBiomeDecoration` between `buildTerrain` and `getBaseHeight`.

```java
    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState, Blender blender, StructureManager structures, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        PlanetTerrain.ChunkTerrain terrain = PlanetTerrain.chunk(minX, minZ, randomState.seed());
        if (terrain == null) {
            Holder<Biome> space = biomes.voidBiome();
            chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> space);
        } else {
            chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> {
                // Sample each 4x4 quart at its centre column, as UniverseBiomeSource#createResolver does.
                int dx = Math.clamp((quartX << 2) + 2 - minX, 0, 15);
                int dz = Math.clamp((quartZ << 2) + 2 - minZ, 0, 15);
                return biomes.holder(terrain.biome(dx, dz));
            });
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
            StructureManager structures, BiomeManager biomeManager, WorldGenRegion region,
            Set<Holder<Biome>> biomesInChunk) {
        ChunkPos pos = chunk.getPos();
        PlanetTerrain.ChunkTerrain terrain =
                PlanetTerrain.chunk(pos.getMinBlockX(), pos.getMinBlockZ(), randomState.seed());
        if (terrain == null) {
            return CompletableFuture.completedFuture(chunk);
        }
        PlanetKind kind = terrain.kind();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = Math.max(chunk.getMinY(), LevelShape.MIN_Y);
        int maxY = Math.min(chunk.getMaxY(), LevelShape.maxY());
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int height = terrain.height(dx, dz);
                PlanetBiome biome = terrain.biome(dx, dz);
                int top = Math.min(ColumnMaterials.topY(height, kind), maxY);
                for (int y = minY; y <= top; y++) {
                    Ground ground = ColumnMaterials.at(y, height, biome, kind);
                    if (ground != Ground.AIR) {
                        chunk.getSection(chunk.getSectionIndex(y))
                                .setBlockState(dx, y & 15, dz, GroundStates.of(ground), false);
                    }
                }
                // A heightmap keeps the highest block that matches it, so only the column's top blocks matter:
                // the solid surface for the ocean floor, and the water or the surface for the world surface.
                int surface = Math.min(LevelShape.surfaceY(height), maxY);
                if (surface >= minY) {
                    oceanFloor.update(dx, surface, dz,
                            GroundStates.of(ColumnMaterials.at(surface, height, biome, kind)));
                }
                if (top >= minY) {
                    worldSurface.update(dx, top, dz, GroundStates.of(ColumnMaterials.at(top, height, biome, kind)));
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /** Bands, and the face strips they copy, stay undecorated so both sides match block for block (M1e-2). */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        ChunkPos pos = chunk.getPos();
        if (PlanetTerrain.isMirrored(pos.getMinBlockX(), pos.getMinBlockZ())) {
            return;
        }
        super.applyBiomeDecoration(level, chunk, structures);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        PlanetTerrain.ColumnTerrain column = PlanetTerrain.at(x, z, randomState.seed());
        if (column == null) {
            return level.getMinY();
        }
        int height = column.height();
        PlanetBiome biome = column.biome();
        PlanetKind kind = column.kind();
        for (int y = Math.min(ColumnMaterials.topY(height, kind), level.getMaxY()); y >= level.getMinY(); y--) {
            if (type.isOpaque().test(GroundStates.of(ColumnMaterials.at(y, height, biome, kind)))) {
                return y + 1;
            }
        }
        return level.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        BlockState[] states = new BlockState[level.getHeight()];
        PlanetTerrain.ColumnTerrain column = PlanetTerrain.at(x, z, randomState.seed());
        if (column == null) {
            Arrays.fill(states, GroundStates.of(Ground.AIR));
        } else {
            for (int i = 0; i < states.length; i++) {
                states[i] = GroundStates.of(
                        ColumnMaterials.at(level.getMinY() + i, column.height(), column.biome(), column.kind()));
            }
        }
        return new NoiseColumn(level.getMinY(), states);
    }
```

- [ ] **Step 4: The biome source.** In `UniverseBiomeSource.java`:
  - Remove the `PlanetHit` import.
  - In the class Javadoc, change `{@code worldengine:void} off the planets.` to `{@code worldengine:void} off the planets. A column in an overlap band has its twin's biome.`
  - Replace `biomeAt` with:

```java
    /** The biome of block column (x, z), sampled at that one column (in a band, at its twin). */
    Holder<Biome> biomeAt(int x, int z) {
        if (Planets.at(x, z) == null && PlanetTerrain.bands().at(x, z) == null) {
            return voidBiome;
        }
        Long seed = worldSeed;
        if (seed == null) {
            throw new IllegalStateException("World Engine biomes were asked for before the level was created");
        }
        return holder(PlanetTerrain.at(x, z, seed).biome());
    }
```

- [ ] **Step 5: Compile and test** (JDK 25 only; otherwise CI in Task 7).

Run: `cd mod && ./gradlew build`
Expected: BUILD SUCCESSFUL, with `bandsCopyTheirTwin` among the passing GameTests.

- [ ] **Step 6: Commit.**

```bash
git add mod/src/main mod/src/gametest
git commit -m "M1e-2: band chunks copy their twin; bands and strips stay undecorated"
```

---

### Task 4: Crossing at the band's middle

`SeamCrossings.plan` changes as follows:
- **Return null (nothing happens):**
  - on a face;
  - in a band short of its middle;
  - after a move of more than `MAX_STEP` (a teleport);
  - when the last position was on neither a face nor a band.
- **At or past the middle:** a cross to the twin, with velocity and yaw turned. Players land one tick short of it, as in M1e-1.
- **In empty space after a face or band** (that is, no-man's land): a bounce back.

The handler remembers band positions as well as positions near face edges. It pre-loads with `SeamBands.preloadTargets`. M1e-1's whole-net test in `NativeSeamTest` used the old edge-crossing `plan`; `NativeBandTest` (Task 2) now does that job, so `NativeSeamTest` keeps only its direct `seamCross` tests.

**Files:**
- Modify (whole file): `mod/src/main/java/io/github/chabelabela/worldengine/planet/SeamCrossings.java`, `mod/src/main/java/io/github/chabelabela/worldengine/level/SeamCrossingHandler.java`
- Modify (whole file): `mod/src/test/java/io/github/chabelabela/worldengine/planet/SeamCrossingsTest.java`, `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeSeamTest.java`, `mod/src/gametest/java/io/github/chabelabela/worldengine/gametest/SeamGameTests.java`

- [ ] **Step 1: The unit tests.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SeamCrossingsTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int W = SeamBands.WIDTH;
    /** Level x of E3's east edge (a seam: the equator wraps onto E0's west edge). */
    private static final int E3_EAST = HOME.faceOriginX(PlanetFace.E3) + N;
    private static final int E0_WEST = HOME.faceOriginX(PlanetFace.E0);
    private static final int Z0 = HOME.faceOriginZ(PlanetFace.E3);

    /** E3's east band as the real fold makes it: a pure translation four faces west. */
    private static final SeamBand WRAP = new SeamBand(Planets.HOME, PlanetFace.E3, SeamBand.Side.EAST, PlanetFace.E0, 0,
            E3_EAST, Z0, E3_EAST + W, Z0 + N, -4.0 * N, 0.0);
    /** A made-up turned band beyond E0's west edge, to check that velocity and yaw are turned. */
    private static final SeamBand TURNED = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.WEST, PlanetFace.E3,
            1, E0_WEST - W, Z0, E0_WEST, Z0 + N, 5_000.0, 7_000.0);
    /** Overlaps TURNED's north end: no-man's land. */
    private static final SeamBand OVERLAP = new SeamBand(Planets.HOME, PlanetFace.E0, SeamBand.Side.NORTH,
            PlanetFace.NORTH, 0, E0_WEST - W, Z0, E0_WEST, Z0 + W, 0.0, 0.0);
    private static final SeamBands BANDS = new SeamBands(List.of(WRAP, TURNED, OVERLAP));

    @Test
    void onTheNetNothingHappens() {
        assertNull(SeamCrossings.plan(5, 5, 6, 5, 1, 0, 0, BANDS));
        // E1's east edge is joined to E2 in the net: crossing it is just walking.
        int e2 = HOME.faceOriginX(PlanetFace.E2);
        assertNull(SeamCrossings.plan(e2 - 0.5, 5, e2 + 0.5, 5, 1, 0, 0, BANDS));
    }

    @Test
    void walkingIntoABandIsJustWalking() {
        assertNull(SeamCrossings.plan(E3_EAST - 2.5, 100.5, E3_EAST + 10.5, 100.5, 0.3, 0, -90, BANDS));
        assertNull(SeamCrossings.plan(E3_EAST + 60.5, 100.5, E3_EAST + 63.75, 100.5, 0.3, 0, -90, BANDS));
    }

    @Test
    void atTheBandsMiddleItIsCarriedToTheTwin() {
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E3_EAST + 63.75, 100.5, E3_EAST + 64.25, 100.5, 0.3, 0, -90, BANDS));
        assertEquals(Planets.HOME, cross.planet());
        assertEquals(PlanetFace.E0, cross.face());
        assertEquals(E0_WEST + 64.25, cross.twinX());
        assertEquals(100.5, cross.twinZ());
        assertEquals(E0_WEST + 64.25 - 0.3, cross.x(), 1e-12); // a player lands one tick short of the twin
        assertEquals(100.5, cross.z());
        assertEquals(0.3, cross.vx());
        assertEquals(0.0, cross.vz());
        assertEquals(-90.0F, cross.yaw());
    }

    @Test
    void aTurnedBandTurnsVelocityAndYaw() {
        double x = E0_WEST - 64.5;
        double z = Z0 + 500.5;
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(x + 0.4, z, x, z, -0.4, 0.1, 90, BANDS));
        double[] twin = TURNED.toTwin(x, z);
        assertEquals(twin[0], cross.twinX());
        assertEquals(twin[1], cross.twinZ());
        // A quarter turn clockwise: (dx, dz) -> (-dz, dx); yaw + 90.
        assertEquals(-0.1, cross.vx(), 1e-12);
        assertEquals(-0.4, cross.vz(), 1e-12);
        assertEquals(-180.0F, cross.yaw());
        assertEquals(twin[0] + 0.1, cross.x(), 1e-12);
        assertEquals(twin[1] + 0.4, cross.z(), 1e-12);
    }

    @Test
    void inNoMansLandItGoesBackToWhereItLastStood() {
        SeamCrossings.Bounce bounce = assertInstanceOf(SeamCrossings.Bounce.class,
                SeamCrossings.plan(E0_WEST - 10.5, Z0 + W + 0.5, E0_WEST - 10.5, Z0 + W - 0.5, 0, -1, 180, BANDS));
        assertEquals(E0_WEST - 10.5, bounce.x());
        assertEquals(Z0 + W + 0.5, bounce.z());
    }

    @Test
    void somethingTeleportedFarAwayIsLeftAlone() {
        assertNull(SeamCrossings.plan(E3_EAST - 2.5, 100.5, E3_EAST + 20_000.5, 100.5, 0, 0, 0, BANDS));
        assertNull(SeamCrossings.plan(E3_EAST + 10.5, 100.5, E3_EAST + 10.5 + SeamCrossings.MAX_STEP + 1, 100.5, 0, 0, 0,
                BANDS));
    }

    @Test
    void somethingThatWasNotOnAPlanetIsLeftAlone() {
        assertNull(SeamCrossings.plan(70_000, 0, 70_001, 0, 1, 0, 0, BANDS));
    }

    @Test
    void nearEdgeLooksAtAllFourEdges() {
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 3, 8_000)), 16));
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 8_000, N - 1)), 16));
        assertFalse(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 16, N - 17)), 16));
    }
}
```

- [ ] **Step 2: Run them to see them fail.**

Run: `cd mod && ./gradlew test --tests '*SeamCrossingsTest'`
Expected: compilation fails (`plan` takes a `SeamBands` now).

- [ ] **Step 3: The new rules.**

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;

/**
 * What happens to something that leaves a planet's net across a seam: one of the 7 cube edges that the cross-shaped
 * net doesn't join (ADR 0001; E2 in the M1b spike; M1e-2).
 *
 * <p>Beyond each seam lies an overlap band holding a copy of the neighbouring face ({@link SeamBands}). Walking into
 * the band is just walking. At the band's middle, {@link SeamBands#TELEPORT_DEPTH} blocks out, it is carried to the
 * same spot on the neighbouring face, keeping its height, with its grid velocity and yaw turned by the fold's quarter
 * turns. Where two bands meet near a cube corner there is nothing, so it is put back where it last stood.
 */
public final class SeamCrossings {
    /**
     * Something that moved further than this along either axis since its last position was teleported (a command, a
     * portal, a ship handoff), not walking, so it is left alone.
     */
    public static final int MAX_STEP = 16;

    /** Folds grid point (x, z) beyond an edge of {@code face}; null if it can't. In the mod: {@code NativeCore::seamCross}. */
    @FunctionalInterface
    public interface Folder {
        SeamCrossing fold(int cells, PlanetFace face, double x, double z, double vx, double vz, float yaw);
    }

    /** What to do with something in a band or beyond the net. */
    public sealed interface Outcome permits Cross, Bounce {
    }

    /**
     * Carry it to its twin ({@code twinX}, {@code twinZ}) on {@code face}, keeping its height, with horizontal velocity
     * ({@code vx}, {@code vz}) and {@code yaw}. ({@code x}, {@code z}) is one tick of that velocity short of the twin:
     * where a player lands, because a player's client advances once more after a teleport (E2). Things the server
     * moves land on the twin itself.
     */
    public record Cross(PlanetSpec planet, PlanetFace face, double x, double z, double vx, double vz, float yaw,
            double twinX, double twinZ) implements Outcome {
    }

    /** Nowhere to go: put it back at level position ({@code x}, {@code z}) and stop its horizontal movement. */
    public record Bounce(double x, double z) implements Outcome {
    }

    private SeamCrossings() {
    }

    /**
     * The outcome for something now at level position ({@code x}, {@code z}), moving ({@code vx}, {@code vz}) blocks
     * per tick and looking along {@code yaw}, whose last position on a face or in a band was ({@code lastX},
     * {@code lastZ}). Null if it is on a face, in a band short of its middle, moved more than {@link #MAX_STEP}, or its
     * last position wasn't on a face or in a band.
     */
    public static Outcome plan(double lastX, double lastZ, double x, double z, double vx, double vz, float yaw,
            SeamBands bands) {
        if (Math.abs(x - lastX) > MAX_STEP || Math.abs(z - lastZ) > MAX_STEP) {
            return null;
        }
        if (Planets.at(floor(x), floor(z)) != null) {
            return null;
        }
        SeamBand band = bands.at(floor(x), floor(z));
        if (band != null) {
            if (band.depth(x, z) < SeamBands.TELEPORT_DEPTH) {
                return null;
            }
            double[] twin = band.toTwin(x, z);
            double[] v = band.rotateDirection(vx, vz);
            return new Cross(band.planet(), band.twinFace(), twin[0] - v[0], twin[1] - v[1], v[0], v[1],
                    band.rotateYaw(yaw), twin[0], twin[1]);
        }
        if (Planets.at(floor(lastX), floor(lastZ)) == null && bands.at(floor(lastX), floor(lastZ)) == null) {
            return null;
        }
        return new Bounce(lastX, lastZ);
    }

    /** True if {@code hit}'s cell is within {@code margin} blocks of one of its face's four edges. */
    public static boolean nearEdge(PlanetHit hit, int margin) {
        FaceCell cell = hit.cell();
        int n = hit.planet().cells();
        return cell.x() < margin || cell.z() < margin || cell.x() >= n - margin || cell.z() >= n - margin;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
```

- [ ] **Step 4: The handler.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Carries everything that walks across a seam to the neighbouring face, at the end of every server tick, in the
 * universe level (see {@link SeamCrossings}; ADR 0001; M1e-2).
 *
 * <ul>
 *   <li>Beyond each seam lies an overlap band copying the neighbouring face ({@link SeamBands}). Something in a band
 *       short of its middle is just walking; at the middle it is teleported to its twin on the neighbouring face,
 *       with its grid velocity and yaw turned. A player lands one tick of travel short, as its client advances once
 *       more after a teleport, unless that point is off the net.
 *   <li>Positions on a face within {@link #EDGE_MARGIN} blocks of an edge, and in a band, are remembered. Something
 *       found where nothing is, in the no-man's land near a cube corner, goes back to where it last was, with no
 *       horizontal speed. Something that moved further than {@link SeamCrossings#MAX_STEP} was teleported away, and
 *       is left alone.
 *   <li>After a crossing, an entity isn't checked for {@link #COOLDOWN_TICKS} ticks.
 *   <li>Players near a seam get the far side pre-loaded, so the destination is generated before they arrive.
 * </ul>
 *
 * <p>Passengers ride along with their vehicle. Server thread only.
 */
public final class SeamCrossingHandler {
    /** Ticks after a crossing during which an entity isn't checked again. */
    public static final int COOLDOWN_TICKS = 3;
    /** Entities this close to a face edge have their position remembered: more than a tick's travel (10 blocks). */
    public static final int EDGE_MARGIN = 16;
    /** Chunk radius of the pre-loaded area around the far side. */
    public static final int PRELOAD_RADIUS = 2;
    /** Pre-load tickets are refreshed this often, and expire after {@link #PRELOAD_TICKS} without a refresh. */
    static final int PRELOAD_REFRESH_TICKS = 20;
    static final long PRELOAD_TICKS = 100;

    private static TicketType preloadTicket;
    private static final Map<UUID, double[]> LAST_POSITION = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private SeamCrossingHandler() {
    }

    /** Registers the pre-load ticket type and the tick hook. Call once, from the mod initializer. */
    public static void register() {
        preloadTicket = Registry.register(BuiltInRegistries.TICKET_TYPE, WorldEngineMod.id("seam_preload"),
                new TicketType(PRELOAD_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
        ServerTickEvents.END_SERVER_TICK.register(SeamCrossingHandler::onEndTick);
        // Tick counts and positions are per server run; they must not survive a singleplayer stop and start.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LAST_POSITION.clear();
            COOLDOWN_UNTIL.clear();
        });
    }

    private static void onEndTick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        if (overworld.getChunkSource().getGenerator() instanceof UniverseChunkGenerator) {
            tickLevel(overworld);
        }
        if (server.getTickCount() % 600 == 0) {
            forgetMissing(server);
        }
    }

    /** Checks every entity in {@code level} once. */
    private static void tickLevel(ServerLevel level) {
        SeamBands bands = PlanetTerrain.bands();
        long now = level.getServer().getTickCount();
        List<Runnable> moves = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            Runnable move = check(level, entity, now, bands);
            if (move != null) {
                moves.add(move);
            }
        }
        moves.forEach(Runnable::run); // after the loop: never move entities while iterating the level's list
    }

    /**
     * Checks one entity now, and moves it if it has crossed. For GameTests: they run in a flat test level, where an
     * entity in a chunk loaded that same tick may not be in the level's entity list yet.
     */
    public static void checkNow(ServerLevel level, Entity entity) {
        Runnable move = check(level, entity, level.getServer().getTickCount(), PlanetTerrain.bands());
        if (move != null) {
            move.run();
        }
    }

    /**
     * Remembers where {@code entity} is, pre-loads the far side for a player near a seam, and returns the teleport to
     * run if it has reached a band's middle or no-man's land, or null.
     */
    private static Runnable check(ServerLevel level, Entity entity, long now, SeamBands bands) {
        if (entity.isPassenger() || entity.isRemoved() || entity.isSpectator()) {
            return null;
        }
        UUID id = entity.getUUID();
        Long until = COOLDOWN_UNTIL.get(id);
        if (until != null) {
            if (until > now) {
                return null;
            }
            COOLDOWN_UNTIL.remove(id);
        }
        double x = entity.getX();
        double z = entity.getZ();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (entity instanceof ServerPlayer && now % PRELOAD_REFRESH_TICKS == 0) {
            for (double[] target : bands.preloadTargets(x, z)) {
                level.getChunkSource().addTicketWithRadius(preloadTicket,
                        new ChunkPos(Mth.floor(target[0]) >> 4, Mth.floor(target[1]) >> 4), PRELOAD_RADIUS);
            }
        }
        PlanetHit hit = Planets.at(bx, bz);
        if (hit != null) {
            if (SeamCrossings.nearEdge(hit, EDGE_MARGIN)) {
                LAST_POSITION.put(id, new double[] {x, z});
            } else {
                LAST_POSITION.remove(id);
            }
            return null;
        }
        SeamBand band = bands.at(bx, bz);
        if (band != null && band.depth(x, z) < SeamBands.TELEPORT_DEPTH) {
            LAST_POSITION.put(id, new double[] {x, z}); // walking in the band
            return null;
        }
        double[] last = LAST_POSITION.get(id);
        if (last == null && band != null) {
            last = new double[] {x, z}; // beyond a band's middle without having walked there (spawned, say)
        }
        if (last == null) {
            return null;
        }
        Vec3 velocity = entity instanceof ServerPlayer player ? player.getKnownMovement() : entity.getDeltaMovement();
        SeamCrossings.Outcome outcome =
                SeamCrossings.plan(last[0], last[1], x, z, velocity.x, velocity.z, entity.getYRot(), bands);
        if (outcome instanceof SeamCrossings.Cross cross) {
            LAST_POSITION.put(id, new double[] {cross.twinX(), cross.twinZ()});
            COOLDOWN_UNTIL.put(id, now + COOLDOWN_TICKS);
            // Only a player's client advances once more after the teleport. If that landing isn't on the net, use the
            // twin itself: never leave it where nothing is.
            boolean shortOfTwin = entity instanceof ServerPlayer
                    && Planets.at(Mth.floor(cross.x()), Mth.floor(cross.z())) != null;
            double landX = shortOfTwin ? cross.x() : cross.twinX();
            double landZ = shortOfTwin ? cross.z() : cross.twinZ();
            return () -> {
                if (!entity.isRemoved()) {
                    entity.teleport(new TeleportTransition(level, new Vec3(landX, entity.getY(), landZ),
                            new Vec3(cross.vx(), velocity.y, cross.vz()), cross.yaw(), entity.getXRot(),
                            TeleportTransition.DO_NOTHING));
                }
            };
        }
        if (outcome instanceof SeamCrossings.Bounce bounce) {
            return () -> {
                if (!entity.isRemoved()) {
                    entity.teleport(new TeleportTransition(level, new Vec3(bounce.x(), entity.getY(), bounce.z()),
                            new Vec3(0, velocity.y, 0), entity.getYRot(), entity.getXRot(),
                            TeleportTransition.DO_NOTHING));
                }
            };
        }
        LAST_POSITION.remove(id); // where nothing is, and not having walked there: teleported away
        return null;
    }

    /** Drops remembered state for entities that no longer exist anywhere. */
    private static void forgetMissing(MinecraftServer server) {
        LAST_POSITION.keySet().removeIf(id -> !exists(server, id));
        COOLDOWN_UNTIL.keySet().removeIf(id -> !exists(server, id));
    }

    private static boolean exists(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) != null) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: Trim `NativeSeamTest`** to its direct `seamCross` tests:

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code we_seam_cross} through the bridge. */
class NativeSeamTest {
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void theEquatorWrapIsATranslation() {
        assertEquals(new SeamCrossing(0, 0.25, 100.5, 0, 0.3, 0.0, -90.0F),
                core.seamCross(16_384, 3, 16_384.25, 100.5, 0.3, 0.0, -90.0F));
    }

    @Test
    void aRotatedSeamTurnsVelocityAndYaw() {
        // North's west edge meets E0 three quarter turns round: running west continues south (E2 in the spike).
        SeamCrossing c = core.seamCross(16_384, 4, -0.25, 100.5, -0.281, 0.0, 90.0F);
        assertEquals(0, c.face());
        assertEquals(3, c.turns());
        assertEquals(0.0, c.vx());
        assertEquals(0.281, c.vz());
        assertEquals(0.0F, c.yaw());
    }

    @Test
    void pointsItCannotFoldGiveNull() {
        assertNull(core.seamCross(16_384, 0, 10.0, 10.0, 0, 0, 0)); // on the face
        assertNull(core.seamCross(16_384, 0, -1.0, -1.0, 0, 0, 0)); // beyond a corner
    }

    @Test
    void badInputThrows() {
        assertThrows(NativeCoreException.class, () -> core.seamCross(16_384, 6, -1.0, 10.0, 0, 0, 0));
        assertThrows(NativeCoreException.class, () -> core.seamCross(16_383, 0, -1.0, 10.0, 0, 0, 0));
    }
}
```

- [ ] **Step 6: The GameTests.**

```java
package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.SeamCrossingHandler;
import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Seam crossing in a real server. The GameTest level is flat, but {@link SeamCrossingHandler} only needs level
 * coordinates, so these tests place a dropped item at the home planet's net coordinates and run the handler's check on
 * it directly ({@link SeamCrossingHandler#checkNow}), all within one tick, with every chunk they touch loaded.
 */
public class SeamGameTests {
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int N = Planets.HOME.cells();
    private static final double Y = 100.0;

    @GameTest
    public void theEquatorWrapKeepsMomentum(GameTestHelper helper) {
        double z = HOME.faceOriginZ(PlanetFace.E3) + 100.5;
        double east = HOME.faceOriginX(PlanetFace.E3) + N;
        double west = HOME.faceOriginX(PlanetFace.E0);
        double middle = SeamBands.TELEPORT_DEPTH;
        Scene.run(helper, east + middle - 1.5, z, new double[] {east + middle + 0.25, z, west + middle + 0.25, z},
                (scene, item) -> {
                    // E3's east band copies E0's west strip: the equator wrap is a translation four faces west.
                    scene.move(item, east + middle + 0.25, z, new Vec3(0.3, 0, 0), -90.0F);
                    // An item lands on its twin (only players land one tick short).
                    scene.expect(item, west + middle + 0.25, z, 0.3, 0.0, -90.0F, "across the equator wrap");
                    SeamCrossingHandler.checkNow(helper.getLevel(), item);
                    scene.expect(item, west + middle + 0.25, z, 0.3, 0.0, -90.0F, "still there during the cooldown");
                });
    }

    @GameTest
    public void walkingIntoABandIsJustWalking(GameTestHelper helper) {
        double z = HOME.faceOriginZ(PlanetFace.E3) + 100.5;
        double east = HOME.faceOriginX(PlanetFace.E3) + N;
        Scene.run(helper, east - 2.5, z, new double[] {east + 10.5, z}, (scene, item) -> {
            scene.move(item, east + 10.5, z, new Vec3(0.3, 0, 0), -90.0F);
            scene.expect(item, east + 10.5, z, 0.3, 0.0, -90.0F, "10 blocks into E3's east band, untouched");
        });
    }

    @GameTest
    public void aRotatedSeamTurnsMomentumAndLook(GameTestHelper helper) {
        int ox = HOME.faceOriginX(PlanetFace.NORTH);
        int oz = HOME.faceOriginZ(PlanetFace.NORTH);
        double depth = SeamBands.TELEPORT_DEPTH + 0.25;
        // The oracle is the core's fold itself, at that depth: the band's map must agree with it.
        SeamCrossing fold = WorldEngineMod.core().seamCross(N, PlanetFace.NORTH.id(), -depth, 100.5, -0.281, 0, 90);
        double foldX = HOME.faceOriginX(PlanetFace.E0) + fold.x();
        double foldZ = HOME.faceOriginZ(PlanetFace.E0) + fold.z();
        double[] load = {ox - depth, oz + 100.5, foldX, foldZ};
        Scene.run(helper, ox - depth + 1.0, oz + 100.5, load, (scene, item) -> {
            scene.move(item, ox - depth, oz + 100.5, new Vec3(-0.281, 0, 0), 90.0F);
            // North's west edge meets E0 three quarter turns round: running west carries on running south.
            scene.expect(item, foldX, foldZ, 0.0, 0.281, 0.0F, "across North's west band");
        });
    }

    @GameTest
    public void whereTwoBandsMeetItGoesBack(GameTestHelper helper) {
        // E0's north band and North's west band meet in a 128 x 128 square by the cube corner of E0, E1 and North.
        int cornerX = HOME.faceOriginX(PlanetFace.NORTH);
        int cornerZ = HOME.faceOriginZ(PlanetFace.E0);
        double z = cornerZ - 8.5;
        double outside = cornerX - SeamBands.WIDTH - 10.5;
        double inside = cornerX - SeamBands.WIDTH + 1.5;
        helper.assertTrue(PlanetTerrain.bands().at((int) Math.floor(inside), (int) Math.floor(z)) == null,
                "the square is no-man's land");
        Scene.run(helper, outside, z, new double[] {inside, z}, (scene, item) -> {
            scene.move(item, inside, z, new Vec3(0.4, 0, 0), -90.0F);
            scene.expect(item, outside, z, 0.0, 0.0, -90.0F, "back out of no-man's land");
        });
    }

    @GameTest
    public void aFarTeleportIsNotUndone(GameTestHelper helper) {
        double west = HOME.faceOriginX(PlanetFace.E0);
        double z = HOME.faceOriginZ(PlanetFace.E0) + 100.5;
        double far = west - 1_000 + 0.5;
        Scene.run(helper, west + 2.5, z, new double[] {far, z}, (scene, item) -> {
            // Commanded far away, off the net: it is not carried or put back.
            scene.move(item, far, z, Vec3.ZERO, 0.0F);
            scene.expect(item, far, z, 0.0, 0.0, 0.0F, "teleported far off the net");
        });
    }

    @GameTest
    public void beyondACornerItGoesBack(GameTestHelper helper) {
        int ox = HOME.faceOriginX(PlanetFace.E0);
        int oz = HOME.faceOriginZ(PlanetFace.E0);
        Scene.run(helper, ox + 2.5, oz + 2.5, new double[] {ox - 0.5, oz - 0.5}, (scene, item) -> {
            scene.move(item, ox - 0.5, oz - 0.5, new Vec3(-1, 0, -1), 135.0F);
            scene.expect(item, ox + 2.5, oz + 2.5, 0.0, 0.0, 135.0F, "back from beyond E0's north-west corner");
        });
    }

    @GameTest
    public void edgesTheNetJoinsAreJustWalking(GameTestHelper helper) {
        double edge = HOME.faceOriginX(PlanetFace.E2);
        double z = HOME.faceOriginZ(PlanetFace.E2) + 100.5;
        Scene.run(helper, edge - 2.5, z, new double[] {edge + 0.5, z}, (scene, item) -> {
            scene.move(item, edge + 0.5, z, new Vec3(0.3, 0, 0), -90.0F);
            scene.expect(item, edge + 0.5, z, 0.3, 0.0, -90.0F, "from E1 onto E2, untouched");
        });
    }

    /** One dropped item at net coordinates, with the chunks it uses kept loaded, cleaned up afterwards. */
    private static final class Scene {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final List<ChunkPos> tickets = new ArrayList<>();

        private Scene(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        /**
         * Loads the chunks at (x, z) and at every {x, z} pair in {@code alsoLoad} (where the steps move the item and
         * where it lands), puts a still item at (x, z), lets the handler remember it, runs {@code steps}, and cleans
         * up.
         */
        static void run(GameTestHelper helper, double x, double z, double[] alsoLoad, StepsBody steps) {
            Scene scene = new Scene(helper);
            scene.load(x, z);
            for (int i = 0; i < alsoLoad.length; i += 2) {
                scene.load(alsoLoad[i], alsoLoad[i + 1]);
            }
            ItemEntity item = new ItemEntity(scene.level, x, Y, z, new ItemStack(Items.STONE));
            item.setNoGravity(true);
            item.setDeltaMovement(Vec3.ZERO);
            scene.level.addFreshEntity(item);
            try {
                SeamCrossingHandler.checkNow(scene.level, item);
                steps.accept(scene, item);
                helper.succeed();
            } finally {
                item.discard();
                scene.tickets.forEach(pos -> scene.level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 1));
            }
        }

        /** Moves the item (as its own tick would) and checks it once. */
        void move(Entity item, double x, double z, Vec3 velocity, float yaw) {
            item.snapTo(x, Y, z, yaw, 0.0F);
            item.setDeltaMovement(velocity);
            SeamCrossingHandler.checkNow(level, item);
        }

        void expect(Entity item, double x, double z, double vx, double vz, float yaw, String what) {
            String got = String.format(Locale.ROOT, "%s: at (%.6f, %.6f) moving (%.6f, %.6f) yaw %.2f", what,
                    item.getX(), item.getZ(), item.getDeltaMovement().x, item.getDeltaMovement().z, item.getYRot());
            helper.assertTrue(Math.abs(item.getX() - x) < 1e-9 && Math.abs(item.getZ() - z) < 1e-9, got);
            helper.assertTrue(Math.abs(item.getY() - Y) < 1e-9, got + ": height kept");
            helper.assertTrue(Math.abs(item.getDeltaMovement().x - vx) < 1e-9
                    && Math.abs(item.getDeltaMovement().z - vz) < 1e-9, got + ": velocity");
            helper.assertTrue(Math.floorMod(Math.round(item.getYRot() - yaw), 360) == 0, got + ": yaw");
        }

        private void load(double x, double z) {
            ChunkPos pos = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            level.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 1);
            level.getChunk(pos.x(), pos.z());
            tickets.add(pos);
        }
    }

    /** The part of a test that runs once its scene has settled. */
    @FunctionalInterface
    private interface StepsBody {
        void accept(Scene scene, Entity item);
    }
}
```

- [ ] **Step 7: Run the tests.**

Run: `cd mod && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit.**

```bash
git add mod/src
git commit -m "M1e-2: cross at the band's middle; bounce out of no-man's land"
```

---

### Task 5: Edit mirroring

Every block change Minecraft sends to clients goes through `ServerLevel.sendBlockUpdated`. A mixin at its head hands the change to `SeamMirror`:
- **In a band:** it is copied to its twin column, turned by the band's quarter turns, and from there into a second band if the twin column sits near a face corner.
- **On a face strip:** it is copied to every band column that copies it, turned back.
- **How the copy is set:** with `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`, so no neighbour updates, no shape updates and no drops. A re-entry guard stops the copy from being mirrored back.

The copy doesn't trigger anything on its own side. Whatever the change causes on the side where it happened, such as a fence connecting or water flowing, is mirrored as each block changes.

GameTests switch mirroring on for the flat GameTest level (`SeamMirror.enableFor`) and edit blocks at net coordinates.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/level/SeamMirror.java`, `mod/src/main/java/io/github/chabelabela/worldengine/mixin/ServerLevelMixin.java`, `mod/src/main/resources/worldengine.mixins.json`
- Modify: `mod/src/main/resources/fabric.mod.json`
- Create: `mod/src/gametest/java/io/github/chabelabela/worldengine/gametest/SeamMirrorGameTests.java`
- Modify: `mod/src/gametest/resources/fabric.mod.json`

- [ ] **Step 1: The GameTests.**

```java
package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.SeamMirror;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Edit mirroring in a real server. Mirroring is switched on for the flat GameTest level, and the tests edit blocks at
 * the home planet's net coordinates there: in an overlap band, and on the face strip it copies.
 */
public class SeamMirrorGameTests {
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int N = Planets.HOME.cells();
    private static final int Y = 100;

    @GameTest
    public void aBlockPlacedInABandAppearsOnItsTwin(GameTestHelper helper) {
        int east = HOME.faceOriginX(PlanetFace.E3) + N;
        int west = HOME.faceOriginX(PlanetFace.E0);
        int z = HOME.faceOriginZ(PlanetFace.E3) + 300;
        // E3's east band copies E0's west strip, unturned: 10 blocks into the band is 10 blocks into E0.
        run(helper, new int[] {east + 10, z, west + 10, z}, level -> {
            level.setBlock(new BlockPos(east + 10, Y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            expect(helper, level, west + 10, z, Blocks.STONE.defaultBlockState(), "stone mirrored onto E0");
            level.setBlock(new BlockPos(east + 10, Y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            expect(helper, level, west + 10, z, Blocks.AIR.defaultBlockState(), "breaking it is mirrored too");
        });
    }

    @GameTest
    public void aBlockPlacedOnTheFaceAppearsInTheBand(GameTestHelper helper) {
        int east = HOME.faceOriginX(PlanetFace.E3) + N;
        int west = HOME.faceOriginX(PlanetFace.E0);
        int z = HOME.faceOriginZ(PlanetFace.E0) + 400;
        run(helper, new int[] {west + 20, z, east + 20, z}, level -> {
            level.setBlock(new BlockPos(west + 20, Y, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
            expect(helper, level, east + 20, z, Blocks.GLASS.defaultBlockState(), "glass mirrored into E3's band");
        });
    }

    @GameTest
    public void facingTurnsWithTheSeam(GameTestHelper helper) {
        // North's west band copies E0's north strip three quarter turns round (E2 in the M1b spike: running west
        // carries on running south), so stairs facing west in the band face south on E0.
        int ox = HOME.faceOriginX(PlanetFace.NORTH);
        int oz = HOME.faceOriginZ(PlanetFace.NORTH);
        int bandX = ox - 10;
        int bandZ = oz + 500;
        int[] twin = PlanetTerrain.bands().at(bandX, bandZ).twinBlock(bandX, bandZ);
        run(helper, new int[] {bandX, bandZ, twin[0], twin[1]}, level -> {
            BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
            level.setBlock(new BlockPos(bandX, Y, bandZ), stairs, Block.UPDATE_ALL);
            BlockState copy = level.getBlockState(new BlockPos(twin[0], Y, twin[1]));
            helper.assertTrue(copy.is(Blocks.OAK_STAIRS), "stairs mirrored onto E0, got " + copy);
            helper.assertTrue(copy.getValue(StairBlock.FACING) == Direction.SOUTH, "facing south, got " + copy);
            helper.assertTrue(Planets.at(twin[0], twin[1]).cell().face() == PlanetFace.E0, "the twin is on E0");
        });
    }

    private static void expect(GameTestHelper helper, ServerLevel level, int x, int z, BlockState state, String what) {
        BlockState got = level.getBlockState(new BlockPos(x, Y, z));
        helper.assertTrue(got == state, what + ": got " + got);
    }

    /** Loads the chunks at each {x, z} pair, mirrors edits in the test level while {@code body} runs, cleans up. */
    private static void run(GameTestHelper helper, int[] load, Consumer<ServerLevel> body) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> tickets = new ArrayList<>();
        for (int i = 0; i < load.length; i += 2) {
            ChunkPos pos = new ChunkPos(load[i] >> 4, load[i + 1] >> 4);
            level.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 1);
            level.getChunk(pos.x(), pos.z());
            tickets.add(pos);
        }
        SeamMirror.enableFor(level);
        try {
            body.accept(level);
            helper.succeed();
        } finally {
            SeamMirror.disableFor(level);
            for (int i = 0; i < load.length; i += 2) {
                level.setBlock(new BlockPos(load[i], Y, load[i + 1]), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            tickets.forEach(pos -> level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 1));
        }
    }
}
```

In `mod/src/gametest/resources/fabric.mod.json`, the entrypoint list becomes:

```json
    "fabric-gametest": [
      "io.github.chabelabela.worldengine.gametest.UniverseGameTests",
      "io.github.chabelabela.worldengine.gametest.SeamGameTests",
      "io.github.chabelabela.worldengine.gametest.SeamMirrorGameTests"
    ]
```

- [ ] **Step 2: The mirror.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps each overlap band and the face strip it copies in step (M1e-2). Every block change on either side is made
 * on the other too, turned by the seam's quarter turns, so stairs, doors and other facing blocks still face the same
 * way across the seam.
 *
 * <p>Called for every block change vanilla sends to clients ({@code ServerLevel.sendBlockUpdated}, through
 * {@code ServerLevelMixin}). The copy is set without neighbour or shape updates and without drops, so it can't start
 * chains of updates of its own or duplicate items; whatever the change causes on its own side (a fence connecting, a
 * torch dropping) is copied as it happens. Block entity contents (chest items, sign text) are not copied. Server
 * thread only.
 */
public final class SeamMirror {
    private static final Set<ServerLevel> ENABLED_FOR_TESTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean copying;

    private SeamMirror() {
    }

    /** Mirrors edits in {@code level} too, though it isn't a universe level. For GameTests. */
    public static void enableFor(ServerLevel level) {
        ENABLED_FOR_TESTS.add(level);
    }

    public static void disableFor(ServerLevel level) {
        ENABLED_FOR_TESTS.remove(level);
    }

    /** Copies the block now at {@code pos} to its twin, or twins, if it is in a band or a strip a band copies. */
    public static void onBlockUpdated(ServerLevel level, BlockPos pos, BlockState state) {
        if (copying || WorldEngineMod.core() == null) {
            return;
        }
        if (!(level.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)
                && !ENABLED_FOR_TESTS.contains(level)) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        PlanetHit hit = Planets.at(x, z);
        SeamBands bands;
        if (hit != null) {
            if (!SeamCrossings.nearEdge(hit, SeamBands.WIDTH)) {
                return; // the common case: nowhere near a seam
            }
            bands = PlanetTerrain.bands();
            for (SeamBands.Column copy : bands.copiesOf(x, z)) {
                set(level, copy.x(), pos.getY(), copy.z(), state, (4 - copy.band().turns()) % 4);
            }
            return;
        }
        bands = PlanetTerrain.bands();
        SeamBand band = bands.at(x, z);
        if (band == null) {
            return;
        }
        int[] twin = band.twinBlock(x, z);
        set(level, twin[0], pos.getY(), twin[1], state, band.turns());
        // Near a face corner the twin is copied into a second band as well; keep that one in step too.
        for (SeamBands.Column copy : bands.copiesOf(twin[0], twin[1])) {
            if (copy.band() != band) {
                set(level, copy.x(), pos.getY(), copy.z(), state, (band.turns() + 4 - copy.band().turns()) % 4);
            }
        }
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state, int turns) {
        copying = true;
        try {
            level.setBlock(new BlockPos(x, y, z), state.rotate(rotation(turns)),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        } finally {
            copying = false;
        }
    }

    /** Clockwise quarter turns as Minecraft's {@link Rotation} (east turns into south, like {@code QuarterTurns}). */
    static Rotation rotation(int turns) {
        return switch (turns) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }
}
```

- [ ] **Step 3: The mixin and its config.**

```java
package io.github.chabelabela.worldengine.mixin;

import io.github.chabelabela.worldengine.level.SeamMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change vanilla sends to clients passes through {@code ServerLevel.sendBlockUpdated}; edits in an
 * overlap band or the face strip it copies are mirrored to the other side from here (M1e-2). The M1b spike used the
 * same hook for ship edits.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void worldengine$mirrorSeamEdits(BlockPos pos, BlockState old, BlockState current, int flags,
            CallbackInfo ci) {
        SeamMirror.onBlockUpdated((ServerLevel) (Object) this, pos, current);
    }
}
```

`mod/src/main/resources/worldengine.mixins.json`:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.chabelabela.worldengine.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "ServerLevelMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

In `mod/src/main/resources/fabric.mod.json`, add the `mixins` entry between `entrypoints` and `depends`:

```json
  "mixins": [
    "worldengine.mixins.json"
  ],
```

- [ ] **Step 4: Run the tests.**

Run: `cd mod && ./gradlew build`
Expected: BUILD SUCCESSFUL, with the 3 `SeamMirrorGameTests` passing.

- [ ] **Step 5: Commit.**

```bash
git add mod/src
git commit -m "M1e-2: mirror edits between bands and the strips they copy"
```

---

### Task 6: `/worldengine where` in a band

In a band, `where` says which band you're in, how deep, and which face column you're looking at a copy of.

**Files:**
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineCommands.java`

- [ ] **Step 1:** Add `import io.github.chabelabela.worldengine.planet.SeamBand;` after the `Planets` import. In `where`, replace the block

```java
        if (hit == null) {
            reply(source, String.format(Locale.ROOT, "(%d, %d): empty space", x, z));
            return Command.SINGLE_SUCCESS;
        }
```

with:

```java
        if (hit == null) {
            SeamBand band = WorldEngineMod.core() == null ? null : PlanetTerrain.bands().at(x, z);
            if (band == null) {
                reply(source, String.format(Locale.ROOT, "(%d, %d): empty space", x, z));
            } else {
                int[] twin = band.twinBlock(x, z);
                reply(source, String.format(Locale.ROOT,
                        "(%d, %d): %s, the band beyond %s's %s edge, %.0f blocks deep, copying %s at (%d, %d)",
                        x, z, band.planet().name(), band.face(), band.side().name().toLowerCase(Locale.ROOT),
                        Math.floor(band.depth(x + 0.5, z + 0.5)), band.twinFace(), twin[0], twin[1]));
            }
            return Command.SINGLE_SUCCESS;
        }
```

- [ ] **Step 2: Compile.** Run: `cd mod && ./gradlew compileJava`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineCommands.java
git commit -m "M1e-2: /worldengine where reports overlap bands"
```

---

### Task 7: Push, CI, docs

- [ ] **Step 1: Push and watch CI.** Every job must pass. The `mod` job runs the GameTests; `mod-tests` runs `NativeBandTest` on Windows and macOS.

- [ ] **Step 2: Docs.**
  - **`docs/ROADMAP.md` §5 M1:** in the "Seams" bullet, add "**overlap bands and edit mirroring done in M1e-2** ([plan](superpowers/plans/2026-11-22-m1e2-overlap-bands.md))". Add an M1e-2 line to the M1 plans list, and update "Next steps".
  - **`docs/superpowers/plans/2026-11-20-m1-overview.md`:** mark M1e done. M1f is next.
  - **`README.md`:** update the status line and add this plan to the table.

- [ ] **Step 3: Manual checks** (owner, in a new World Engine world; creative is easiest):
  1. **Look across a seam:**
     - Fly to E3's east edge (x = 40,960) and look east. The terrain carries on for 128 blocks, then ends in void until M4.
     - The band has no trees or ores, and neither has E3 within 144 blocks of the edge (the copied strip and one chunk more). Near face corners and the no-man's land squares, strips whose only copy would lie in no-man's land are decorated, so the bare zone is irregular there.
  2. **Walk across:** at 64 blocks into the band you are carried to E0 (x ≈ −24,512). The surroundings stay the same, apart from chunks loading in.
  3. **Build across the seam:** place blocks and stairs in the band, then fly to E0's west edge. They are there, and the stairs face the same way relative to the terrain. Do the same over North's west edge, where the copy is turned.
  4. **No-man's land:** at x ≈ −8,250, z ≈ −8,250 (near the corner of E0, E1 and North) there is a 128 × 128 hole. Walking into it puts you back.
  5. **Water, redstone and moving blocks near a seam:** pour water in a band, put a lever and a lamp in a band, push a block with a piston and drop sand there. Note what the copy does.
  6. **`/worldengine where`** in a band names the band and the face column it copies.
  7. **Containers and loading:** fill a chest in a band, then break the chest on its twin, and note whether the band chest's items spill. In a fresh band, build at depth 5 and watch for a server hitch while the twin chunk generates. Drop an item at the band's middle with no player near the twin, and note where it ends up.

- [ ] **Step 4: Commit and push.**

```bash
git add README.md docs
git commit -m "M1e-2: docs"
git push
```

---

## Known limits (by design, for later)

- **Bare bands:** no trees, ores or structures in a band, or within 144 blocks of a seam on the face side (the copied strip plus a ring of one chunk; see the post-execution note), so both copies match. Copying decoration across needs both chunks decorated first; that's a later improvement (M2 or M4).
- **Structures near seams are cut off.** A structure whose pieces reach into the undecorated zone is missing those pieces, and `/locate` can point at one. Keeping structure starts out of that zone is later work.
- **Put deep into a band:** something teleported (a command, a pearl, a respawn) to a band's middle or beyond crosses on the next tick, with its look turned as for a walker. Something put into a no-man's land square stays there, as it has no last position to go back to.
- **Block entity contents aren't copied.** A chest in a band and its twin are two chests, so items put in one aren't in the other. Signs, banners and containers near seams behave this way. Breaking one of them also replaces its twin with air, and the twin's own contents may spill there (a manual check, Task 7).
- **Entities aren't mirrored.** Item frames, armor stands, boats and animals exist on one side only, and animals generated in both copies are separate animals.
- **An edit near a seam can generate its twin's chunk on the server thread.** The copy needs the twin chunk loaded, so an edit whose twin chunk isn't loaded yet loads or generates it right away, which can make the server hitch. The pre-load only covers the landing point of a player walking out, not the twin of every column a player builds on.
- **Only players pre-load.** A mob or item that crosses a seam can land in a twin chunk that isn't loaded, and freeze there until a player comes near.
- **Worlds made before M1e-2 keep their old seams.** Chunks generated earlier keep void where the bands now are, and trees in the strips the bands copy. The world stamp only tracks the core's terrain version, so it can't warn about this; use a new world.
- **Grass tint can differ slightly across a seam near biome borders.** The stored biomes match quart for quart, but Minecraft picks each block's biome with a position-dependent jitter between neighbouring quarts and blends colours over neighbouring columns. Band and twin sit at different positions, and a band's outer edge blends with the void.
- **Chunk pop at the crossing:** the client still has to load the twin's chunks, which sit at other level coordinates. E2 measured about 60–260 ms of holes; the pre-load shortens it. M4's level-of-detail planet will hide it.
- **Edits in chunks that aren't block-ticking** aren't mirrored, because vanilla doesn't send those to clients either. Edits next to a player always are.
- **Simultaneous fluid or redstone activity on both sides** is mirrored change by change. Both sides follow the same rules, so they should agree, but this is a manual check (Task 7).
- **Beyond the band:** void until M4 draws the true neighbour.

## Self-review against the M1 goals and ADR 0001

| Requirement | Where |
|---|---|
| Overlap band, about 128 blocks wide, holding a synchronized, rotated copy of the neighbouring face (ROADMAP §4.3) | `SeamBands` (Task 2), band chunks (Task 3), mirroring (Task 5) |
| Edits in a band are mirrored to its twin, turned | `SeamMirror`; GameTests for placing, breaking, both directions and turned stairs (Task 5) |
| Teleport mid-band, about 64 blocks from either edge (ADR, M1e) | `SeamCrossings.plan`, the handler (Task 4) |
| Corners as no-man's land (ADR, M1e) | `SeamBands.at` is null in the 8 overlap squares; bounce (Task 4) |
| Terrain never visibly breaks at seams (M1 exit) | Bands copy the twin exactly (Task 3, `bandsCopyTheirTwin`) |
| Fabric GameTests for seam crossing and edit mirroring (M1 exit) | `SeamGameTests`, `SeamMirrorGameTests` |
| Pre-load the twin region, and get its chunks to the client before the crossing (ADR) | Server pre-load: yes. The client still loads the twin's chunks after the crossing (Known limits). |
