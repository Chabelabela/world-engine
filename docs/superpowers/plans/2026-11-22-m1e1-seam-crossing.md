# M1e-1 Seam Crossing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a World Engine world, anything that walks, runs, flies, swims or drops off a planet's net across a **seam** arrives on the neighbouring face. A seam is one of the 7 cube edges per planet that the cross-shaped net doesn't join. The crossing keeps the thing's height, momentum and look direction, rotated with the fold, with no loading screen. Beyond a cube corner, where there is no neighbour, it is put back where it last stood. A player can now walk around the equator, or over a pole, and come back to the start.

**Where this comes from:** [ADR 0001](../../adr/0001-frames-and-handoff.md), accepted 2026-11-22, and experiment E2 of the [M1b spike](2026-11-20-m1b-handoff-spike.md): 84 launched crossings of all 14 directed home seams were exact, with 0 corrections. This plan turns that spike code into production code and adds what the ADR asks of M1e:
- land one tick of travel short of fold's point;
- a cooldown against folding straight back;
- corners as no-man's land;
- pre-load the far side.

**Split:** M1e is two plans. This one (M1e-1) does the crossing itself, at the seam edge. **M1e-2** adds the overlap bands, a mirrored copy of the neighbouring face beyond each seam, and moves the teleport to the middle of the band. Until then, looking across a seam shows empty space, and the far side's terrain loads in just after arriving.

**Architecture:**
- **Rust:** `we-ffi` exports `we_seam_cross` (**ABI v4**). It calls `we_frames::CubeSphere::fold` and turns the grid velocity and yaw by the fold's `QuarterTurns`. It is the spike's `we_spike_cross` made permanent, so the mod keeps one source of truth for the cube's topology.
- **Java bridge:** `NativeCore.seamCross` returns a `SeamCrossing`, or null where nothing folds.
- **Pure Java:** `planet.SeamCrossings`:
  - `plan` decides what happens to something that has left the net: cross or bounce.
  - `nearEdge` tests closeness to a face edge.
  - `farSides` lists the far sides of nearby seams, for pre-loading.

  It is unit-tested with a stand-in folder, and against the real core on all 28 directed seams.
- **Minecraft:** `level.SeamCrossingHandler`, at the end of each server tick in the universe level, checks every entity:
  - It remembers where entities near a face edge stood.
  - It teleports those found off the net with vanilla's `TeleportTransition`, which is server-initiated, so there are no movement corrections. No mixins are needed.
  - It pre-loads the far side for players near a seam, with an expiring ticket.
- **Tests:** Fabric GameTests run the handler on dropped items placed at the net's coordinates in the GameTest level.

**Tech Stack:** Rust stable (edition 2024) · Java 25 FFM · Minecraft 26.3 + Fabric API 0.166.2 (lifecycle events, GameTest API v1) · JUnit 5

