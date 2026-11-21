# M1d Universe Level Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Gate (owner's decision):** M1d is the last phase before the M1b spike. When this plan is done, **nothing after it (M1e, M1f, M2 …) starts until the [M1b spike](2026-11-20-m1b-handoff-spike.md) has run and ADR 0001 is accepted.**

**Goal:** A "World Engine" world type whose overworld is the universe level. It contains the home planet and the moon, each laid out as a cross-shaped net of six cube faces, with terrain from M1c's native core and empty space everywhere else. Vanilla decorates the planets through their biomes (trees, grass, ores, mobs). The world records which terrain version generated it.

**Architecture:**
- **Rust:** `we-ffi` exports `we_terrain_version()` (ABI v3), so the mod can stamp worlds with the terrain model's version.
- **Pure Java** (`worldengine.planet`, no Minecraft classes, unit-tested on any JDK):
  - `PlanetLayout` is a Java port of `we_frames::PlanetLayout`: pure translation, face origins aligned to 16, so every chunk lies on exactly one face or on none.
  - `PlanetSpec`/`Planets` are the single source for each planet's name, cells, kind, layout and seed derivation. The home planet's face E1 is centred on the level origin; the moon sits 59 km east.
  - `LevelShape` sets the level's vertical layout: y −384 to 895, sea level 63.
  - `ColumnMaterials` turns a height and biome into blocks.
  - `BiomeTable` maps the 13 planet biomes to Minecraft biomes.
  - `SpawnSearch` finds dry land near a planet's E1 centre.
  - `TerrainStamp` decides when a world's stamp warrants a warning.