**What has been checked before execution (2026-11-22):** every file in this plan was built and run from a scratch worktree of `e2c777b`.
- ✅ **Rust:** `cargo test --workspace --locked` passes (`we-ffi` 23 unit + 2 integration, `we-frames` 44, `we-planet` 23, `we-orbit` 20). clippy (`-D warnings`) and fmt are clean.
- ✅ **Pure Java:** the 37 `worldengine.planet` JUnit tests pass on JDK 21, 7 of them new for `SeamCrossings`.
- ✅ **Bridge on JDK 21:** `NativeCore.seamCross` ran against the built library. All 28 directed seams of both planets cross onto the face the Java net expects, and cross back to where they started.
- ✅ **Minecraft side, in CI** ([mc-api run #10](https://github.com/Chabelabela/world-engine/actions/runs/45062545512); a temporary workflow, removed afterwards): the whole mod compiled against Minecraft 26.3. `./gradlew build` passed, including the native `NativeSeamTest` and all GameTests (12 in total; the 4 new seam tests among them).
- ✅ **The plan reproduces that code:** replaying this plan's steps onto a clean `e2c777b` gives the same files, byte for byte, as the tree CI built and tested.
- ⚠️ **Not checked:** crossing seams by hand in a real client. That covers how the chunk pop looks (E2 measured 60–260 ms), and boats or minecarts with riders. Task 6 lists the manual checks.

**Post-execution note (2026-11-22):** executed with Haiku implementers and Sonnet reviewers. Every task's files matched the CI-verified reference byte for byte. The code reviews then found two real problems, fixed in one follow-up commit (`6c3820c`), so **the repository, not these code blocks, is authoritative**:
- **The one-tick step-back now applies to players only.** It exists because a player's client advances once more after a teleport (E2). Items and mobs, which the server moves, already had this tick's movement, so they land at fold's point. A player whose step-back point would be off the net also lands at fold's point, so they can't be left beyond the edge and folded back after the cooldown. The GameTests, which use items, now expect fold's point.
- **Teleports away aren't undone.** Something that moved more than `SeamCrossings.MAX_STEP` (16 blocks) along either axis since its last position was teleported: a command, a portal, and later the ship handoff to the shipyard. It is left alone and forgotten, where before it would have been "bounced" back. There's a new unit test and GameTest for this.
- **Smaller fixes:**
  - the handler's state is cleared when a server starts (singleplayer reuses the JVM);
  - deferred teleports skip entities removed in the meantime;
  - `we_seam_cross` writes its results unaligned-safe;
  - the `farSides` test checks the exact points it folds.

**Environment note:** without JDK 25 (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 6). Don't download JDKs. Rust steps always run locally. The pure planet tests can run on any JDK ≥ 21 with the JUnit console launcher.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `core/crates/we-ffi/src/seams.rs` | Create | `we_seam_cross`, `WE_SEAM_NOT_FOLDABLE` |
| `core/crates/we-ffi/src/lib.rs` | Modify | ABI v4, the module, docs |
| `core/crates/we-ffi/tests/abi.rs` | Modify | The call sequence crosses a seam |
| `mod/src/main/java/…/nativebridge/SeamCrossing.java` | Create | Result record |
| `mod/src/main/java/…/nativebridge/NativeCore.java` | Modify | `seamCross`, expects ABI v4 |
| `mod/src/test/java/…/nativebridge/NativeSeamTest.java` | Create | Bridge tests; Java net vs Rust fold on all 28 directed seams |
| `mod/src/main/java/…/planet/SeamCrossings.java` | Create | Cross or bounce; near an edge; far sides |
| `mod/src/test/java/…/planet/SeamCrossingsTest.java` | Create | Unit tests with a stand-in folder |
| `mod/src/main/java/…/level/SeamCrossingHandler.java` | Create | The per-tick handler, cooldown, pre-load ticket |
| `mod/src/main/java/…/WorldEngineMod.java` | Modify | Registers the handler when the core loads |
| `mod/src/gametest/java/…/gametest/SeamGameTests.java` | Create | Crossings in a real server |
| `mod/src/gametest/resources/fabric.mod.json` | Modify | Lists the new GameTest class |

(`…` is `io/github/chabelabela/worldengine`.)

---

### Task 1: `we_seam_cross` in the C ABI (v4)

**Files:**
- Create: `core/crates/we-ffi/src/seams.rs`
- Modify: `core/crates/we-ffi/src/lib.rs`, `core/crates/we-ffi/tests/abi.rs`

- [ ] **Step 1: Write the module with its tests.** `core/crates/we-ffi/src/seams.rs`:

```rust
//! Seam crossing: carrying an entity across one of a planet's seam edges (M1e).

use crate::{WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use we_frames::{CubeSphere, Face, SurfacePoint};

/// Returned by [`we_seam_cross`] when the point can't be folded: it is on
/// its face (edges included), beyond a corner, or more than one face width
/// out. Not an error: the caller decides what happens there.
pub const WE_SEAM_NOT_FOLDABLE: i32 = 1;

/// Carry grid point (`x`, `z`) of `face`, which lies beyond one of the face's
/// edges, onto the neighbouring face (`we_frames::CubeSphere::fold`), with a
/// grid velocity (`vx` east, `vz` south, any unit) and a Minecraft yaw in
/// degrees. Velocity and yaw are rotated by the fold's quarter turns
/// (`QuarterTurns::apply`, `QuarterTurns::rotate_yaw`).
///
/// Writes 7 doubles to `out`: the new face index (0–5), x, z (grid, on the new
/// face), quarter turns (0–3), rotated vx, vz, and rotated yaw.
///
/// Returns `WE_OK`, [`WE_SEAM_NOT_FOLDABLE`], `WE_ERR_NULL` for a null `out`,
/// or `WE_ERR_INVALID` for a bad `cells` or face index. Stateless and
/// thread-safe.
///
/// # Safety
/// `out` must be null or valid for writing 7 `f64`s. It needn't be aligned.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn we_seam_cross(
    cells: u32,
    face: u32,
    x: f64,
    z: f64,
    vx: f64,
    vz: f64,
    yaw: f32,
    out: *mut f64,
) -> i32 {
    if out.is_null() {
        return WE_ERR_NULL;
    }
    let (Some(sphere), Some(face)) = (
        CubeSphere::try_new(cells),
        u8::try_from(face).ok().and_then(Face::from_index),
    ) else {
        return WE_ERR_INVALID;
    };
    guard(|| {
        let Some(folded) = sphere.fold(SurfacePoint { face, x, y: 0.0, z }) else {
            return WE_SEAM_NOT_FOLDABLE;
        };
        let (nvx, nvz) = folded.turns.apply(vx, vz);
        let result = [
            f64::from(folded.point.face.index()),
            folded.point.x,
            folded.point.z,
            f64::from(folded.turns.count()),
            nvx,
            nvz,
            f64::from(folded.turns.rotate_yaw(yaw)),
        ];
        // SAFETY: the caller guarantees `out` is valid for 7 f64 writes.
        unsafe { std::ptr::copy_nonoverlapping(result.as_ptr(), out, result.len()) };
        WE_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const N: u32 = 16_384;

    fn cross(face: u32, x: f64, z: f64, vx: f64, vz: f64, yaw: f32) -> (i32, [f64; 7]) {
        let mut out = [f64::NAN; 7];
        let status = unsafe { we_seam_cross(N, face, x, z, vx, vz, yaw, out.as_mut_ptr()) };
        (status, out)
    }

    #[test]
    fn crosses_the_equator_wrap_without_rotation() {
        // Just past E3's east edge folds onto E0's west edge, unrotated.
        let (status, out) = cross(3, 16_384.25, 100.5, 0.3, 0.0, -90.0);
        assert_eq!(status, WE_OK);
        assert_eq!(out, [0.0, 0.25, 100.5, 0.0, 0.3, 0.0, -90.0]);
    }

    #[test]
    fn rotates_velocity_and_yaw_with_the_fold() {
        // North's west edge meets E0 (E2 in the M1b spike: a westward sprint
        // continues southward, yaw 90 -> 0).
        let (status, out) = cross(4, -0.25, 100.5, -0.281, 0.0, 90.0);
        assert_eq!(status, WE_OK);
        assert_eq!(out[0], 0.0, "lands on E0");
        assert_eq!(out[3], 3.0, "three quarter turns");
        assert_eq!((out[4], out[5]), (0.0, 0.281));
        assert_eq!(out[6], 0.0);
    }

    #[test]
    fn matches_we_frames_fold_for_every_face_and_side() {
        let sphere = CubeSphere::new(N);
        let n = f64::from(N);
        for face in Face::ALL {
            for (x, z) in [
                (-0.5, 777.25),
                (n + 0.5, 777.25),
                (777.25, -0.5),
                (777.25, n + 0.5),
            ] {
                let folded = sphere.fold(SurfacePoint { face, x, y: 0.0, z }).unwrap();
                let (status, out) = cross(u32::from(face.index()), x, z, 1.0, 0.0, 0.0);
                assert_eq!(status, WE_OK);
                assert_eq!(out[0], f64::from(folded.point.face.index()));
                assert_eq!((out[1], out[2]), (folded.point.x, folded.point.z));
                assert_eq!(out[3], f64::from(folded.turns.count()));
                assert_eq!((out[4], out[5]), folded.turns.apply(1.0, 0.0));
            }
        }
    }

    #[test]
    fn leaves_points_it_cannot_fold() {
        assert_eq!(cross(0, 10.0, 10.0, 0.0, 0.0, 0.0).0, WE_SEAM_NOT_FOLDABLE); // on the face
        assert_eq!(cross(0, -1.0, -1.0, 0.0, 0.0, 0.0).0, WE_SEAM_NOT_FOLDABLE); // beyond a corner
        assert_eq!(
            cross(0, f64::NAN, 1.0, 0.0, 0.0, 0.0).0,
            WE_SEAM_NOT_FOLDABLE
        );
    }

    #[test]
    fn rejects_bad_input() {
        let mut out = [0.0; 7];
        let p = out.as_mut_ptr();
        assert_eq!(
            unsafe { we_seam_cross(16_383, 0, -1.0, 10.0, 0.0, 0.0, 0.0, p) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_seam_cross(N, 6, -1.0, 10.0, 0.0, 0.0, 0.0, p) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_seam_cross(N, 0, -1.0, 10.0, 0.0, 0.0, 0.0, std::ptr::null_mut()) },
            WE_ERR_NULL
        );
    }
}
```

- [ ] **Step 2: Wire it into the crate and bump the ABI.** In `core/crates/we-ffi/src/lib.rs`:
  - Replace `mod terrain;` with:

```rust
mod seams;
mod terrain;
```

  - Add before `pub use terrain::{we_terrain_columns, we_terrain_version};`:

```rust
pub use seams::{WE_SEAM_NOT_FOLDABLE, we_seam_cross};
```

  - Set `pub const WE_ABI_VERSION: u32 = 4;`, and in the unit test `reports_abi_version` expect `4`.
  - In the module doc, replace the status-code rule line with:

```rust
//! - Return `i32` status codes: `WE_OK`, a negative `WE_ERR_*`, or a documented
//!   positive outcome such as `WE_SEAM_NOT_FOLDABLE`.
```

    and replace the two lines that start `` //! `we_terrain_columns` takes no handle `` with:

```rust
//! `we_terrain_columns` and `we_seam_cross` take no handle and keep no state:
//! any number of threads may call them at once.
```

- [ ] **Step 3: Extend the ABI call sequence.** In `core/crates/we-ffi/tests/abi.rs`, add after the line `assert_eq!(we_terrain_version(), we_planet::TERRAIN_VERSION);`:

```rust
    let mut crossed = [0.0f64; 7];
    assert_eq!(
        unsafe {
            we_seam_cross(
                16_384,
                3,
                16_384.25,
                1.5,
                0.0,
                0.0,
                0.0,
                crossed.as_mut_ptr(),
            )
        },
        WE_OK
    );
    assert_eq!(crossed[0], 0.0, "E3's east edge wraps onto E0");
```

- [ ] **Step 4: Run the Rust checks.**

Run: `cd core && cargo fmt --all && cargo test --workspace --locked && cargo clippy --workspace --all-targets --locked -- -D warnings && cargo fmt --all --check`
Expected: all pass (`we-ffi`: 23 unit, 2 integration).

- [ ] **Step 5: Commit.**

```bash
git add core/crates/we-ffi
git commit -m "M1e-1: we_seam_cross (ABI v4)"
```

---

### Task 2: Seam crossing in the Java bridge

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/SeamCrossing.java`
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeSeamTest.java`

- [ ] **Step 1: Write the failing test** (its last test, over the whole net, is added in Task 3). `NativeSeamTest.java`:

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

- [ ] **Step 2: Add the result record.**

```java
package io.github.chabelabela.worldengine.nativebridge;

/**
 * Where a point that left its face across a seam edge lands ({@code we_seam_cross}): the new face (0-5, numbered as
 * {@code we_frames::Face}), the grid point on it, the fold's clockwise quarter turns, and the grid velocity and yaw
 * rotated by them.
 */
public record SeamCrossing(int face, double x, double z, int turns, double vx, double vz, float yaw) {
}
```

- [ ] **Step 3: Add `seamCross` to `NativeCore`.**
  - Add `import static java.lang.foreign.ValueLayout.JAVA_FLOAT;` after the `JAVA_DOUBLE` import.
  - Set `EXPECTED_ABI_VERSION = 4`.
  - After the `MAX_COLUMNS` line, add:

```java
    /** {@code WE_SEAM_NOT_FOLDABLE}: {@code we_seam_cross} left the point where it is. */
    private static final int SEAM_NOT_FOLDABLE = 1;
```

  - Add the field `private final MethodHandle seamCrossFn;` after `terrainVersionFn`.
  - At the end of the constructor, add:

```java
        seamCrossFn = linker.downcallHandle(
                find(lib, "we_seam_cross"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                        JAVA_FLOAT, ADDRESS));
```

  - Add this method before the Javadoc of `newDemoUniverse`:

```java
    /**
     * Carries grid point ({@code x}, {@code z}) of {@code face} (0-5), which lies beyond one of the face's edges, onto
     * the neighbouring face of a planet with {@code cells} blocks per face edge, rotating the grid velocity
     * ({@code vx} east, {@code vz} south) and the Minecraft {@code yaw} with it. Returns null if the point can't be
     * folded: it is on its face, beyond a corner, or more than one face width out.
     *
     * <p>Stateless and thread-safe, like {@link #terrainColumns}.
     */
    public SeamCrossing seamCross(int cells, int face, double x, double z, double vx, double vz, float yaw) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, 7);
            int status = (int) seamCrossFn.invokeExact(cells, face, x, z, vx, vz, yaw, out);
            if (status == SEAM_NOT_FOLDABLE) {
                return null;
            }
            check(status, "we_seam_cross");
            double[] r = out.toArray(JAVA_DOUBLE);
            return new SeamCrossing((int) r[0], r[1], r[2], (int) r[3], r[4], r[5], (float) r[6]);
        } catch (Throwable t) {
            throw wrap(t);
        }
    }
```

- [ ] **Step 4: Run the tests** (JDK 25 only; otherwise CI in Task 6).

Run: `cd mod && ./gradlew test --tests '*NativeSeamTest' --tests '*NativeCoreTest'`
Expected: all pass.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/nativebridge mod/src/test/java/io/github/chabelabela/worldengine/nativebridge
git commit -m "M1e-1: NativeCore.seamCross"
```

---

### Task 3: Deciding a crossing (`SeamCrossings`)

Pure Java, no Minecraft classes:
- `plan` takes something's position now, its last position on the net, its grid velocity and its yaw.
  - It returns nothing while the position is on a face. Crossing an edge the net joins is just walking.
  - Otherwise it returns a **cross**: fold's point minus one tick of rotated velocity (E2's overshoot), with the turned velocity and yaw.
  - Beyond a corner, where the folder returns null, it returns a **bounce** back to the last position.
- `farSides` gives, for a position near seams, where something leaving straight across each would land, so the handler can pre-load it. It skips edges the net joins.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/planet/SeamCrossings.java`
- Test: `mod/src/test/java/io/github/chabelabela/worldengine/planet/SeamCrossingsTest.java`
- Modify (whole file): `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeSeamTest.java`

- [ ] **Step 1: Write the failing unit test.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeamCrossingsTest {
    private static final int N = Planets.HOME.cells();
    private static final PlanetLayout HOME = Planets.HOME.layout();
    /** Level x of E3's east edge (a seam: the equator wraps onto E0's west edge). */
    private static final int E3_EAST = HOME.faceOriginX(PlanetFace.E3) + N;

    /** Stand-in for the native fold: the equator wrap only, like {@code we_seam_cross} does there. */
    private static final SeamCrossings.Folder WRAP = (cells, face, x, z, vx, vz, yaw) ->
            face == PlanetFace.E3 && x > cells && z >= 0 && z <= cells
                    ? new SeamCrossing(PlanetFace.E0.id(), x - cells, z, 0, vx, vz, yaw)
                    : null;

    @Test
    void onTheNetNothingHappens() {
        assertNull(SeamCrossings.plan(5, 5, 6, 5, 1, 0, 0, WRAP));
        // E1's east edge is joined to E2 in the net: crossing it is just walking.
        int e2 = HOME.faceOriginX(PlanetFace.E2);
        assertNull(SeamCrossings.plan(e2 - 0.5, 5, e2 + 0.5, 5, 1, 0, 0, WRAP));
    }

    @Test
    void somethingThatWasNotOnAPlanetIsLeftAlone() {
        assertNull(SeamCrossings.plan(70_000, 0, 70_001, 0, 1, 0, 0, WRAP));
    }

    @Test
    void aCrossingLandsOneTickShortOfFoldsPoint() {
        SeamCrossings.Outcome outcome =
                SeamCrossings.plan(E3_EAST - 4.5, 100.5, E3_EAST + 0.25, 100.5, 0.3, 0, -90, WRAP);
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class, outcome);
        int e0 = HOME.faceOriginX(PlanetFace.E0);
        assertEquals(Planets.HOME, cross.planet());
        assertEquals(PlanetFace.E0, cross.face());
        assertEquals(e0 + 0.25, cross.foldX());
        assertEquals(100.5, cross.foldZ());
        assertEquals(e0 + 0.25 - 0.3, cross.x(), 1e-12);
        assertEquals(100.5, cross.z());
        assertEquals(0.3, cross.vx());
        assertEquals(0.0, cross.vz());
        assertEquals(-90.0F, cross.yaw());
    }

    @Test
    void theFolderGetsGridCoordinatesOnTheLastFace() {
        List<Object[]> calls = new ArrayList<>();
        SeamCrossings.Folder spy = (cells, face, x, z, vx, vz, yaw) -> {
            calls.add(new Object[] {cells, face, x, z, vx, vz, yaw});
            return new SeamCrossing(PlanetFace.SOUTH.id(), 10.0, 20.0, 1, -vz, vx, yaw + 90);
        };
        double z = HOME.faceOriginZ(PlanetFace.E3) + 7.25;
        SeamCrossings.Cross cross = assertInstanceOf(SeamCrossings.Cross.class,
                SeamCrossings.plan(E3_EAST - 1.5, z, E3_EAST + 0.75, z, 0.2, 0.1, 10, spy));
        assertArrayEquals(new Object[] {N, PlanetFace.E3, N + 0.75, 7.25, 0.2, 0.1, 10.0F}, calls.get(0));
        // Fold's point is translated into level coordinates on the face it names.
        assertEquals(HOME.faceOriginX(PlanetFace.SOUTH) + 10.0, cross.foldX());
        assertEquals(HOME.faceOriginZ(PlanetFace.SOUTH) + 20.0, cross.foldZ());
        assertEquals(cross.foldX() + 0.1, cross.x(), 1e-12); // minus the rotated velocity (-0.1, 0.2)
        assertEquals(cross.foldZ() - 0.2, cross.z(), 1e-12);
        assertEquals(100.0F, cross.yaw());
    }

    @Test
    void beyondACornerItGoesBackToWhereItLastStood() {
        int e0x = HOME.faceOriginX(PlanetFace.E0);
        int e0z = HOME.faceOriginZ(PlanetFace.E0);
        SeamCrossings.Outcome outcome = SeamCrossings.plan(e0x + 2.5, e0z + 2.5, e0x - 0.5, e0z - 0.5, -1, -1, 0, WRAP);
        SeamCrossings.Bounce bounce = assertInstanceOf(SeamCrossings.Bounce.class, outcome);
        assertEquals(e0x + 2.5, bounce.x());
        assertEquals(e0z + 2.5, bounce.z());
    }

    @Test
    void nearEdgeLooksAtAllFourEdges() {
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 3, 8_000)), 16));
        assertTrue(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 8_000, N - 1)), 16));
        assertFalse(SeamCrossings.nearEdge(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 16, N - 17)), 16));
    }

    @Test
    void farSidesAreOnlyTheSeams() {
        SeamCrossings.Folder anywhere = (cells, face, x, z, vx, vz, yaw) ->
                new SeamCrossing(PlanetFace.E2.id(), 1.0, 2.0, 0, vx, vz, yaw);
        // Every edge of E1 is joined in the net (E0, E2, North, South): nothing to pre-load.
        assertTrue(SeamCrossings.farSides(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E1, 3, 3)), 16, anywhere)
                .isEmpty());
        // E0's west edge (the equator wrap) and north edge are seams: near its corner there are two far sides.
        List<double[]> sides =
                SeamCrossings.farSides(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E0, 3, 3)), 16, anywhere);
        assertEquals(2, sides.size());
        assertArrayEquals(new double[] {HOME.faceOriginX(PlanetFace.E2) + 1.0, HOME.faceOriginZ(PlanetFace.E2) + 2.0},
                sides.get(0));
        // Away from E0's edges: none.
        assertTrue(SeamCrossings.farSides(new PlanetHit(Planets.HOME, new FaceCell(PlanetFace.E0, 500, 500)), 16,
                anywhere).isEmpty());
    }
}
```

- [ ] **Step 2: Run it to see it fail.**

Run: `cd mod && ./gradlew test --tests '*SeamCrossingsTest'`
Expected: compilation fails (`SeamCrossings` doesn't exist).

- [ ] **Step 3: Implement.**

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import java.util.ArrayList;
import java.util.List;

/**
 * What happens to something that steps off a planet's net across a seam: one of the 7 cube edges that the
 * cross-shaped net doesn't join (ADR 0001; E2 in the M1b spike).
 *
 * <p>It lands on the neighbouring face at {@code we_frames::CubeSphere::fold}'s point, keeping its height, with its
 * grid velocity and yaw turned by the fold's quarter turns. Beyond a cube corner there is no neighbour, so it is put
 * back where it last stood on the net.
 */
public final class SeamCrossings {
    /** Folds grid point (x, z) beyond an edge of {@code face}; null if it can't. In the mod: {@code NativeCore::seamCross}. */
    @FunctionalInterface
    public interface Folder {
        SeamCrossing fold(int cells, PlanetFace face, double x, double z, double vx, double vz, float yaw);
    }

    /** What to do with something that has left the net. */
    public sealed interface Outcome permits Cross, Bounce {
    }

    /**
     * Teleport to level position ({@code x}, {@code z}) on {@code face}, keeping the height, with horizontal velocity
     * ({@code vx}, {@code vz}) and {@code yaw}. ({@code foldX}, {@code foldZ}) is fold's point itself, always on the
     * face.
     */
    public record Cross(PlanetSpec planet, PlanetFace face, double x, double z, double vx, double vz, float yaw,
            double foldX, double foldZ) implements Outcome {
    }

    /** Beyond a corner: put it back at level position ({@code x}, {@code z}) and stop its horizontal movement. */
    public record Bounce(double x, double z) implements Outcome {
    }

    private SeamCrossings() {
    }

    /**
     * The outcome for something now at level position ({@code x}, {@code z}), moving ({@code vx}, {@code vz}) blocks
     * per tick and looking along {@code yaw}, whose last position on a planet's net was ({@code lastX}, {@code lastZ}).
     * Null if it is on a face (crossing an edge the net joins is just walking) or its last position wasn't on a planet.
     *
     * <p>The landing point is one tick of (rotated) travel short of fold's point. A client advances one more step
     * right after a teleport, so without this every crossing would gain a tick of travel (E2).
     */
    public static Outcome plan(double lastX, double lastZ, double x, double z, double vx, double vz, float yaw,
            Folder folder) {
        if (Planets.at(floor(x), floor(z)) != null) {
            return null;
        }
        PlanetHit last = Planets.at(floor(lastX), floor(lastZ));
        if (last == null) {
            return null;
        }
        PlanetSpec planet = last.planet();
        PlanetLayout layout = planet.layout();
        PlanetFace from = last.cell().face();
        SeamCrossing c = folder.fold(planet.cells(), from, x - layout.faceOriginX(from), z - layout.faceOriginZ(from),
                vx, vz, yaw);
        if (c == null) {
            return new Bounce(lastX, lastZ);
        }
        PlanetFace to = PlanetFace.fromId(c.face());
        double foldX = layout.faceOriginX(to) + c.x();
        double foldZ = layout.faceOriginZ(to) + c.z();
        return new Cross(planet, to, foldX - c.vx(), foldZ - c.vz(), c.vx(), c.vz(), c.yaw(), foldX, foldZ);
    }

    /** True if {@code hit}'s cell is within {@code margin} blocks of one of its face's four edges. */
    public static boolean nearEdge(PlanetHit hit, int margin) {
        FaceCell cell = hit.cell();
        int n = hit.planet().cells();
        return cell.x() < margin || cell.z() < margin || cell.x() >= n - margin || cell.z() >= n - margin;
    }

    /**
     * Level positions {x, z} of the far side of every seam within {@code margin} blocks of {@code hit}: where
     * something leaving straight across that edge would land. Edges the net joins are skipped. For pre-loading.
     */
    public static List<double[]> farSides(PlanetHit hit, int margin, Folder folder) {
        PlanetSpec planet = hit.planet();
        PlanetLayout layout = planet.layout();
        FaceCell cell = hit.cell();
        int n = planet.cells();
        double along = 0.5;
        double[][] beyond = {
            cell.x() < margin ? new double[] {-along, cell.z() + 0.5} : null,
            cell.x() >= n - margin ? new double[] {n + along, cell.z() + 0.5} : null,
            cell.z() < margin ? new double[] {cell.x() + 0.5, -along} : null,
            cell.z() >= n - margin ? new double[] {cell.x() + 0.5, n + along} : null,
        };
        List<double[]> sides = new ArrayList<>();
        for (double[] grid : beyond) {
            if (grid == null) {
                continue;
            }
            double lx = layout.faceOriginX(cell.face()) + grid[0];
            double lz = layout.faceOriginZ(cell.face()) + grid[1];
            if (Planets.at(floor(lx), floor(lz)) != null) {
                continue; // the net joins this edge
            }
            SeamCrossing c = folder.fold(n, cell.face(), grid[0], grid[1], 0, 0, 0);
            if (c != null) {
                PlanetFace to = PlanetFace.fromId(c.face());
                sides.add(new double[] {layout.faceOriginX(to) + c.x(), layout.faceOriginZ(to) + c.z()});
            }
        }
        return sides;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
```

- [ ] **Step 4: Check the whole net against the core.** Replace `NativeSeamTest.java` with this version, which adds `theJavaNetAndTheRustFoldAgreeOnEverySeam`:

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code we_seam_cross} through the bridge, and the Java net layout against the Rust fold on every seam. */
class NativeSeamTest {
    private static NativeCore core;
    private static SeamCrossings.Folder folder;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
        folder = (cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw);
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

    /**
     * Every directed seam of both planets (7 edges, both ways, 28 in all), found from the Java layout as face edges
     * whose far side is on no face: the Rust fold lands on another face of the same planet where the Java layout
     * agrees, and crossing back from there returns to the start.
     */
    @Test
    void theJavaNetAndTheRustFoldAgreeOnEverySeam() {
        int seams = 0;
        for (PlanetSpec planet : Planets.ALL) {
            PlanetLayout layout = planet.layout();
            int n = planet.cells();
            for (PlanetFace face : PlanetFace.values()) {
                double[][] sides = {
                    {-0.25, 777.25, -1, 0}, {n + 0.25, 777.25, 1, 0}, {777.25, -0.25, 0, -1}, {777.25, n + 0.25, 0, 1},
                };
                for (double[] side : sides) {
                    double x = layout.faceOriginX(face) + side[0];
                    double z = layout.faceOriginZ(face) + side[1];
                    if (Planets.at((int) Math.floor(x), (int) Math.floor(z)) != null) {
                        continue; // an edge the net joins
                    }
                    seams++;
                    String label = planet.name() + " " + face + " (" + side[2] + ", " + side[3] + ")";
                    SeamCrossings.Cross there = assertInstanceOf(SeamCrossings.Cross.class, SeamCrossings.plan(
                            x - 2 * side[2], z - 2 * side[3], x, z, 0.1 * side[2], 0.1 * side[3], 0, folder), label);
                    PlanetHit landed = Planets.at((int) Math.floor(there.foldX()), (int) Math.floor(there.foldZ()));
                    assertNotNull(landed, label);
                    assertEquals(planet, landed.planet(), label);
                    assertEquals(there.face(), landed.cell().face(), label);
                    assertNotEquals(face, there.face(), label);
                    // Half a block back out across the landing edge returns to where it left, half a block in.
                    SeamCrossings.Cross back = assertInstanceOf(SeamCrossings.Cross.class, SeamCrossings.plan(
                            there.foldX(), there.foldZ(), there.foldX() - 5 * there.vx(), there.foldZ() - 5 * there.vz(),
                            -there.vx(), -there.vz(), 0, folder), label);
                    assertEquals(face, back.face(), label);
                    assertEquals(x - 0.5 * side[2], back.foldX(), 1e-6, label);
                    assertEquals(z - 0.5 * side[3], back.foldZ(), 1e-6, label);
                }
            }
        }
        assertEquals(28, seams);
    }
}
```

- [ ] **Step 5: Run the tests.**

Run: `cd mod && ./gradlew test --tests '*SeamCrossingsTest' --tests '*NativeSeamTest'`
Expected: all pass. All 28 directed seams cross and come back.

- [ ] **Step 6: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/test/java/io/github/chabelabela/worldengine
git commit -m "M1e-1: SeamCrossings decides crossings and bounces"
```