- **Minecraft** (`worldengine.level`):
  - `UniverseChunkGenerator` makes one batched native call per chunk for biomes and one for blocks.
  - `UniverseBiomeSource` serves biome queries outside chunk generation (structures, `/locate`).
  - `WorldStamp` is saved data holding the terrain and ABI versions.
  - A data pack: the `worldengine:universe` world preset (listed with vanilla's presets), its dimension type, and two biomes (`void`, `barren`).
- **Commands:** `/worldengine where`, `goto <planet>`, `terrain` and `bodies`, for game masters only.
- **Tests:** Fabric GameTests run inside a real server. They take the World Engine preset's generator from the server's registry, generate chunks with it and compare them block by block with the core. CI runs them as part of `./gradlew build`.

**Tech Stack:** Rust stable (edition 2024) · Java 25 FFM · Minecraft 26.3 + Fabric API 0.166.2 (GameTest API v1) · Loom 1.19.4 `fabricApi.configureTests` · Brigadier · DataFixerUpper codecs

**What has been checked before execution (2026-11-20):** every file in this plan was built and run from a scratch worktree of `ecad8c7`.
- ✅ **Rust:** `cargo test --workspace --locked` passes (`we-ffi` 18 unit + 2 integration, `we-frames` 44, `we-planet` 23, `we-orbit` 20). clippy (`-D warnings`) and fmt are clean.
- ✅ **Pure Java:** the 27 `worldengine.planet` JUnit tests pass on JDK 21 (they use no Minecraft classes).
- ✅ **Minecraft side, in CI** ([mc-api run #5](https://github.com/Chabelabela/world-engine/actions/runs/44837133617); that temporary workflow was removed afterwards): the whole mod compiled against Minecraft 26.3, `./gradlew build` passed (all unit tests, including the native ones), and the 7 GameTests passed in a headless server. Generating biomes and terrain took 9.2 ms per chunk on the CI machine.
- ✅ **The plan reproduces that code:** replaying this plan's steps onto a clean `ecad8c7` gives the same 41 files, byte for byte, as the tree CI built and tested.
- ✅ **API recon:** every Minecraft signature used below was read from the 26.3 jar with `javap`. The surprises are listed under "26.3 API notes" at the end.
- ⚠️ **Not checked:**
  - The World Engine world type on the client's Create World screen, how the terrain looks, and generation speed in play. Task 11 lists the manual checks.
  - The in-between states of `WorldEngineMod.java` after Tasks 7 and 8 were not compiled on their own; Task 9 replaces the whole file with the CI-built version.

**Post-execution note (2026-11-21):** executed with Haiku implementers and Sonnet reviewers. Every task's files matched the CI-verified reference byte for byte; CI is green on all jobs, and the GameTests ran in the `mod` job (4.3 ms per chunk). One review follow-up commit (`b2fd564`) then changed a few lines, so **the repository, not these code blocks, is authoritative**:
- `ColumnMaterials`: a top block counts as under water only on planets with oceans.
- `PlanetTerrain.landChunk`: native failures are rethrown with the planet's name.
- `checkWorldStamp`: a failing stamp check is logged instead of thrown, so a corrupt stamp file can't stop server start. The crash-on-failure policy still holds for world generation.
- `/worldengine goto`: lands on `MOTION_BLOCKING_NO_LEAVES`, not on treetops.
- `/worldengine where`: says "layout only" outside universe worlds.
- Tests: face ids pinned to the ABI, invariants checked at the height extremes, a moon spawn-search test, tighter level margins, the seed included in GameTest failure messages, the speed limit raised to 50 ms per chunk, and the temporary stamp directory cleaned up.

**Environment note:** without JDK 25 (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 11). Don't download JDKs. Rust steps always run locally. The pure-Java tests (Tasks 2–6) can also be run on any JDK ≥ 21 with the JUnit console launcher, as was done here.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `core/crates/we-ffi/src/terrain.rs` | Modify | `we_terrain_version()` |
| `core/crates/we-ffi/src/lib.rs` | Modify | ABI v3, re-export |
| `core/crates/we-ffi/tests/abi.rs` | Modify | Call sequence includes the terrain version |
| `mod/src/main/java/…/nativebridge/NativeCore.java` | Modify | `terrainVersion()`, expects ABI v3 |
| `mod/src/test/java/…/nativebridge/NativeCoreTest.java` | Modify | Terrain version test |
| `mod/src/test/java/…/nativebridge/NativeTerrainTest.java` | Modify | Uses `Planets.HOME.cells()` |
| `mod/src/main/java/…/planet/PlanetFace.java` | Create | Face numbering (ABI) |
| `mod/src/main/java/…/planet/FaceCell.java` | Create | A face cell |
| `mod/src/main/java/…/planet/PlanetLayout.java` | Create | The cross net in level coordinates |
| `mod/src/main/java/…/planet/PlanetSpec.java` | Create | One planet's definition and seed |
| `mod/src/main/java/…/planet/PlanetHit.java` | Create | Planet + cell under a column |
| `mod/src/main/java/…/planet/Planets.java` | Create | Home and moon; lookup by column |
| `mod/src/main/java/…/planet/LevelShape.java` | Create | min y, height, sea level |
| `mod/src/main/java/…/planet/Ground.java` | Create | Block kinds before block states |
| `mod/src/main/java/…/planet/ColumnMaterials.java` | Create | Column → blocks |
| `mod/src/main/java/…/planet/BiomeTable.java` | Create | Planet biome → Minecraft biome |
| `mod/src/main/java/…/planet/SpawnSearch.java` | Create | Dry land near a planet's centre |
| `mod/src/main/java/…/planet/TerrainStamp.java` | Create | Version stamp and its warning |
| `mod/src/main/java/…/level/GroundStates.java` | Create | `Ground` → block state |
| `mod/src/main/java/…/level/PlanetTerrain.java` | Create | Native samples + failure policy |
| `mod/src/main/java/…/level/UniverseBiomeSource.java` | Create | Biomes outside chunk generation |
| `mod/src/main/java/…/level/UniverseChunkGenerator.java` | Create | The universe level's generator |
| `mod/src/main/java/…/level/WorldStamp.java` | Create | Saved terrain/ABI versions |
| `mod/src/main/java/…/WorldEngineCommands.java` | Create | `/worldengine …` (game masters) |
| `mod/src/main/java/…/WorldEngineMod.java` | Modify | Registration, stamp check, accessors |
| `mod/src/main/resources/data/worldengine/dimension_type/universe.json` | Create | Level height |
| `mod/src/main/resources/data/worldengine/worldgen/world_preset/universe.json` | Create | The world type |
| `mod/src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json` | Create | Lists it on Create World |
| `mod/src/main/resources/data/worldengine/worldgen/biome/{void,barren}.json` | Create | Space and airless rock |
| `mod/src/main/resources/assets/worldengine/lang/en_us.json` | Create | Names |
| `mod/src/test/java/…/planet/*Test.java` | Create | Unit tests for the pure classes |
| `mod/build.gradle` | Modify | GameTest source set; `runGameTest` builds the core |
| `mod/src/gametest/…` | Create | GameTests, their mod JSON and test dimension |

(`…` is `io/github/chabelabela/worldengine`.)

---

### Task 1: The terrain version crosses the ABI (v3)

The world stamp (Task 8) needs the terrain model's version from the core, not a copy in Java.

**Files:**
- Modify: `core/crates/we-ffi/src/terrain.rs`, `core/crates/we-ffi/src/lib.rs`, `core/crates/we-ffi/tests/abi.rs`
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java`, `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeCoreTest.java`

- [ ] **Step 1: Write the failing tests.** In `core/crates/we-ffi/tests/abi.rs`, in `java_call_sequence`, add the second line below after the first:

```rust
    assert_eq!(we_abi_version(), WE_ABI_VERSION);
    assert_eq!(we_terrain_version(), we_planet::TERRAIN_VERSION);
```

In `core/crates/we-ffi/src/lib.rs`'s unit tests, change `reports_abi_version` to expect 3:

```rust
    fn reports_abi_version() {
        assert_eq!(we_abi_version(), 3);
    }
```

- [ ] **Step 2: Run them to see them fail.**

Run: `cd core && cargo test --locked -p we-ffi`
Expected: compile error, `cannot find function we_terrain_version`.

- [ ] **Step 3: Implement.** In `core/crates/we-ffi/src/terrain.rs`, insert before the doc comment of `we_terrain_columns`:

```rust
/// The terrain model's version, `we_planet::TERRAIN_VERSION`. Worlds store it
/// so that a later change to the terrain model is detected instead of
/// silently mixing old and new chunks.
#[unsafe(no_mangle)]
pub extern "C" fn we_terrain_version() -> u32 {
    we_planet::TERRAIN_VERSION
}
```

In `core/crates/we-ffi/src/lib.rs`, replace `pub use terrain::we_terrain_columns;` and the version constant:

```rust
pub use terrain::{we_terrain_columns, we_terrain_version};
```

```rust
pub const WE_ABI_VERSION: u32 = 3;
```

- [ ] **Step 4: Run the Rust checks.**

Run: `cd core && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked -- -D warnings && cargo fmt --all --check`
Expected: all pass (`we-ffi`: 18 unit, 2 integration).

- [ ] **Step 5: Java side.** In `NativeCore.java`:
  - Set `EXPECTED_ABI_VERSION = 3`.
  - Add the field `private final MethodHandle terrainVersionFn;` after `terrainColumnsFn`.
  - At the end of the constructor, add:

```java
        terrainVersionFn = linker.downcallHandle(find(lib, "we_terrain_version"), FunctionDescriptor.of(JAVA_INT));
```

  - Add this method before `terrainColumns`'s Javadoc:

```java
    /** The terrain model's version ({@code we_planet::TERRAIN_VERSION}); worlds record it. */
    public int terrainVersion() {
        try {
            return (int) terrainVersionFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }
```

In `NativeCoreTest.java`, add after `reportsAbiVersion`:

```java
    @Test
    void reportsTerrainVersion() {
        assertEquals(1, core.terrainVersion());
    }
```

- [ ] **Step 6: Run the Java tests** (JDK 25 only; otherwise CI covers it in Task 11).

Run: `cd mod && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit.**

```bash
git add core/crates/we-ffi mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeCoreTest.java
git commit -m "M1d: we_terrain_version (ABI v3)"
```

---

### Task 2: The planet net in Java (`PlanetFace`, `FaceCell`, `PlanetLayout`)

A port of `we_frames::PlanetLayout`: E0–E3 in a row, North above E1, South below it, each face a pure translation of its cell grid. The tests reuse the Rust tests' layout and cases, so both implementations are held to the same answers. Origins and `cells` must be multiples of 16, so a chunk never straddles two faces.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/PlanetFace.java`, `FaceCell.java`, `PlanetLayout.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/PlanetLayoutTest.java`

- [ ] **Step 1: Write the failing test.**

```java
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
```

- [ ] **Step 2: Run it to see it fail.**

Run: `cd mod && ./gradlew test --tests '*PlanetLayoutTest'`
Expected: compilation fails (`PlanetLayout` doesn't exist).

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

/**
 * A cube face, numbered exactly as {@code we_frames::Face}. The numbers are part of the native ABI
 * ({@code we_terrain_columns} takes them).
 */
public enum PlanetFace {
    E0,
    E1,
    E2,
    E3,
    NORTH,
    SOUTH;

    private static final PlanetFace[] BY_ID = values();

    public int id() {
        return ordinal();
    }

    public static PlanetFace fromId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            throw new IllegalArgumentException("No face " + id);
        }
        return BY_ID[id];
    }
}
```

```java
package io.github.chabelabela.worldengine.planet;

import java.util.Objects;

/** Cell ({@code x}, {@code z}) of a face's block grid: x grows east, z grows south. */
public record FaceCell(PlanetFace face, int x, int z) {
    public FaceCell {
        Objects.requireNonNull(face, "face");
    }
}
```

```java
package io.github.chabelabela.worldengine.planet;

/**
 * Where a planet's six faces sit in the level: the cross-shaped net of {@code we_frames::PlanetLayout}.
 *
 * <p>E0, E1, E2 and E3 lie in a row starting at ({@code originX}, {@code originZ}), with North above E1
 * and South below it. Each face is {@code cells} by {@code cells} blocks, and block
 * (faceOriginX + x, faceOriginZ + z) is cell (x, z) of that face: a pure translation, no rotation.
 *
 * <p>Origins and {@code cells} are multiples of 16, so every chunk lies on exactly one face or on none.
 */
public record PlanetLayout(int originX, int originZ, int cells) {
    /** Blocks from the level's centre to the vanilla world border's edge. The whole net stays inside. */
    public static final int WORLD_LIMIT = 29_999_984;

    public PlanetLayout {
        if (cells <= 0 || cells % 16 != 0) {
            throw new IllegalArgumentException("cells must be a positive multiple of 16, got " + cells);
        }
        if (originX % 16 != 0 || originZ % 16 != 0) {
            throw new IllegalArgumentException("origin must be chunk-aligned, got (" + originX + ", " + originZ + ")");
        }
        if ((long) originX < -WORLD_LIMIT || (long) originX + 4L * cells > WORLD_LIMIT
                || (long) originZ - cells < -WORLD_LIMIT || (long) originZ + 2L * cells > WORLD_LIMIT) {
            throw new IllegalArgumentException("net does not fit inside the world border");
        }
    }

    /** The cell under block column ({@code blockX}, {@code blockZ}), or null if it isn't on a face. */
    public FaceCell cellAt(int blockX, int blockZ) {
        long rx = (long) blockX - originX;
        long rz = (long) blockZ - originZ;
        long column = Math.floorDiv(rx, cells);
        long row = Math.floorDiv(rz, cells);
        PlanetFace face = faceAt(row, column);
        if (face == null) {
            return null;
        }
        return new FaceCell(face, (int) (rx - column * cells), (int) (rz - row * cells));
    }

    /** Block x of cell x = 0 on {@code face}. */
    public int faceOriginX(PlanetFace face) {
        return originX + column(face) * cells;
    }

    /** Block z of cell z = 0 on {@code face}. */
    public int faceOriginZ(PlanetFace face) {
        return originZ + row(face) * cells;
    }

    private static PlanetFace faceAt(long row, long column) {
        if (row == 0 && column >= 0 && column <= 3) {
            return PlanetFace.fromId((int) column);
        }
        if (column == 1 && row == -1) {
            return PlanetFace.NORTH;
        }
        if (column == 1 && row == 1) {
            return PlanetFace.SOUTH;
        }
        return null;
    }

    private static int column(PlanetFace face) {
        return switch (face) {
            case E0 -> 0;
            case E1, NORTH, SOUTH -> 1;
            case E2 -> 2;
            case E3 -> 3;
        };
    }

    private static int row(PlanetFace face) {
        return switch (face) {
            case NORTH -> -1;
            case SOUTH -> 1;
            default -> 0;
        };
    }
}
```

- [ ] **Step 4: Run the test.**

Run: `cd mod && ./gradlew test --tests '*PlanetLayoutTest'`
Expected: 5 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/test/java/io/github/chabelabela/worldengine/planet
git commit -m "M1d: planet net layout in Java"
```

---

### Task 3: Planets (`PlanetSpec`, `PlanetHit`, `Planets`)

One definition per planet replaces the `HOME_CELLS` constants scattered across Java. Seeds derive from the world seed through SplitMix64's finalizer with a per-planet salt; the test pins two values because changing the derivation changes every world.

| Planet | Cells | Kind | Layout origin | Net spans x | Net spans z | E1 centre |
|---|---|---|---|---|---|---|
| home | 16,384 | terrestrial | (−24,576, −8,192) | −24,576 … 40,959 | −24,576 … 24,575 | (0, 0) |
| moon | 4,096 | barren | (100,000, −2,048) | 100,000 … 116,383 | −6,144 … 6,143 | (106,144, 0) |

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/PlanetSpec.java`, `PlanetHit.java`, `Planets.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/PlanetsTest.java`
- Modify: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeTerrainTest.java`

- [ ] **Step 1: Write the failing test.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import org.junit.jupiter.api.Test;

class PlanetsTest {
    // Part of the world format: changing these changes every generated world.
    private static final long HOME_SEED_12345 = -5_771_623_559_949_941_913L;
    private static final long MOON_SEED_12345 = -5_841_888_737_799_734_202L;

    @Test
    void homeFaceE1IsCentredOnTheLevelOrigin() {
        assertEquals(0, Planets.HOME.centreX());
        assertEquals(0, Planets.HOME.centreZ());
        PlanetHit hit = Planets.at(0, 0);
        assertSame(Planets.HOME, hit.planet());
        assertEquals(new FaceCell(PlanetFace.E1, 8_192, 8_192), hit.cell());
    }

    @Test
    void planetsMatchTheRoadmap() {
        assertEquals(16_384, Planets.HOME.cells());
        assertEquals(PlanetKind.TERRESTRIAL, Planets.HOME.kind());
        assertEquals(4_096, Planets.MOON.cells());
        assertEquals(PlanetKind.BARREN, Planets.MOON.kind());
        assertSame(Planets.MOON, Planets.byName("moon"));
        assertNull(Planets.byName("pluto"));
    }

    @Test
    void netsAreFarApart() {
        // Bounding boxes of the nets, grown by 1,024 blocks, must not touch.
        for (PlanetSpec a : Planets.ALL) {
            for (PlanetSpec b : Planets.ALL) {
                if (a != b) {
                    assertTrue(minX(a) - 1_024 >= maxX(b) || minX(b) - 1_024 >= maxX(a)
                            || minZ(a) - 1_024 >= maxZ(b) || minZ(b) - 1_024 >= maxZ(a), a.name() + " / " + b.name());
                }
            }
        }
        PlanetHit moon = Planets.at(Planets.MOON.centreX(), Planets.MOON.centreZ());
        assertSame(Planets.MOON, moon.planet());
        assertNull(Planets.at(70_000, 0));
    }

    @Test
    void planetSeedsAreDistinctAndPinned() {
        assertNotEquals(Planets.HOME.seed(42L), Planets.MOON.seed(42L));
        assertNotEquals(Planets.HOME.seed(42L), Planets.HOME.seed(43L));
        assertEquals(HOME_SEED_12345, Planets.HOME.seed(12_345L));
        assertEquals(MOON_SEED_12345, Planets.MOON.seed(12_345L));
    }

    private static long minX(PlanetSpec p) {
        return p.layout().originX();
    }

    private static long maxX(PlanetSpec p) {
        return p.layout().originX() + 4L * p.cells();
    }

    private static long minZ(PlanetSpec p) {
        return p.layout().originZ() - (long) p.cells();
    }

    private static long maxZ(PlanetSpec p) {
        return p.layout().originZ() + 2L * p.cells();
    }
}
```

- [ ] **Step 2: Run it to see it fail.**

Run: `cd mod && ./gradlew test --tests '*PlanetsTest'`
Expected: compilation fails (`Planets` doesn't exist).

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import java.util.Objects;

/**
 * Everything world generation needs to know about one planet: its name (as in {@code we-orbit}), how its
 * terrain seed derives from the world seed, its kind, and where its faces sit in the level.
 */
public record PlanetSpec(String name, long seedSalt, PlanetKind kind, PlanetLayout layout) {
    public PlanetSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(layout, "layout");
    }

    /** Blocks per face edge. */
    public int cells() {
        return layout.cells();
    }

    /** This planet's terrain seed in a world with seed {@code worldSeed}. */
    public long seed(long worldSeed) {
        return mix64(worldSeed ^ seedSalt);
    }

    /** Block x of the centre of face E1, where searches for a spawn point start. */
    public int centreX() {
        return layout.faceOriginX(PlanetFace.E1) + cells() / 2;
    }

    /** Block z of the centre of face E1. */
    public int centreZ() {
        return layout.faceOriginZ(PlanetFace.E1) + cells() / 2;
    }

    /** SplitMix64's finalizer: a bijection, so distinct salts give distinct seeds. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
```

```java
package io.github.chabelabela.worldengine.planet;

/** A block column that lies on a planet: which planet, and which cell of which face. */
public record PlanetHit(PlanetSpec planet, FaceCell cell) {
}
```

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import java.util.List;

/**
 * The planets in the universe level. Their nets are far apart, so a column belongs to at most one planet.
 * The home planet's face E1 is centred on the level's origin.
 */
public final class Planets {
    /** ROADMAP §4.4: 16,384 blocks per face edge, radius 10,430 m. */
    public static final PlanetSpec HOME = new PlanetSpec(
            "home", 0x9E3779B97F4A7C15L, PlanetKind.TERRESTRIAL, new PlanetLayout(-24_576, -8_192, 16_384));
    /** 4,096 blocks per face edge, radius 2,608 m. */
    public static final PlanetSpec MOON = new PlanetSpec(
            "moon", 0xD1B54A32D192ED03L, PlanetKind.BARREN, new PlanetLayout(100_000, -2_048, 4_096));
    public static final List<PlanetSpec> ALL = List.of(HOME, MOON);

    private Planets() {
    }

    /** The planet and cell under block column ({@code blockX}, {@code blockZ}), or null for empty space. */
    public static PlanetHit at(int blockX, int blockZ) {
        for (PlanetSpec planet : ALL) {
            FaceCell cell = planet.layout().cellAt(blockX, blockZ);
            if (cell != null) {
                return new PlanetHit(planet, cell);
            }
        }
        return null;
    }

    /** The planet called {@code name}, or null. */
    public static PlanetSpec byName(String name) {
        for (PlanetSpec planet : ALL) {
            if (planet.name().equals(name)) {
                return planet;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: One source for the home planet's size.** In `NativeTerrainTest.java`, replace `private static final int HOME_CELLS = 16_384;` with:

```java
    private static final int HOME_CELLS = Planets.HOME.cells();
```

and add `import io.github.chabelabela.worldengine.planet.Planets;` before `import java.nio.file.Path;`.

- [ ] **Step 5: Run the tests.**

Run: `cd mod && ./gradlew test --tests '*PlanetsTest' --tests '*NativeTerrainTest'`
Expected: all pass.

- [ ] **Step 6: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/test/java/io/github/chabelabela/worldengine
git commit -m "M1d: planet definitions (home, moon) and seed derivation"
```

---

### Task 4: The level's shape and what a column is made of

The universe level runs from y = −384 to y = 895 (80 sections). Terrain tops run from y = −338 to y = 762, so there are 46 blocks of rock under the deepest sea floor and 133 blocks of air over the highest peak. Sea level is 63, vanilla's convention (water fills every y below it). A column `h` metres above the datum has its top block at `62 + h`, so `h = 0` is a beach level with the water and `h < 0` is under water.

Each column: bedrock at y = −384, stone, 3 blocks of biome subsoil, a biome top block, then water up to y = 62 on terrestrial planets. Grass under water becomes dirt. The moon has no water.

The dimension type is vanilla's `overworld.json` from the 26.3 jar with only `min_y`, `height` and `logical_height` changed. A test keeps it and `LevelShape` in step.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/LevelShape.java`, `Ground.java`, `ColumnMaterials.java`
- Create: `mod/src/main/resources/data/worldengine/dimension_type/universe.json`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/LevelShapeTest.java`, `ColumnMaterialsTest.java`

- [ ] **Step 1: Write the failing tests.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LevelShapeTest {
    @Test
    void coversTheTerrainRangeWithRoomToSpare() {
        assertTrue(LevelShape.surfaceY(TerrainColumns.MIN_HEIGHT) - LevelShape.MIN_Y >= 32, "rock under the deepest sea");
        assertTrue(LevelShape.maxY() - LevelShape.surfaceY(TerrainColumns.MAX_HEIGHT) >= 100, "air over the highest peak");
        assertEquals(0, LevelShape.MIN_Y % 16);
        assertEquals(0, LevelShape.HEIGHT % 16);
        assertTrue(LevelShape.MIN_Y + LevelShape.HEIGHT <= 2_032, "vanilla's dimension height limit");
        assertEquals(62, LevelShape.surfaceY(0));
    }

    @Test
    void matchesTheDimensionType() throws IOException {
        String json = resource("/data/worldengine/dimension_type/universe.json");
        assertEquals(LevelShape.MIN_Y, intField(json, "min_y"));
        assertEquals(LevelShape.HEIGHT, intField(json, "height"));
        assertEquals(LevelShape.HEIGHT, intField(json, "logical_height"));
    }

    static String resource(String path) throws IOException {
        try (InputStream in = LevelShapeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int intField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue(m.find(), "no field " + name);
        return Integer.parseInt(m.group(1));
    }
}
```

```java
package io.github.chabelabela.worldengine.planet;

import static io.github.chabelabela.worldengine.nativebridge.PlanetKind.BARREN;
import static io.github.chabelabela.worldengine.nativebridge.PlanetKind.TERRESTRIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import org.junit.jupiter.api.Test;

class ColumnMaterialsTest {
    @Test
    void landColumn() {
        // 10 m above the datum: top block at y = 72.
        assertEquals(Ground.BEDROCK, ColumnMaterials.at(LevelShape.MIN_Y, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.STONE, ColumnMaterials.at(68, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        for (int y = 69; y <= 71; y++) {
            assertEquals(Ground.DIRT, ColumnMaterials.at(y, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        }
        assertEquals(Ground.GRASS, ColumnMaterials.at(72, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(73, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(72, ColumnMaterials.topY(10, TERRESTRIAL));
    }

    @Test
    void oceanColumnFillsWithWaterToSeaLevel() {
        // 20 m below the datum: sea floor at y = 42, water from 43 to 62.
        assertEquals(Ground.SAND, ColumnMaterials.at(42, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(Ground.WATER, ColumnMaterials.at(43, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(Ground.WATER, ColumnMaterials.at(62, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(63, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(62, ColumnMaterials.topY(-20, TERRESTRIAL));
        assertEquals(Ground.GRAVEL, ColumnMaterials.at(-200, -262, PlanetBiome.DEEP_OCEAN, TERRESTRIAL));
    }

    @Test
    void grassUnderWaterBecomesDirt() {
        assertEquals(Ground.DIRT, ColumnMaterials.at(61, -1, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.WATER, ColumnMaterials.at(62, -1, PlanetBiome.PLAINS, TERRESTRIAL));
    }

    @Test
    void beachAtTheDatumIsDry() {
        assertEquals(Ground.SAND, ColumnMaterials.at(62, 0, PlanetBiome.BEACH, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(63, 0, PlanetBiome.BEACH, TERRESTRIAL));
        assertEquals(62, ColumnMaterials.topY(0, TERRESTRIAL));
    }

    @Test
    void barrenPlanetsHaveNoWater() {
        assertEquals(Ground.GRAVEL, ColumnMaterials.at(42, -20, PlanetBiome.BARREN, BARREN));
        assertEquals(Ground.STONE, ColumnMaterials.at(41, -20, PlanetBiome.BARREN, BARREN));
        assertEquals(Ground.AIR, ColumnMaterials.at(50, -20, PlanetBiome.BARREN, BARREN));
        assertEquals(42, ColumnMaterials.topY(-20, BARREN));
    }

    @Test
    void outsideTheLevelIsAir() {
        assertEquals(Ground.AIR, ColumnMaterials.at(LevelShape.MIN_Y - 1, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(LevelShape.maxY() + 1, 10, PlanetBiome.PLAINS, TERRESTRIAL));
    }

    @Test
    void everyBiomeHasASolidTopAndSubsoil() {
        for (PlanetBiome biome : PlanetBiome.values()) {
            for (int y = 47; y <= 50; y++) {
                Ground g = ColumnMaterials.at(y, -12, biome, TERRESTRIAL);
                assertNotEquals(Ground.AIR, g, biome + " at " + y);
                assertNotEquals(Ground.WATER, g, biome + " at " + y);
            }
        }
    }
}
```

- [ ] **Step 2: Run them to see them fail.**

Run: `cd mod && ./gradlew test --tests '*LevelShapeTest' --tests '*ColumnMaterialsTest'`
Expected: compilation fails.

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

/**
 * The universe level's vertical layout. Must match {@code data/worldengine/dimension_type/universe.json}
 * (a unit test checks).
 *
 * <p>Terrain heights are metres above the datum (sea level) and lie within
 * {@code TerrainColumns.MIN_HEIGHT..MAX_HEIGHT} (-400..700). A column of height {@code h} has its top solid
 * block at {@code SEA_LEVEL - 1 + h}, so {@code h = 0} is a beach level with the water surface and
 * {@code h < 0} is under water. This gives top blocks from y = -338 to y = 762.
 */
public final class LevelShape {
    /** Lowest block y: 46 blocks of rock under the deepest possible sea floor. Bedrock sits here. */
    public static final int MIN_Y = -384;
    /** Blocks from MIN_Y to the build limit: the top is y = 895, 133 blocks above the highest peak. */
    public static final int HEIGHT = 1280;
    /** Vanilla's convention: water fills every y below this on terrestrial planets. */
    public static final int SEA_LEVEL = 63;

    private LevelShape() {
    }

    /** Highest buildable y. */
    public static int maxY() {
        return MIN_Y + HEIGHT - 1;
    }

    /** The top solid block's y for a column {@code height} metres above the datum. */
    public static int surfaceY(int height) {
        return SEA_LEVEL - 1 + height;
    }
}
```

```java
package io.github.chabelabela.worldengine.planet;

/** What a generated block is, before it becomes a Minecraft block state. */
public enum Ground {
    AIR,
    WATER,
    BEDROCK,
    STONE,
    DIRT,
    GRASS,
    SAND,
    SANDSTONE,
    GRAVEL,
    SNOW
}
```

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;

/**
 * Turns one terrain column (height and biome from the core) into blocks: bedrock at the bottom, stone,
 * three blocks of biome subsoil, a biome top block, then water up to sea level on terrestrial planets.
 */
public final class ColumnMaterials {
    /** Blocks of subsoil (dirt, sand, ...) between the top block and stone. */
    public static final int SUBSOIL_DEPTH = 3;

    private ColumnMaterials() {
    }

    /** The highest non-air block's y: the surface, or the water surface if that is higher. */
    public static int topY(int height, PlanetKind kind) {
        int surface = LevelShape.surfaceY(height);
        return hasOceans(kind) ? Math.max(surface, LevelShape.SEA_LEVEL - 1) : surface;
    }

    /** The block at {@code y} in a column {@code height} metres above the datum. */
    public static Ground at(int y, int height, PlanetBiome biome, PlanetKind kind) {
        if (y < LevelShape.MIN_Y || y > LevelShape.maxY()) {
            return Ground.AIR;
        }
        if (y == LevelShape.MIN_Y) {
            return Ground.BEDROCK;
        }
        int surface = LevelShape.surfaceY(height);
        if (y > surface) {
            return hasOceans(kind) && y < LevelShape.SEA_LEVEL ? Ground.WATER : Ground.AIR;
        }
        int depth = surface - y;
        if (depth == 0) {
            return top(biome, height < 0);
        }
        if (depth <= SUBSOIL_DEPTH) {
            return subsoil(biome);
        }
        return Ground.STONE;
    }

    private static boolean hasOceans(PlanetKind kind) {
        return kind == PlanetKind.TERRESTRIAL;
    }

    private static Ground top(PlanetBiome biome, boolean underWater) {
        return switch (biome) {
            case DEEP_OCEAN, BARREN -> Ground.GRAVEL;
            case OCEAN, BEACH, DESERT -> Ground.SAND;
            case MOUNTAINS -> Ground.STONE;
            case SNOWY_PEAKS -> Ground.SNOW;
            case PLAINS, FOREST, TAIGA, TUNDRA, SAVANNA, JUNGLE -> underWater ? Ground.DIRT : Ground.GRASS;
        };
    }

    private static Ground subsoil(PlanetBiome biome) {
        return switch (biome) {
            case DEEP_OCEAN -> Ground.GRAVEL;
            case OCEAN, BEACH -> Ground.SAND;
            case DESERT -> Ground.SANDSTONE;
            case MOUNTAINS, SNOWY_PEAKS, BARREN -> Ground.STONE;
            case PLAINS, FOREST, TAIGA, TUNDRA, SAVANNA, JUNGLE -> Ground.DIRT;
        };
    }
}
```

`mod/src/main/resources/data/worldengine/dimension_type/universe.json`:

```json
{
  "ambient_light": 0.0,
  "attributes": {
    "minecraft:audio/ambient_sounds": {
      "mood": {
        "block_search_extent": 8,
        "offset": 2.0,
        "sound": "minecraft:ambient.cave",
        "tick_delay": 6000
      }
    },
    "minecraft:audio/background_music": {
      "creative": {
        "max_delay": 24000,
        "min_delay": 12000,
        "sound": "minecraft:music.creative"
      },
      "default": {
        "max_delay": 24000,
        "min_delay": 12000,
        "sound": "minecraft:music.game"
      }
    },
    "minecraft:gameplay/bed_rule": {
      "can_set_spawn": "always",
      "can_sleep": "when_dark",
      "error_message": {
        "translate": "block.minecraft.bed.no_sleep"
      }
    },
    "minecraft:gameplay/nether_portal_spawns_piglin": true,
    "minecraft:gameplay/respawn_anchor_works": false,
    "minecraft:gameplay/straw_bed_rule": {
      "can_set_spawn": "never",
      "can_sleep": "when_dark",
      "destroy_on_leave": true,
      "error_message": {
        "translate": "block.minecraft.bed.no_sleep"
      }
    },
    "minecraft:visual/ambient_light_color": "#0a0a0a",
    "minecraft:visual/cloud_color": "#ccffffff",
    "minecraft:visual/cloud_height": 192.33,
    "minecraft:visual/fog_color": "#c0d8ff",
    "minecraft:visual/sky_color": "#78a7ff"
  },
  "coordinate_scale": 1.0,
  "default_clock": "minecraft:overworld",
  "has_ceiling": false,
  "has_ender_dragon_fight": false,
  "has_skylight": true,
  "height": 1280,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "logical_height": 1280,
  "min_y": -384,
  "monster_spawn_block_light_limit": 0,
  "monster_spawn_light_level": {
    "type": "minecraft:uniform",
    "max_inclusive": 7,
    "min_inclusive": 0
  },
  "timelines": "#minecraft:in_overworld"
}
```

- [ ] **Step 4: Run the tests.**

Run: `cd mod && ./gradlew test --tests '*LevelShapeTest' --tests '*ColumnMaterialsTest'`
Expected: 9 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/main/resources/data mod/src/test/java/io/github/chabelabela/worldengine/planet
git commit -m "M1d: level shape and column materials"
```

---

### Task 5: Biomes (`BiomeTable`, `worldengine:void`, `worldengine:barren`)

Vanilla biomes bring their trees, grass, ores and mobs. Two new biomes have no features and no mob spawns: `void` for the space around and between the nets, and `barren` for the moon. They follow 26.3's biome format (mob spawns and sky colour are `attributes`), modelled on vanilla's `the_void.json` without its spawn platform.

| Planet biome | Minecraft biome | Top / subsoil |
|---|---|---|
| DEEP_OCEAN | `minecraft:deep_ocean` | gravel / gravel |
| OCEAN | `minecraft:ocean` | sand / sand |
| BEACH | `minecraft:beach` | sand / sand |
| PLAINS | `minecraft:plains` | grass / dirt |
| FOREST | `minecraft:forest` | grass / dirt |
| TAIGA | `minecraft:taiga` | grass / dirt |
| TUNDRA | `minecraft:snowy_plains` | grass / dirt |
| DESERT | `minecraft:desert` | sand / sandstone |
| SAVANNA | `minecraft:savanna` | grass / dirt |
| JUNGLE | `minecraft:jungle` | grass / dirt |
| MOUNTAINS | `minecraft:stony_peaks` | stone / stone |
| SNOWY_PEAKS | `minecraft:frozen_peaks` | snow / stone |
| BARREN | `worldengine:barren` | gravel / stone |

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/BiomeTable.java`
- Create: `mod/src/main/resources/data/worldengine/worldgen/biome/void.json`, `barren.json`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/BiomeTableTest.java`

- [ ] **Step 1: Write the failing test.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiomeTableTest {
    @Test
    void everyPlanetBiomeHasANamespacedBiome() {
        for (PlanetBiome biome : PlanetBiome.values()) {
            String id = BiomeTable.biomeId(biome);
            assertTrue(id.matches("(minecraft|worldengine):[a-z_]+"), biome + " -> " + id);
        }
    }

    @Test
    void worldEngineBiomesShipWithTheMod() throws IOException {
        List<String> ids = new ArrayList<>();
        ids.add(BiomeTable.VOID);
        for (PlanetBiome biome : PlanetBiome.values()) {
            ids.add(BiomeTable.biomeId(biome));
        }
        for (String id : ids) {
            if (id.startsWith("worldengine:")) {
                String path = "/data/worldengine/worldgen/biome/" + id.substring("worldengine:".length()) + ".json";
                assertTrue(LevelShapeTest.resource(path).contains("\"features\""), path);
            }
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail.**

Run: `cd mod && ./gradlew test --tests '*BiomeTableTest'`
Expected: compilation fails.

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;

/**
 * Which Minecraft biome stands in for each planet biome. Vanilla biomes bring their trees, grass, ores and
 * mobs; World Engine adds {@code worldengine:barren} (airless rock) and {@code worldengine:void} (space
 * between and around the planets' nets).
 */
public final class BiomeTable {
    public static final String VOID = "worldengine:void";

    private BiomeTable() {
    }

    public static String biomeId(PlanetBiome biome) {
        return switch (biome) {
            case DEEP_OCEAN -> "minecraft:deep_ocean";
            case OCEAN -> "minecraft:ocean";
            case BEACH -> "minecraft:beach";
            case PLAINS -> "minecraft:plains";
            case FOREST -> "minecraft:forest";
            case TAIGA -> "minecraft:taiga";
            case TUNDRA -> "minecraft:snowy_plains";
            case DESERT -> "minecraft:desert";
            case SAVANNA -> "minecraft:savanna";
            case JUNGLE -> "minecraft:jungle";
            case MOUNTAINS -> "minecraft:stony_peaks";
            case SNOWY_PEAKS -> "minecraft:frozen_peaks";
            case BARREN -> "worldengine:barren";
        };
    }
}
```

`mod/src/main/resources/data/worldengine/worldgen/biome/void.json` and `barren.json` (identical for now; M1f and later give the moon its own sky):

```json
{
  "attributes": {
    "minecraft:gameplay/natural_mob_spawns": {
      "argument": {
        "spawn_costs": {},
        "spawns_by_category": {
          "ambient": [],
          "axolotls": [],
          "creature": [],
          "misc": [],
          "monster": [],
          "underground_water_creature": [],
          "water_ambient": [],
          "water_creature": []
        }
      },
      "modifier": "overlay"
    },
    "minecraft:visual/sky_color": "#7ba4ff"
  },
  "carvers": [],
  "downfall": 0.0,
  "effects": {
    "water_color": "#3f76e4"
  },
  "features": [
    [],
    [],
    [],
    [],
    [],
    [],
    [],
    [],
    [],
    [],
    []
  ],
  "has_precipitation": false,
  "temperature": 0.5
}
```

- [ ] **Step 4: Run the test.**

Run: `cd mod && ./gradlew test --tests '*BiomeTableTest'`
Expected: 2 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/main/resources/data mod/src/test/java/io/github/chabelabela/worldengine/planet
git commit -m "M1d: biome table and the void and barren biomes"
```

---

### Task 6: Spawn search and the terrain stamp

`SpawnSearch` samples every 4th chunk (64 blocks apart) in square rings outward from a planet's E1 centre. It returns the first chunk whose centre column is at least 1 m above sea level, searching up to 8,192 blocks out: all of the home planet's face E1, at most 66,049 one-column samples. A first draft that stopped at 1 km found no land for one CI seed, because oceans can be that wide. Without land the search falls back to the centre chunk. Vanilla then picks the exact spawn block inside the chunk.

`TerrainStamp` holds the versions a world was generated with. Only a changed **terrain** version warns: a changed ABI alone doesn't change any block.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/SpawnSearch.java`, `TerrainStamp.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/SpawnSearchTest.java`, `TerrainStampTest.java`

- [ ] **Step 1: Write the failing tests.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpawnSearchTest {
    @Test
    void landAtTheCentreIsTheCentreChunk() {
        assertArrayEquals(new int[] {0, 0}, SpawnSearch.findLandChunk(Planets.HOME, (f, x, z) -> 5, 8));
    }

    @Test
    void searchesOutwardInRings() {
        // Home's E1 centre (cell 8,192) is block x = 0. Land only from cell 8,392 (block 200) eastward. Samples
        // sit 64 blocks apart at block 8 + 64 * step, so the first on land is step 3 (block 200), in ring 3.
        // A ring is scanned row by row from its north edge, so the first hit is step (3, -3): chunk (12, -12).
        SpawnSearch.Heights eastIsLand = (face, x, z) -> face == PlanetFace.E1 && x >= 8_392 ? 3 : -10;
        assertArrayEquals(new int[] {12, -12}, SpawnSearch.findLandChunk(Planets.HOME, eastIsLand, 8));
    }

    @Test
    void anOceanPlanetFallsBackToTheCentreAfterSearchingAllOfE1() {
        AtomicInteger samples = new AtomicInteger();
        int[] chunk = SpawnSearch.findLandChunk(Planets.HOME, (f, x, z) -> {
            samples.incrementAndGet();
            return -5;
        }, SpawnSearch.MAX_RADIUS_STEPS);
        assertArrayEquals(new int[] {0, 0}, chunk);
        // Samples run from block -8,184 to 8,200 on both axes. All are on the net except the corner
        // (8,200, 8,200), which is beyond both E2 and South.
        int side = 2 * SpawnSearch.MAX_RADIUS_STEPS + 1;
        assertEquals(side * side - 1, samples.get());
    }

    @Test
    void sampledCellsAreOnThePlanet() {
        int[] moon = SpawnSearch.findLandChunk(Planets.MOON, (f, x, z) -> 2, 2);
        assertArrayEquals(new int[] {Math.floorDiv(Planets.MOON.centreX(), 16), Math.floorDiv(Planets.MOON.centreZ(), 16)}, moon);
    }
}
```

```java
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
```

- [ ] **Step 2: Run them to see them fail.**

Run: `cd mod && ./gradlew test --tests '*SpawnSearchTest' --tests '*TerrainStampTest'`
Expected: compilation fails.

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

/** Finds dry land near a planet's E1 centre, for the world spawn and {@code /worldengine goto}. */
public final class SpawnSearch {
    /** Height in metres of one terrain column, given its face and cell. */
    @FunctionalInterface
    public interface Heights {
        int height(PlanetFace face, int x, int z);
    }

    /** Spacing of the samples: every 4th chunk (64 blocks) in each direction. */
    public static final int STEP_CHUNKS = 4;
    /**
     * How far out to look, in steps: 128 steps is 8,192 blocks, the whole of the home planet's face E1. Oceans
     * can span more than a kilometre, so a shorter search can miss land. At most 66,049 samples.
     */
    public static final int MAX_RADIUS_STEPS = 128;

    private SpawnSearch() {
    }

    /**
     * The sampled chunk nearest the planet's E1 centre, by square rings of {@link #STEP_CHUNKS}-chunk steps,
     * whose centre column is at least 1 m above sea level. Returns {chunkX, chunkZ}; the centre chunk itself if
     * no ring has land.
     */
    public static int[] findLandChunk(PlanetSpec planet, Heights heights, int maxRadiusSteps) {
        int cx0 = Math.floorDiv(planet.centreX(), 16);
        int cz0 = Math.floorDiv(planet.centreZ(), 16);
        for (int r = 0; r <= maxRadiusSteps; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // inside the ring: already checked
                    }
                    int cx = cx0 + dx * STEP_CHUNKS;
                    int cz = cz0 + dz * STEP_CHUNKS;
                    FaceCell cell = planet.layout().cellAt(cx * 16 + 8, cz * 16 + 8);
                    if (cell != null && heights.height(cell.face(), cell.x(), cell.z()) >= 1) {
                        return new int[] {cx, cz};
                    }
                }
            }
        }
        return new int[] {cx0, cz0};
    }
}
```

```java
package io.github.chabelabela.worldengine.planet;

/**
 * The terrain and ABI versions a world was generated with. Version 0 means "not stamped yet".
 */
public record TerrainStamp(int terrainVersion, int abiVersion) {
    public static final TerrainStamp NONE = new TerrainStamp(0, 0);

    public boolean isStamped() {
        return terrainVersion != 0;
    }

    /**
     * A warning if chunks generated now won't match chunks generated with {@code this} stamp, else null.
     * A changed ABI alone is harmless: only the terrain model decides what blocks a chunk gets.
     */
    public String warningFor(TerrainStamp current) {
        if (!isStamped() || terrainVersion == current.terrainVersion) {
            return null;
        }
        return "This world's terrain was generated with terrain version " + terrainVersion
                + ", but this World Engine generates version " + current.terrainVersion
                + ". New chunks will not line up with old ones at their borders.";
    }
}
```

- [ ] **Step 4: Run all the planet tests.**

Run: `cd mod && ./gradlew test --tests 'io.github.chabelabela.worldengine.planet.*'`
Expected: 27 tests pass.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/test/java/io/github/chabelabela/worldengine/planet
git commit -m "M1d: spawn search and terrain stamp"
```

---

### Task 7: The universe generator and the World Engine world type

Minecraft 26.3 generates a chunk in stages: biomes (`createBiomes`), then terrain (`buildTerrain`, which replaced the older fill/surface/carver steps), then vanilla features. The generator overrides the first two:
- **Biomes:** one 16×16 native call per chunk. Each 4×4 quart takes the biome of its centre column.
- **Terrain:** one 16×16 call. Blocks are written straight into the chunk sections, as vanilla's noise generator does, and the two world-generation heightmaps are updated.
- **Outside a planet's net:** nothing (air, `worldengine:void`).

It also overrides:
- `getBaseHeight`/`getBaseColumn`, used by structures and spawning. They sample one column.
- `getOrigin`, which decides where vanilla looks for the world spawn (and centres stronghold rings): dry land near the home planet's E1 centre, via `SpawnSearch`. Up to 66,049 one-column native calls in the worst case, so it is cached per seed.
- `createState`, which hands the world seed to the biome source. Vanilla gives biome sources only a climate sampler, which has no seed.

**Failure policy:** `PlanetTerrain` throws if the core is missing or a call fails. On a world-generation thread this crashes the server with that message, instead of saving a hole or a half-written chunk into the world permanently.

The world preset reuses vanilla's Nether and End and adds itself to the `minecraft:normal` world-preset tag, so "World Engine" appears among the world types on the Create World screen. Its overworld uses the `worldengine:universe` dimension type from Task 4.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/level/GroundStates.java`, `PlanetTerrain.java`, `UniverseBiomeSource.java`, `UniverseChunkGenerator.java`
- Create: `mod/src/main/resources/data/worldengine/worldgen/world_preset/universe.json`, `mod/src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json`, `mod/src/main/resources/assets/worldengine/lang/en_us.json`
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java`

The GameTests in Task 10 test this task's code in a real server. Until then the check is that it compiles.

- [ ] **Step 1: Block states for each `Ground`.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.planet.Ground;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The block state for each {@link Ground}. A separate class so blocks are looked up after bootstrap. */
final class GroundStates {
    private static final BlockState[] STATES = new BlockState[Ground.values().length];

    static {
        put(Ground.AIR, Blocks.AIR);
        put(Ground.WATER, Blocks.WATER);
        put(Ground.BEDROCK, Blocks.BEDROCK);
        put(Ground.STONE, Blocks.STONE);
        put(Ground.DIRT, Blocks.DIRT);
        put(Ground.GRASS, Blocks.GRASS_BLOCK);
        put(Ground.SAND, Blocks.SAND);
        put(Ground.SANDSTONE, Blocks.SANDSTONE);
        put(Ground.GRAVEL, Blocks.GRAVEL);
        put(Ground.SNOW, Blocks.SNOW_BLOCK);
    }

    private GroundStates() {
    }

    static BlockState of(Ground ground) {
        return STATES[ground.ordinal()];
    }

    private static void put(Ground ground, Block block) {
        STATES[ground.ordinal()] = block.defaultBlockState();
    }
}
```

- [ ] **Step 2: Native terrain samples and the failure policy.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.SpawnSearch;

/**
 * Terrain samples for world generation, from the native core.
 *
 * <p>Failure policy: if the core is missing or a call fails, throw. On a worldgen thread that crashes the
 * server with this message instead of saving a hole or a half-written chunk into the world for good.
 */
public final class PlanetTerrain {
    private PlanetTerrain() {
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
        return SpawnSearch.findLandChunk(planet, (face, x, z) ->
                core.terrainColumns(seed, planet.cells(), planet.kind(), face.id(), x, z, 1, 1).height(0, 0),
                SpawnSearch.MAX_RADIUS_STEPS);
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

- [ ] **Step 3: The biome source.**

```java
package io.github.chabelabela.worldengine.level;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.planet.BiomeTable;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Biomes of the universe level: each planet column's {@link PlanetBiome} through {@link BiomeTable}, and
 * {@code worldengine:void} off the planets.
 *
 * <p>Biomes depend on the world seed, which vanilla only passes to biome sources inside a climate sampler.
 * {@link UniverseChunkGenerator#createState} binds it here before the level asks for any biome. Chunk
 * generation doesn't use this path: the generator fills each chunk's biomes from one batched call.
 */
public final class UniverseBiomeSource extends BiomeSource {
    public static final MapCodec<UniverseBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RegistryOps.<Biome, UniverseBiomeSource>retrieveGetter(Registries.BIOME))
            .apply(i, UniverseBiomeSource::new));

    private final List<Holder<Biome>> byPlanetBiome;
    private final Holder<Biome> voidBiome;
    private volatile Long worldSeed;

    public UniverseBiomeSource(HolderGetter<Biome> biomes) {
        List<Holder<Biome>> holders = new ArrayList<>();
        for (PlanetBiome biome : PlanetBiome.values()) {
            holders.add(lookup(biomes, BiomeTable.biomeId(biome)));
        }
        this.byPlanetBiome = List.copyOf(holders);
        this.voidBiome = lookup(biomes, BiomeTable.VOID);
    }

    private static Holder<Biome> lookup(HolderGetter<Biome> biomes, String id) {
        return biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
    }

    Holder<Biome> holder(PlanetBiome biome) {
        return byPlanetBiome.get(biome.ordinal());
    }

    Holder<Biome> voidBiome() {
        return voidBiome;
    }

    void bindWorldSeed(long seed) {
        worldSeed = seed;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(byPlanetBiome.stream(), Stream.of(voidBiome)).distinct();
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        return (quartX, quartY, quartZ) -> biomeAt(QuartPos.toBlock(quartX) + 2, QuartPos.toBlock(quartZ) + 2);
    }

    /** The biome of block column (x, z), sampled at that one column. */
    Holder<Biome> biomeAt(int x, int z) {
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            return voidBiome;
        }
        Long seed = worldSeed;
        if (seed == null) {
            throw new IllegalStateException("World Engine biomes were asked for before the level was created");
        }
        return holder(PlanetTerrain.column(hit, seed).biome(0, 0));
    }
}
```

- [ ] **Step 4: The chunk generator.**

```java
package io.github.chabelabela.worldengine.level;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.ColumnMaterials;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.Ground;
import io.github.chabelabela.worldengine.planet.LevelShape;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * The universe level's generator: planet terrain from the native core where a chunk lies on a planet's
 * net (see {@link Planets}), empty space everywhere else. Vanilla then decorates the terrain through the
 * biomes (trees, grass, ores, mobs) and places structures.
 *
 * <p>Each chunk costs one batched native call for its biomes and one for its blocks, about 0.25 ms each.
 */
public final class UniverseChunkGenerator extends ChunkGenerator {
    public static final MapCodec<UniverseChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RegistryOps.<Biome, UniverseChunkGenerator>retrieveGetter(Registries.BIOME))
            .apply(i, UniverseChunkGenerator::new));

    private final UniverseBiomeSource biomes;
    private volatile Spawn spawn;

    private record Spawn(long worldSeed, ChunkPos chunk) {
    }

    public UniverseChunkGenerator(HolderGetter<Biome> biomeGetter) {
        this(new UniverseBiomeSource(biomeGetter));
    }

    private UniverseChunkGenerator(UniverseBiomeSource biomes) {
        super(biomes);
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /** Called once when the level is created, before anything asks for biomes or terrain. */
    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
        PlanetTerrain.core(); // fail early, with a clear message, if the core is missing
        biomes.bindWorldSeed(seed);
        return super.createState(structureSets, randomState, seed);
    }

    /** Where the world spawn (and stronghold rings) centre: dry land near the home planet's E1 centre. */
    @Override
    public ChunkPos getOrigin(RandomState randomState) {
        long seed = randomState.seed();
        Spawn cached = spawn;
        if (cached == null || cached.worldSeed() != seed) {
            int[] chunk = PlanetTerrain.landChunk(Planets.HOME, seed);
            cached = new Spawn(seed, new ChunkPos(chunk[0], chunk[1]));
            spawn = cached;
        }
        return cached.chunk();
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState, Blender blender, StructureManager structures, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        PlanetHit hit = Planets.at(minX, minZ);
        if (hit == null) {
            Holder<Biome> space = biomes.voidBiome();
            chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> space);
        } else {
            TerrainColumns columns = PlanetTerrain.sample(hit, randomState.seed(), 16, 16);
            chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> {
                // Sample each 4x4 quart at its centre column, as UniverseBiomeSource#createResolver does.
                int dx = Math.clamp((quartX << 2) + 2 - minX, 0, 15);
                int dz = Math.clamp((quartZ << 2) + 2 - minZ, 0, 15);
                return biomes.holder(columns.biome(dx, dz));
            });
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
            StructureManager structures, BiomeManager biomeManager, WorldGenRegion region,
            Set<Holder<Biome>> biomesInChunk) {
        ChunkPos pos = chunk.getPos();
        PlanetHit hit = Planets.at(pos.getMinBlockX(), pos.getMinBlockZ());
        if (hit == null) {
            return CompletableFuture.completedFuture(chunk);
        }
        TerrainColumns columns = PlanetTerrain.sample(hit, randomState.seed(), 16, 16);
        PlanetKind kind = hit.planet().kind();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = Math.max(chunk.getMinY(), LevelShape.MIN_Y);
        int maxY = Math.min(chunk.getMaxY(), LevelShape.maxY());
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int height = columns.height(dx, dz);
                PlanetBiome biome = columns.biome(dx, dz);
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

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            return level.getMinY();
        }
        TerrainColumns column = PlanetTerrain.column(hit, randomState.seed());
        int height = column.height(0, 0);
        PlanetBiome biome = column.biome(0, 0);
        PlanetKind kind = hit.planet().kind();
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
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            Arrays.fill(states, GroundStates.of(Ground.AIR));
        } else {
            TerrainColumns column = PlanetTerrain.column(hit, randomState.seed());
            for (int i = 0; i < states.length; i++) {
                states[i] = GroundStates.of(ColumnMaterials.at(
                        level.getMinY() + i, column.height(0, 0), column.biome(0, 0), hit.planet().kind()));
            }
        }
        return new NoiseColumn(level.getMinY(), states);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // As NoiseBasedChunkGenerator does: passive mobs from the biome when a chunk is first generated.
        ChunkPos pos = region.getCenter();
        BlockPos top = pos.getWorldPosition().atY(region.getMaxY());
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(region.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(region, top, pos, random);
    }

    @Override
    public int getGenDepth() {
        return LevelShape.HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return LevelShape.SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return LevelShape.MIN_Y;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
        PlanetHit hit = Planets.at(pos.getX(), pos.getZ());
        if (hit == null) {
            info.add("World Engine: empty space");
        } else {
            FaceCell cell = hit.cell();
            info.add(String.format(Locale.ROOT, "World Engine: %s, face %s, cell %d %d",
                    hit.planet().name(), cell.face(), cell.x(), cell.z()));
        }
    }
}
```

- [ ] **Step 5: Register the codecs and expose the core.** In `WorldEngineMod.java`:
  - Add these imports, in order with the existing ones:

```java
import io.github.chabelabela.worldengine.level.UniverseBiomeSource;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
```

  - Replace the line `    private static NativeCore core; // set once in onInitialize; null if the core is unavailable` with:

```java
    private static volatile NativeCore core; // set once in onInitialize; null if the core is unavailable
```

  - Add these after the `universe` field:

```java

    /** The native core, or null if it failed to load. */
    public static NativeCore core() {
        return core;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
```

  - Make these the first lines of `onInitialize()`:

```java
        // Always registered, even without the core, so World Engine worlds still load and fail with a clear
        // message instead of an unknown generator type.
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("universe"), UniverseChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, id("universe"), UniverseBiomeSource.CODEC);

```

- [ ] **Step 6: The world preset, its tag, and names.**

`mod/src/main/resources/data/worldengine/worldgen/world_preset/universe.json`:

```json
{
  "dimensions": {
    "minecraft:overworld": {
      "type": "worldengine:universe",
      "generator": {
        "type": "worldengine:universe"
      }
    },
    "minecraft:the_end": {
      "type": "minecraft:the_end",
      "generator": {
        "type": "minecraft:noise",
        "biome_source": {
          "type": "minecraft:the_end"
        },
        "settings": "minecraft:end"
      }
    },
    "minecraft:the_nether": {
      "type": "minecraft:the_nether",
      "generator": {
        "type": "minecraft:noise",
        "biome_source": {
          "type": "minecraft:multi_noise",
          "preset": "minecraft:nether"
        },
        "settings": "minecraft:nether"
      }
    }
  }
}
```

`mod/src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json` (tags merge, so this adds to vanilla's list):

```json
{
  "values": [
    "worldengine:universe"
  ]
}
```

`mod/src/main/resources/assets/worldengine/lang/en_us.json`:

```json
{
  "generator.worldengine.universe": "World Engine",
  "biome.worldengine.barren": "Barren",
  "biome.worldengine.void": "Void"
}
```

- [ ] **Step 7: Compile** (JDK 25 only; otherwise CI in Task 11).

Run: `cd mod && ./gradlew compileJava test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit.**

```bash
git add mod/src/main
git commit -m "M1d: universe chunk generator, biome source and World Engine world type"
```

---

### Task 8: Stamp worlds with the terrain version

A universe world stores `worldengine:world_stamp` in its saved data (`data/worldengine/world_stamp.dat` in the save folder) the first time it starts. On later starts, a different terrain version logs a warning: new chunks would not line up with old ones. The world keeps its first stamp, so the warning repeats until someone deals with it (M3+ may offer a migration).

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/level/WorldStamp.java`
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java`

- [ ] **Step 1: The saved data.**

```java
package io.github.chabelabela.worldengine.level;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Saved with the universe level: the terrain and ABI versions its chunks were generated with. */
public final class WorldStamp extends SavedData {
    public static final Codec<WorldStamp> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("terrain_version").forGetter(s -> s.stamp.terrainVersion()),
            Codec.INT.fieldOf("abi_version").forGetter(s -> s.stamp.abiVersion()))
            .apply(i, (terrain, abi) -> new WorldStamp(new TerrainStamp(terrain, abi))));
    public static final SavedDataType<WorldStamp> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(WorldEngineMod.MOD_ID, "world_stamp"), WorldStamp::new, CODEC, null);

    private TerrainStamp stamp;

    public WorldStamp() {
        this(TerrainStamp.NONE);
    }

    private WorldStamp(TerrainStamp stamp) {
        this.stamp = stamp;
    }

    public TerrainStamp stamp() {
        return stamp;
    }

    /**
     * Checks this world against the running core, and stamps it if it has no stamp yet. Returns a warning to
     * log, or null. A world keeps its first stamp, so the warning repeats on every start.
     */
    public String reconcile(TerrainStamp current) {
        String warning = stamp.warningFor(current);
        if (!stamp.isStamped()) {
            stamp = current;
            setDirty();
        }
        return warning;
    }
}
```

- [ ] **Step 2: Check the stamp when the server starts.** In `WorldEngineMod.java`:
  - Add these imports, in order with the existing ones:

```java
import io.github.chabelabela.worldengine.level.WorldStamp;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
```

  - Make `checkWorldStamp(server, loaded);` the first line of the `SERVER_STARTED` handler.
  - Add this method before `loadCore()`:

```java
    /** Records the terrain version in a new universe world, and warns if an old world's differs. */
    private static void checkWorldStamp(MinecraftServer server, NativeCore core) {
        ServerLevel overworld = server.overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)) {
            return;
        }
        TerrainStamp current = new TerrainStamp(core.terrainVersion(), core.abiVersion());
        String warning = overworld.getDataStorage().computeIfAbsent(WorldStamp.TYPE).reconcile(current);
        if (warning != null) {
            LOGGER.warn(warning);
        }
    }
```

- [ ] **Step 3: Compile.**

Run: `cd mod && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add mod/src/main
git commit -m "M1d: stamp universe worlds with the terrain version"
```

---

### Task 9: `/worldengine` commands for game masters

The commands move out of `WorldEngineMod` into `WorldEngineCommands`, behind 26.3's permission check (`Permissions.COMMANDS_GAMEMASTER`, the old operator level 2):

| Command | Does |
|---|---|
| `/worldengine where` | The planet, face and cell under you, with that column's height and biome |
| `/worldengine goto <home\|moon>` | Teleports you to dry land near that planet's E1 centre (universe worlds only) |
| `/worldengine terrain <face> <x> <z>` | The home planet's terrain at a cell (now with the planet's own seed, as world generation uses) |
| `/worldengine bodies` | Unchanged: the demo universe's bodies |

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineCommands.java`
- Modify (whole file): `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java`

- [ ] **Step 1: The commands.**

```java
package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** The {@code /worldengine} debug commands. Game masters (operator level 2) only. */
final class WorldEngineCommands {
    private static final int HOME_CELLS = Planets.HOME.cells();

    private WorldEngineCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("worldengine")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("bodies").executes(context -> bodies(context.getSource())))
                .then(Commands.literal("where").executes(context -> where(context.getSource())))
                .then(Commands.literal("goto")
                        .then(Commands.argument("planet", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Planets.ALL.stream().map(PlanetSpec::name), builder))
                                .executes(context -> goTo(
                                        context.getSource(), StringArgumentType.getString(context, "planet")))))
                .then(Commands.literal("terrain")
                        .then(Commands.argument("face", IntegerArgumentType.integer(0, 5))
                                .then(Commands.argument("x", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                        .then(Commands.argument("z", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                                .executes(context -> terrain(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "face"),
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z"))))))));
    }

    /** Which planet, face and cell the executor stands on, and that column's terrain. */
    private static int where(CommandSourceStack source) {
        Vec3 position = source.getPosition();
        int x = Mth.floor(position.x());
        int z = Mth.floor(position.z());
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            reply(source, String.format(Locale.ROOT, "(%d, %d): empty space", x, z));
            return Command.SINGLE_SUCCESS;
        }
        if (!coreLoaded(source)) {
            return 0;
        }
        TerrainColumns column = PlanetTerrain.column(hit, source.getLevel().getSeed());
        FaceCell cell = hit.cell();
        reply(source, String.format(Locale.ROOT, "(%d, %d): %s, face %s, cell (%d, %d), %d m, %s",
                x, z, hit.planet().name(), cell.face(), cell.x(), cell.z(), column.height(0, 0), column.biome(0, 0)));
        return Command.SINGLE_SUCCESS;
    }

    /** Teleports the player to dry land near {@code name}'s E1 centre. */
    private static int goTo(CommandSourceStack source, String name) throws CommandSyntaxException {
        PlanetSpec planet = Planets.byName(name);
        if (planet == null) {
            source.sendFailure(Component.literal("Unknown planet " + name));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        if (!(level.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)) {
            source.sendFailure(Component.literal("This world isn't a World Engine universe"));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        int[] chunk = PlanetTerrain.landChunk(planet, level.getSeed());
        int x = chunk[0] * 16 + 8;
        int z = chunk[1] * 16 + 8;
        level.getChunk(chunk[0], chunk[1]);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        reply(source, String.format(Locale.ROOT, "Sent to %s at (%d, %d, %d)", planet.name(), x, y, z));
        return Command.SINGLE_SUCCESS;
    }

    /** The home planet's terrain at cell (x, z) of a face, for this world's seed. */
    private static int terrain(CommandSourceStack source, int face, int x, int z) {
        if (!coreLoaded(source)) {
            return 0;
        }
        PlanetHit hit = new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.fromId(face), x, z));
        TerrainColumns column = PlanetTerrain.column(hit, source.getLevel().getSeed());
        reply(source, String.format(Locale.ROOT, "home, face %s, cell (%d, %d): %d m, %s",
                PlanetFace.fromId(face), x, z, column.height(0, 0), column.biome(0, 0)));
        return Command.SINGLE_SUCCESS;
    }

    private static int bodies(CommandSourceStack source) {
        NativeCore.Universe universe = WorldEngineMod.universe();
        if (universe == null) {
            source.sendFailure(Component.literal(WorldEngineMod.core() == null
                    ? "World Engine core failed to load (see the game log)"
                    : "World Engine universe is not running"));
            return 0;
        }
        universe.setTime(source.getLevel().getGameTime() / 20.0);
        for (int i = 0; i < universe.bodyCount(); i++) {
            double[] p = universe.bodyPosition(i);
            reply(source, String.format(Locale.ROOT, "%s: x=%.1f y=%.1f z=%.1f m, |r|=%.1f m",
                    universe.bodyName(i), p[0], p[1], p[2], Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static boolean coreLoaded(CommandSourceStack source) {
        if (WorldEngineMod.core() == null) {
            source.sendFailure(Component.literal("World Engine core failed to load (see the game log)"));
            return false;
        }
        return true;
    }

    private static void reply(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }
}
```

- [ ] **Step 2: Slim down `WorldEngineMod`.** Replace the whole file with the following. `HOME_CELLS`, `printTerrain` and `printBodies` are gone, and the command registration is now one call.

```java
package io.github.chabelabela.worldengine;

import io.github.chabelabela.worldengine.level.UniverseBiomeSource;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import io.github.chabelabela.worldengine.level.WorldStamp;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldEngineMod implements ModInitializer {
    public static final String MOD_ID = "worldengine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile NativeCore core; // set once in onInitialize; null if the core is unavailable
    private static NativeCore.Universe universe; // server thread only

    /** The native core, or null if it failed to load. */
    public static NativeCore core() {
        return core;
    }

    /** The running demo universe, or null. Server thread only. */
    static NativeCore.Universe universe() {
        return universe;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        // Always registered, even without the core, so World Engine worlds still load and fail with a clear
        // message instead of an unknown generator type.
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("universe"), UniverseChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, id("universe"), UniverseBiomeSource.CODEC);

        NativeCore loaded = loadCore();
        core = loaded;
        if (loaded != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                checkWorldStamp(server, loaded);
                if (universe != null) {
                    universe.close(); // only if a stop event was ever missed
                    universe = null;
                }
                try {
                    universe = loaded.newDemoUniverse();
                } catch (NativeCoreException e) {
                    LOGGER.error("World Engine could not create its universe; the mod stays idle this session", e);
                }
            });
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                if (universe != null) {
                    universe.close();
                    universe = null;
                }
            });
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WorldEngineCommands.register(dispatcher));
    }

    /** Records the terrain version in a new universe world, and warns if an old world's differs. */
    private static void checkWorldStamp(MinecraftServer server, NativeCore core) {
        ServerLevel overworld = server.overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof UniverseChunkGenerator)) {
            return;
        }
        TerrainStamp current = new TerrainStamp(core.terrainVersion(), core.abiVersion());
        String warning = overworld.getDataStorage().computeIfAbsent(WorldStamp.TYPE).reconcile(current);
        if (warning != null) {
            LOGGER.warn(warning);
        }
    }

    /** Loads the Rust core, or logs why it can't and returns null so the game still starts. */
    private static NativeCore loadCore() {
        Path cache = FabricLoader.getInstance().getGameDir().resolve(".worldengine").resolve("natives");
        NativeCore loaded;
        Path library;
        int abi;
        try {
            library = NativeLibraryLoader.locate(cache);
            loaded = NativeCore.load(library);
            abi = loaded.abiVersion();
        } catch (RuntimeException e) {
            // Deliberately RuntimeException, not just NativeCoreException: the core is optional, so any
            // non-fatal failure must degrade to an inactive mod rather than kill the game. Errors still propagate.
            LOGGER.error("World Engine core failed to load on {} {}; the mod stays inactive and /worldengine reports this. "
                    + "Please include this log when reporting the problem.",
                    System.getProperty("os.name"), System.getProperty("os.arch"), e);
            return null;
        }
        try {
            NativeLibraryLoader.pruneStaleCaches(cache, library);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not prune old World Engine native caches", e);
        }
        LOGGER.info("World Engine core loaded: ABI v{} from {}", abi, library);
        return loaded;
    }
}
```

- [ ] **Step 3: Compile and run the unit tests.**

Run: `cd mod && ./gradlew compileJava test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add mod/src/main
git commit -m "M1d: /worldengine where, goto and terrain, for game masters"
```

---

### Task 10: GameTests in a real server

Loom's `fabricApi.configureTests` adds a `gametest` source set, packaged as its own mod (`worldengine-gametest`), plus a `runGameTest` task that starts a headless GameTest server. The GameTest server needs no `eula.txt`. `./gradlew build` runs it as part of `check`, so the existing CI `mod` job runs the GameTests with no workflow change.

The GameTest server's world is flat and ignores data-pack dimensions. So the tests take the `worldengine:universe` preset from the server's registry, which also proves the data pack and codecs load. They bind its generator to the test world's seed (as a real level does through `createState`) and generate stand-alone chunks with it. They then compare those chunks with the core and with `ColumnMaterials`, block by block, over the full height.

**Files:**
- Modify: `mod/build.gradle`
- Create: `mod/src/gametest/resources/fabric.mod.json`
- Create: `mod/src/gametest/java/io/github/chabelabela/worldengine/gametest/UniverseGameTests.java`

- [ ] **Step 1: Enable GameTests.** In `mod/build.gradle`, insert before `loom {`:

```groovy
// GameTests run in a headless server: ./gradlew runGameTest, also part of check. They are their own
// source set and mod, so test code never ships in the release jar.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = 'worldengine-gametest'
        enableClientGameTests = false
    }
}
```

and add `'runGameTest'` to the tasks that build the core first:

```groovy
    if (name in ['runClient', 'runServer', 'runGameTest']) {
```

- [ ] **Step 2: The test mod's metadata.**

`mod/src/gametest/resources/fabric.mod.json`:

```json
{
  "schemaVersion": 1,
  "id": "worldengine-gametest",
  "version": "1.0.0",
  "name": "World Engine GameTests",
  "environment": "*",
  "entrypoints": {
    "fabric-gametest": [
      "io.github.chabelabela.worldengine.gametest.UniverseGameTests"
    ]
  },
  "depends": {
    "worldengine": "*"
  }
}
```

- [ ] **Step 3: The tests.**

```java
package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import io.github.chabelabela.worldengine.level.WorldStamp;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.BiomeTable;
import io.github.chabelabela.worldengine.planet.ColumnMaterials;
import io.github.chabelabela.worldengine.planet.LevelShape;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * World generation checks inside a real server. The GameTest server's own world is flat, so these tests take
 * the {@code worldengine:universe} world preset from its registry (decoded from the mod's data pack, exactly
 * as a new world would be), and run its generator on stand-alone chunks.
 */
public class UniverseGameTests {
    private static final ResourceKey<WorldPreset> PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, WorldEngineMod.id("universe"));
    /** A column on each face of the home planet (E1, E0, E2, E3, North, South), and one on the moon. */
    private static final int[][] COLUMNS = {
        {3, 5}, {-9_000, 1_234}, {9_000, -4_321}, {30_000, 2_000}, {100, -9_000}, {-200, 9_500}, {106_100, 40}};

    @GameTest
    public void worldTypeIsRegistered(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<WorldPreset> preset = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET);
        check(helper, preset.is(WorldPresetTags.NORMAL), "the preset is listed on the Create World screen");
        LevelStem overworld = preset.value().overworld().orElseThrow();
        check(helper, overworld.generator() instanceof UniverseChunkGenerator, "the overworld uses the universe generator");
        DimensionType type = overworld.type().value();
        check(helper, type.minY() == LevelShape.MIN_Y && type.height() == LevelShape.HEIGHT, "the level is y -384..895");
        for (String id : new String[] {BiomeTable.VOID, "worldengine:barren"}) {
            check(helper, registries.lookupOrThrow(Registries.BIOME).get(biome(id)).isPresent(), id + " is loaded");
        }
        helper.succeed();
    }

    @GameTest
    public void chunksFollowTheCore(GameTestHelper helper) {
        Universe u = universe(helper);
        for (int[] c : COLUMNS) {
            ProtoChunk chunk = u.generate(new ChunkPos(c[0] >> 4, c[1] >> 4));
            PlanetHit hit = Planets.at(c[0], c[1]);
            TerrainColumns column = PlanetTerrain.column(hit, u.seed());
            int height = column.height(0, 0);
            String where = hit.planet().name() + " (" + c[0] + ", " + c[1] + ") at " + height + " m";
            var expected = u.generator().getBaseColumn(c[0], c[1], chunk, u.randomState());
            for (int y = LevelShape.MIN_Y; y <= LevelShape.maxY(); y++) {
                BlockPos pos = new BlockPos(c[0], y, c[1]);
                if (chunk.getBlockState(pos) != expected.getBlock(y)) {
                    helper.fail("block at y " + y + " of " + where + " is " + chunk.getBlockState(pos)
                            + ", expected " + expected.getBlock(y));
                }
            }
            check(helper, chunk.getBlockState(new BlockPos(c[0], LevelShape.MIN_Y, c[1])).is(Blocks.BEDROCK),
                    "bedrock floor at " + where);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, c[0] & 15, c[1] & 15);
            check(helper, floor == LevelShape.surfaceY(height), "ocean floor heightmap at " + where + " is " + floor);
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, c[0] & 15, c[1] & 15);
            check(helper, top == ColumnMaterials.topY(height, hit.planet().kind()), "surface heightmap at " + where);
            int base = u.generator().getBaseHeight(c[0], c[1], Heightmap.Types.WORLD_SURFACE_WG, chunk, u.randomState());
            check(helper, base == top + 1, "getBaseHeight agrees with the chunk at " + where);
        }
        helper.succeed();
    }

    @GameTest
    public void spaceIsEmpty(GameTestHelper helper) {
        Universe u = universe(helper);
        int x = 70_000;
        int z = 0;
        check(helper, Planets.at(x, z) == null, "the test column is off every planet");
        ProtoChunk chunk = u.generate(new ChunkPos(x >> 4, z >> 4));
        for (LevelChunkSection section : chunk.getSections()) {
            check(helper, section.hasOnlyAir(), "no blocks in space");
        }
        check(helper, noiseBiome(chunk, QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z)).is(biome(BiomeTable.VOID)),
                "space is the void biome");
        helper.succeed();
    }

    @GameTest
    public void biomesFollowTheTable(GameTestHelper helper) {
        Universe u = universe(helper);
        for (int[] c : COLUMNS) {
            int qx = QuartPos.fromBlock(c[0]);
            int qy = QuartPos.fromBlock(64);
            int qz = QuartPos.fromBlock(c[1]);
            PlanetHit hit = Planets.at(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2);
            String expected = BiomeTable.biomeId(PlanetTerrain.column(hit, u.seed()).biome(0, 0));
            ProtoChunk chunk = u.generate(new ChunkPos(c[0] >> 4, c[1] >> 4));
            check(helper, noiseBiome(chunk, qx, qy, qz).is(biome(expected)),
                    "chunk biome at (" + c[0] + ", " + c[1] + ") is " + expected);
            Holder<Biome> direct = u.generator().getBiomeSource().createUncachedResolver(u.randomState())
                    .getNoiseBiome(qx, qy, qz);
            check(helper, direct.is(biome(expected)), "biome source at (" + c[0] + ", " + c[1] + ") is " + expected);
        }
        helper.succeed();
    }

    @GameTest
    public void spawnIsOnDryLand(GameTestHelper helper) {
        Universe u = universe(helper);
        ChunkPos origin = u.generator().getOrigin(u.randomState());
        PlanetHit hit = Planets.at(origin.getMinBlockX() + 8, origin.getMinBlockZ() + 8);
        check(helper, hit != null && hit.planet() == Planets.HOME, "the spawn chunk is on the home planet");
        int height = PlanetTerrain.column(hit, u.seed()).height(0, 0);
        check(helper, height >= 1, "the spawn column is dry land, got " + height + " m");
        helper.succeed();
    }

    @GameTest
    public void generationIsFastEnough(GameTestHelper helper) {
        Universe u = universe(helper);
        for (int i = 0; i < 4; i++) {
            u.generate(new ChunkPos(100 + i, 0)); // warm up
        }
        int chunks = 64;
        long start = System.nanoTime();
        for (int i = 0; i < chunks; i++) {
            u.generate(new ChunkPos(i % 8, i / 8));
        }
        double ms = (System.nanoTime() - start) / 1e6 / chunks;
        WorldEngineMod.LOGGER.info("GameTest: biomes + terrain took {} ms per chunk", String.format(Locale.ROOT, "%.2f", ms));
        // Generous, to stay reliable on shared CI machines; vanilla's noise generator takes a few ms per chunk.
        check(helper, ms < 25, "biomes + terrain took " + ms + " ms per chunk");
        helper.succeed();
    }

    @GameTest
    public void worldStampSurvivesASave(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        Path dir = Files.createTempDirectory("worldengine-stamp");
        try (SavedDataStorage storage = new SavedDataStorage(dir, level.getServer().getFixerUpper(), level.registryAccess())) {
            check(helper, storage.computeIfAbsent(WorldStamp.TYPE).reconcile(new TerrainStamp(1, 3)) == null,
                    "a new world is stamped without a warning");
            storage.saveAndJoin();
        }
        try (SavedDataStorage storage = new SavedDataStorage(dir, level.getServer().getFixerUpper(), level.registryAccess())) {
            WorldStamp stamp = storage.computeIfAbsent(WorldStamp.TYPE);
            check(helper, stamp.stamp().equals(new TerrainStamp(1, 3)), "the stamp was saved, got " + stamp.stamp());
            check(helper, stamp.reconcile(new TerrainStamp(2, 3)) != null, "a newer terrain version warns");
        }
        helper.succeed();
    }

    /** The universe preset's generator, bound to the test world's seed as a real level would bind it. */
    private record Universe(UniverseChunkGenerator generator, RandomState randomState, ServerLevel level) {
        long seed() {
            return randomState.seed();
        }

        ProtoChunk generate(ChunkPos pos) {
            LevelHeightAccessor height = LevelHeightAccessor.create(LevelShape.MIN_Y, LevelShape.HEIGHT);
            ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, height,
                    PalettedContainerFactory.create(level.registryAccess()), null);
            generator.createBiomes(randomState, Blender.empty(), null, chunk).join();
            generator.buildTerrain(chunk, Blender.empty(), randomState, null, null, null, Set.of()).join();
            return chunk;
        }
    }

    private static Universe universe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        UniverseChunkGenerator generator = (UniverseChunkGenerator) registries.lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(PRESET).value().overworld().orElseThrow().generator();
        RandomState randomState = level.getChunkSource().randomState();
        generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, randomState.seed());
        return new Universe(generator, randomState, level);
    }

    /** The biome stored for a quart. Read from the section: a stand-alone chunk has no generation status. */
    private static Holder<Biome> noiseBiome(ProtoChunk chunk, int quartX, int quartY, int quartZ) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(QuartPos.toBlock(quartY)));
        return section.getNoiseBiome(quartX & 3, quartY & 3, quartZ & 3);
    }

    private static ResourceKey<Biome> biome(String id) {
        return ResourceKey.create(Registries.BIOME, Identifier.parse(id));
    }

    private static void check(GameTestHelper helper, boolean condition, String what) {
        helper.assertTrue(condition, what);
    }
}
```

- [ ] **Step 4: Run them** (JDK 25 only; otherwise CI in Task 11).

Run: `cd mod && ./gradlew runGameTest`
Expected: near the end of the log, `GameTest: biomes + terrain took … ms per chunk`, then `All 8 required tests passed :)` (the server adds one test of its own to these 7), then BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add mod/build.gradle mod/src/gametest
git commit -m "M1d: GameTests for the universe generator"
```

---

### Task 11: Push, CI, docs

- [ ] **Step 1: Push and watch CI.**

```bash
git push -u origin <branch>
```

Every job must pass:
- `core` on 5 targets, with tests on 4. The ABI v3 tests run here.
- `mod`: `./gradlew build`, which now includes the GameTests.
- `mod-tests` on Windows and macOS.

If a GameTest fails, its message names the column and what differed.

- [ ] **Step 2: Docs.**
  - `docs/ROADMAP.md` §5 M1: mark the world preset and chunk generator bullets "**Done in M1d**" with a link to this plan.
  - `docs/superpowers/plans/2026-11-20-m1-overview.md`: mark M1d done. Restate the gate: the next step is the M1b spike on the owner's PC.

- [ ] **Step 3: Manual checks** (owner, on a desktop with Minecraft 26.3; note the results in the PR):
  1. **Create World:** under World Type there is "World Engine". The new world spawns on dry land, and F3 shows a `World Engine: home, face E1, cell …` line.
  2. **Look around:** trees and grass, beaches, oceans at y 62; snowy peaks in the mountains.
  3. **Commands:** `/worldengine where` at spawn; `/worldengine goto moon` lands on grey gravel with no water and no mobs; `/worldengine goto home` returns.
  4. **Net edges:** fly to a face edge, e.g. `/tp @s 8200 200 0` (E1 → E2), then `/tp @s 0 200 -8200` (E1 → North). Terrain continues across these edges, because they are ordinary neighbours in the net. Flying off the net's outer edge leads into empty void. Seams that wrap around the cube are M1e's job.
  5. **Speed:** flying fast at render distance 12 keeps up, roughly like vanilla. Note anything slower.
  6. **Restart** the world: the log shows no terrain-version warning.

- [ ] **Step 4: Commit.**

```bash
git add docs
git commit -m "M1d: docs"
git push
```

- [ ] **Step 5: Gate.** Stop here. The next work is the [M1b spike](2026-11-20-m1b-handoff-spike.md), which needs the owner at their PC. M1e and M1f are planned only after ADR 0001 is accepted.

---

## Self-review against the M1d inputs (M1 overview, "Inputs for the M1d plan")

| Input | Where |
|---|---|
| Level height from −400..700 m, plus room | `LevelShape`: y −384..895; tests check the margins and the dimension type JSON (Task 4) |
| `PlanetSpec` as the single source of `HOME_CELLS` and friends | `Planets.HOME`/`MOON` (Task 3); `HOME_CELLS` constants removed |
| Face origins aligned to 16 | `PlanetLayout` rejects anything else; test that every chunk is on one face (Task 2) |
| Failure policy on worldgen threads | Throw with planet, face and cell, which crashes rather than saving bad chunks (`PlanetTerrain`, Task 7) |
| `TERRAIN_VERSION` and ABI in saved data | `we_terrain_version` (Task 1), `WorldStamp` (Task 8), GameTest round trip (Task 10) |
| Debug commands gated | `Permissions.COMMANDS_GAMEMASTER` (Task 9) |
| Sea-level fill | Water fills y < 63; top block at `62 + h` (Task 4) |
| Biome resolution | One 16×16 call per chunk, the quart's centre column; the same rule in the biome source (Task 7) |
| CI `--locked` | Already in CI (M1c) |
| One table for the 13 biomes | `BiomeTable` (Task 5) |

## Known limits (by design, for later phases)

- **Seams:** the net's interior edges join naturally. The 7 edges that wrap around the cube (E3 → E0, and North/South with E0, E2, E3) end in void until M1e adds overlap bands and teleports.
- **Sky and time:** vanilla's, until M1f.
- **No caves or carvers.** Vanilla features (trees, ores, springs) and structures come through the biomes. A structure that crosses a face edge is cut off.
- **The Nether and End are vanilla.** Portals still link to them.
- **Speed:** biomes plus terrain take about 9 ms per chunk on a CI machine, measured on one thread; Minecraft generates several chunks at once on its worker threads. If the manual check (Task 11) finds generation slow, the first fix is to fill all-stone sections in one step (`new LevelChunkSection(PalettedContainer, PalettedContainerRO)`) instead of block by block. Updating heightmaps only at the top of each column made no measurable difference.

## Changing the terrain model later

1. Change `we-planet`. The golden-hash test fails; re-pin `TERRAIN_GOLDEN`.
2. Bump `TERRAIN_VERSION` (`core/crates/we-planet/src/terrain.rs`). `NativeCoreTest.reportsTerrainVersion` pins the number, so update it too.
3. Existing universe worlds then log the stamp warning on every start, because new chunks won't line up with old ones at their borders. Players either keep the world (accepting seams where old and new chunks meet) or start a new one.
4. A re-stamp or migration path (a command, or per-chunk versions) is future work, to be decided before the first public release.

## 26.3 API notes (found with `javap` on the 26.3 jar)

- **Chunk generation:** `ChunkGenerator.buildTerrain(ChunkAccess, Blender, RandomState, StructureManager, BiomeManager, WorldGenRegion, Set<Holder<Biome>>)` replaced `fillFromNoise`, `buildSurface` and `applyCarvers`. `ChunkStatus` now goes EMPTY, STRUCTURE_STARTS, STRUCTURE_REFERENCES, BIOMES, **TERRAIN**, FEATURES, INITIALIZE_LIGHT, LIGHT, SPAWN, FULL.
- **Seed and spawn:**
  - `RandomState.seed()` is public.
  - `ChunkGenerator.getOrigin(RandomState)` is where `MinecraftServer.setInitialSpawn` and `createState` (stronghold rings) start; vanilla then scans 11×11 chunks around it with `PlayerSpawnFinder`.
- **Biome sources:** `BiomeSource.createResolver(Climate.Sampler)` returns a `BiomeResolver` (x, y, z, with no sampler argument). `findBiomeHorizontal` and friends take a `RandomState`.
- **Debug screen:** `addDebugScreenInfo` gained a `SamplerContext` parameter.
- **Renames:**
  - `ResourceLocation` → `Identifier` (`fromNamespaceAndPath`, `parse`).
  - `DimensionDataStorage` → `SavedDataStorage`; `SavedDataType(Identifier, Supplier, Codec, DataFixTypes)`.
  - `ServerLevel.setRespawnData(LevelData.RespawnData)`.
- **Permissions:** `CommandSourceStack.permissions()` returns a `PermissionSet`; check it with `hasPermission(Permissions.COMMANDS_GAMEMASTER)`.
- **Data formats:**
  - Dimension type JSON has `attributes` (sky, fog, cloud colour and height, music, beds), `timelines` and `default_clock`.
  - Biome JSON keeps mob spawns and sky colour under `attributes`.
- **GameTests:** Fabric GameTest API v1 uses `@GameTest` on methods taking a `GameTestHelper`, with the entrypoint `fabric-gametest`. The GameTest server ignores data-pack dimensions.