---

### Task 4: The seam crossing handler

At the end of every server tick, in the universe level only, for every entity that isn't a passenger (passengers ride along with their vehicle):
- **On the net:** within 16 blocks of a face edge (more than a tick's travel), remember its position. A player within 64 blocks of a seam gets the far side pre-loaded, refreshed every second, with a `worldengine:seam_preload` ticket that expires after 5 s.
- **Off the net, having last stood near an edge:**
  - A cross is a `TeleportTransition` to the landing point with the turned velocity and yaw. The entity isn't checked again for 3 ticks (E2's cooldown) and is remembered at fold's point.
  - A bounce teleports it back with no horizontal speed.
  - Teleports run after the loop over the level's entities. `checkNow` runs the same check on a single entity, for the GameTests.

The velocity is `getKnownMovement()` for players (the client's last move: the server doesn't simulate players) and `getDeltaMovement()` for everything else.

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/level/SeamCrossingHandler.java`
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java`

- [ ] **Step 1: The handler.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamCrossings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Carries everything that steps off a planet's net across a seam to the neighbouring face, at the end of every server
 * tick, in the universe level (see {@link SeamCrossings}; ADR 0001, E2 in the M1b spike).
 *
 * <ul>
 *   <li>Entities within {@link #EDGE_MARGIN} blocks of a face edge have their position remembered. One that is then
 *       on no face has left across a seam, and is teleported to fold's point, one tick of travel short, with its
 *       grid velocity and yaw turned.
 *   <li>Beyond a cube corner there is no neighbour: it goes back to where it last stood, with no horizontal speed.
 *   <li>After a crossing, an entity isn't checked for {@link #COOLDOWN_TICKS} ticks, so one that lands less than a
 *       tick of travel inside the new face doesn't fold straight back.
 *   <li>Players near a seam get the far side pre-loaded, so the destination is generated before they arrive.
 * </ul>
 *
 * <p>Passengers ride along with their vehicle. Server thread only.
 */
public final class SeamCrossingHandler {
    /** Ticks after a crossing during which an entity isn't checked again (E2 needed 3). */
    public static final int COOLDOWN_TICKS = 3;
    /** Entities this close to a face edge have their position remembered: more than a tick's travel (10 blocks). */
    public static final int EDGE_MARGIN = 16;
    /** Players this close to a seam have its far side pre-loaded. */
    public static final int PRELOAD_MARGIN = 64;
    /** Chunk radius of the pre-loaded area around the far side. */
    public static final int PRELOAD_RADIUS = 2;
    /** Pre-load tickets are refreshed this often, and expire after {@link #PRELOAD_TICKS} without a refresh. */
    static final int PRELOAD_REFRESH_TICKS = 20;
    static final long PRELOAD_TICKS = 100;

    private static TicketType preloadTicket;
    private static final Map<UUID, double[]> LAST_ON_NET = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private SeamCrossingHandler() {
    }

    /** Registers the pre-load ticket type and the tick hook. Call once, from the mod initializer. */
    public static void register() {
        preloadTicket = Registry.register(BuiltInRegistries.TICKET_TYPE, WorldEngineMod.id("seam_preload"),
                new TicketType(PRELOAD_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
        ServerTickEvents.END_SERVER_TICK.register(SeamCrossingHandler::onEndTick);
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
        SeamCrossings.Folder folder = folder();
        long now = level.getServer().getTickCount();
        List<Runnable> moves = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            Runnable move = check(level, entity, now, folder);
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
        Runnable move = check(level, entity, level.getServer().getTickCount(), folder());
        if (move != null) {
            move.run();
        }
    }

    private static SeamCrossings.Folder folder() {
        NativeCore core = PlanetTerrain.core();
        return (cells, face, x, z, vx, vz, yaw) -> core.seamCross(cells, face.id(), x, z, vx, vz, yaw);
    }

    /**
     * Remembers {@code entity} near an edge, pre-loads the far side for a player near a seam, and returns the
     * teleport to run if it has left the net, or null.
     */
    private static Runnable check(ServerLevel level, Entity entity, long now, SeamCrossings.Folder folder) {
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
        PlanetHit hit = Planets.at(Mth.floor(x), Mth.floor(z));
        if (hit != null) {
            if (SeamCrossings.nearEdge(hit, EDGE_MARGIN)) {
                LAST_ON_NET.put(id, new double[] {x, z});
            } else {
                LAST_ON_NET.remove(id);
            }
            if (entity instanceof ServerPlayer && now % PRELOAD_REFRESH_TICKS == 0
                    && SeamCrossings.nearEdge(hit, PRELOAD_MARGIN)) {
                for (double[] side : SeamCrossings.farSides(hit, PRELOAD_MARGIN, folder)) {
                    level.getChunkSource().addTicketWithRadius(preloadTicket,
                            new ChunkPos(Mth.floor(side[0]) >> 4, Mth.floor(side[1]) >> 4), PRELOAD_RADIUS);
                }
            }
            return null;
        }
        double[] last = LAST_ON_NET.get(id);
        if (last == null) {
            return null;
        }
        Vec3 velocity = entity instanceof ServerPlayer player ? player.getKnownMovement() : entity.getDeltaMovement();
        SeamCrossings.Outcome outcome =
                SeamCrossings.plan(last[0], last[1], x, z, velocity.x, velocity.z, entity.getYRot(), folder);
        if (outcome instanceof SeamCrossings.Cross cross) {
            LAST_ON_NET.put(id, new double[] {cross.foldX(), cross.foldZ()});
            COOLDOWN_UNTIL.put(id, now + COOLDOWN_TICKS);
            return () -> entity.teleport(new TeleportTransition(level,
                    new Vec3(cross.x(), entity.getY(), cross.z()), new Vec3(cross.vx(), velocity.y, cross.vz()),
                    cross.yaw(), entity.getXRot(), TeleportTransition.DO_NOTHING));
        }
        if (outcome instanceof SeamCrossings.Bounce bounce) {
            return () -> entity.teleport(new TeleportTransition(level,
                    new Vec3(bounce.x(), entity.getY(), bounce.z()), new Vec3(0, velocity.y, 0),
                    entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
        }
        return null;
    }

    /** Drops remembered state for entities that no longer exist anywhere. */
    private static void forgetMissing(MinecraftServer server) {
        LAST_ON_NET.keySet().removeIf(id -> !exists(server, id));
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

- [ ] **Step 2: Register it when the core loads.** In `WorldEngineMod.java`:
  - Add `import io.github.chabelabela.worldengine.level.SeamCrossingHandler;` before the `UniverseBiomeSource` import.
  - Make `SeamCrossingHandler.register();` the first line inside `if (loaded != null) {` in `onInitialize()`.

- [ ] **Step 3: Compile** (JDK 25 only; otherwise CI in Task 6).

Run: `cd mod && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine
git commit -m "M1e-1: carry entities across seams in the universe level"
```

---

### Task 5: GameTests for crossings

The GameTest level is flat, but the handler only needs level coordinates. Each test does all of this within one tick:
1. It loads every chunk it touches: where the item starts, where it is moved, and where it lands.
2. It drops a still item (no gravity) there, and calls `SeamCrossingHandler.checkNow` once, so the item is remembered.
3. It moves the item across an edge, as its own tick would, and calls `checkNow` again.

The tests call the per-entity check directly, not the per-tick loop over the level's entities. An entity in a chunk loaded that same tick isn't always in that list yet; three draft runs failed on that.

The four tests:
- **The equator wrap:** a translation. Includes the cooldown.
- **North's west edge:** three quarter turns, so velocity and yaw are rotated.
- **A corner:** the item bounces back.
- **An edge the net joins:** nothing happens.

**Files:**
- Create: `mod/src/gametest/java/io/github/chabelabela/worldengine/gametest/SeamGameTests.java`
- Modify: `mod/src/gametest/resources/fabric.mod.json`

- [ ] **Step 1: The tests.**

```java
package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.level.SeamCrossingHandler;
import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
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
        Scene.run(helper, east - 4.5, z, new double[] {east + 0.25, z, west + 0.25 - 0.3, z}, (scene, item) -> {
            scene.move(item, east + 0.25, z, new Vec3(0.3, 0, 0), -90.0F);
            // Fold's point is 0.25 inside E0's west edge; it lands one tick (0.3) short of it.
            scene.expect(item, west + 0.25 - 0.3, z, 0.3, 0.0, -90.0F, "across the equator wrap");
            // Now 0.05 blocks off E0's edge, but the cooldown stops it folding straight back.
            SeamCrossingHandler.checkNow(helper.getLevel(), item);
            scene.expect(item, west + 0.25 - 0.3, z, 0.3, 0.0, -90.0F, "still there during the cooldown");
        });
    }

    @GameTest
    public void aRotatedSeamTurnsMomentumAndLook(GameTestHelper helper) {
        int ox = HOME.faceOriginX(PlanetFace.NORTH);
        int oz = HOME.faceOriginZ(PlanetFace.NORTH);
        SeamCrossing fold = WorldEngineMod.core().seamCross(N, PlanetFace.NORTH.id(), -0.25, 100.5, -0.281, 0, 90);
        double foldX = HOME.faceOriginX(PlanetFace.E0) + fold.x();
        double foldZ = HOME.faceOriginZ(PlanetFace.E0) + fold.z();
        double[] load = {ox - 0.25, oz + 100.5, foldX, foldZ - 0.281};
        Scene.run(helper, ox + 3.5, oz + 100.5, load, (scene, item) -> {
            scene.move(item, ox - 0.25, oz + 100.5, new Vec3(-0.281, 0, 0), 90.0F);
            // North's west edge meets E0 three quarter turns round: running west carries on running south.
            scene.expect(item, foldX, foldZ - 0.281, 0.0, 0.281, 0.0F, "across North's west edge");
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

- [ ] **Step 2: Register the class.** In `mod/src/gametest/resources/fabric.mod.json`, the `fabric-gametest` entrypoint list becomes:

```json
    "fabric-gametest": [
      "io.github.chabelabela.worldengine.gametest.UniverseGameTests",
      "io.github.chabelabela.worldengine.gametest.SeamGameTests"
    ]
```

- [ ] **Step 3: Run them** (JDK 25 only; otherwise CI in Task 6).

Run: `cd mod && ./gradlew runGameTest`
Expected: `All 12 required tests passed :)`, then BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add mod/src/gametest
git commit -m "M1e-1: GameTests for seam crossings"
```

---

### Task 6: Push, CI, docs

- [ ] **Step 1: Push and watch CI.** Every job must pass:
  - `core` on 5 targets, which runs the ABI v4 tests;
  - `mod`: `./gradlew build`, which includes the GameTests;
  - `mod-tests` on Windows and macOS, which run the native `NativeSeamTest` there too.

- [ ] **Step 2: Docs.**
  - `docs/ROADMAP.md` §5 M1, the "Seams" bullet: add "**Seam crossing done in M1e-1** ([plan](superpowers/plans/2026-11-22-m1e1-seam-crossing.md)); overlap bands and edit mirroring are M1e-2."
  - `docs/superpowers/plans/2026-11-20-m1-overview.md`, the M1e row: link this plan, mark it done, and say that M1e-2 (bands and mirroring) is next.

- [ ] **Step 3: Manual checks** (owner, in a World Engine world, creative and survival):
  1. **Around the equator:** fly east from spawn along z = 0. The run passes E1, E2 and E3 (x up to 40,960), then appears at x = −24,576 on E0 heading the same way. Back at x = 0 you're where you started.
  2. **Over a pole:** walk north from E1 onto North (z < −8,192). Then cross North's west edge at x = −8,192: you arrive on E0 near its north edge, running **south**, with the view turned to match.
  3. **Sprint-jump** across a seam: there's no rubber-banding, and the far side's chunks pop in within a fraction of a second.
  4. **A corner:** walk off E0 diagonally at its north-west corner (x ≈ −24,576, z ≈ −8,192). You're put back on E0.
  5. **Mobs and items:** drop an item or push a mob across a seam. It arrives on the far side.
  6. **Riding:** cross a seam in a boat on the ocean and in a minecart. Note anything odd about the riders.

- [ ] **Step 4: Commit and push.**

```bash
git add docs
git commit -m "M1e-1: docs"
git push
```

---

## Known limits (by design, for M1e-2 and later)

- **No overlap bands yet.**
  - Looking across a seam shows empty space.
  - The far side's chunks load just after arriving (E2: about 60–260 ms of holes, less with the pre-load).
  - Blocks can't be placed beyond a seam edge.

  M1e-2 adds mirrored bands and moves the teleport to their middle.
- **Corners:** beyond a cube corner there is no neighbour, so things bounce back. Terrain there is deep ocean (M1c).
- **Other players** can drop out of view for up to about a second after one of them crosses a seam. It's the entity-tracking gap measured in E6; M2's membership tracking or a tracking re-check fixes it.
- **Riders:** passengers aren't checked themselves, so they cross with their vehicle. Boats and minecarts are a manual check (Task 6).
- **Entities crossing into unloaded chunks** are saved there by vanilla, as with any entity that moves into an unloaded area.
- **Short teleports across a seam:** a command or portal move of 16 blocks or less (`MAX_STEP`) from near an edge to just beyond a seam looks like walking, so it is treated as a crossing.

## Self-review against ADR 0001 ("What M1e must do")

| ADR requirement | Where |
|---|---|
| Teleport at `fold`'s point with `QuarterTurns`-rotated grid velocity and yaw | `we_seam_cross` (Task 1), `SeamCrossings.plan` (Task 3), `SeamCrossingHandler` (Task 4) |
| Compensate the one-tick overshoot: land at `fold(p) − rotate(v)` | **Players only** (their client advances once more; see the post-execution note): `SeamCrossings.plan` offers both points, and `SeamCrossingHandler` picks one. The player path has a unit test of `plan` but no GameTest yet. |
| Hysteresis: mid-band, or a cooldown | A 3-tick cooldown (Task 4), as in E2. The mid-band teleport comes with M1e-2's bands. |
| Pre-load the twin region | An expiring ticket on the far side for players within 64 blocks (Task 4). Getting chunks to the client *before* the crossing needs M1e-2's bands. |
| Corners: no-man's land | Bounce back (Tasks 3–5) |
| Tracking re-check after a seam teleport | Not done: documented above, for M2 |
