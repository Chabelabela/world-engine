# M1c Planet Terrain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deterministic planet terrain (height, climate, biome), sampled on the sphere and keyed by seed, behind a stateless, thread-safe, batched call that Minecraft's world generation can use from any thread. Includes the M1a final review's follow-ups.

**Architecture:**
- `we-frames` switches its projection to the pure-Rust `libm` crate, so cube-sphere directions are bit-identical on every OS, and pins that with a golden hash. Its API is also tightened for FFI: face indexes, `PlanetLayout` built from a `CubeSphere`, and an `Option`-returning `cell_of`.
- A new crate, `we-planet`, builds terrain from integer hashing and IEEE arithmetic only:
  - stateless gradient noise and fractal sums
  - a continents-and-mountains height model with latitude/altitude climate and 13 biomes
  - deep ocean forced near the 8 cube corners, where seam bands collide (M1e)
  - `fill_columns`, a validated batch over a face region
- `we-ffi` exports `we_terrain_columns`, which takes no handle (ABI v2).
- Java gets `NativeCore.terrainColumns`, which is thread-safe, plus `PlanetKind`/`PlanetBiome`/`TerrainColumns` and a `/worldengine terrain` debug command. M1d's chunk generator builds on this.

**Tech Stack:** Rust stable (edition 2024), `glam` 0.34, `libm` 0.2.16 (new) · Java 25 FFM · Minecraft 26.3 + Fabric · Brigadier

**What has been checked before execution (2026-11-20):**
- ✅ **Rust:** built in a scratch worktree of `b54fb9a`.
  - `cargo test --workspace` passes: `we-frames` 44, `we-planet` 21, `we-orbit` 20, `we-ffi` 16 unit + 2 integration.
  - clippy (`-D warnings`) and fmt are clean.
- ✅ **Determinism is a real problem, not a theoretical one.** With the platform's `f64::tan`/`atan` (glibc), the projection hash is `0xa7b230aece4e263d`; with `libm` it is `0x88c6ce59944224d1`. The two disagree in the last bits.
- ✅ **Terrain (seed 42):**
  - heights −300 to +520 m; 55% land; all 12 terrestrial biomes appear
  - the worst height step across any seam is 0.87 m
  - one 16×16 chunk takes **0.23 ms** in a release build
- ✅ **Java bridge** (`terrainColumns`, the ABI v2 check, the enums) was run on JDK 21 preview FFM against the built library:
  - batch = single columns
  - a cube corner is deep ocean, and the moon is barren
  - seeds differ, and invalid input is rejected
  - 16 concurrent calls on 8 threads agree
- ⚠️ **Not run locally:** `WorldEngineMod` and the JUnit files. They need JDK 25 and Minecraft, so CI verifies them in Task 11. **CI is also where the golden hashes meet Windows, macOS and Linux arm64 for the first time.**

**Environment note:** without JDK 25 (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 11). Don't download JDKs. Rust steps always run locally.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `core/Cargo.toml` | Modify | `libm` and `we-planet` workspace entries |
| `core/crates/we-frames/Cargo.toml` | Modify | Depend on `libm` |
| `core/crates/we-frames/src/cube_sphere.rs` | Modify | `libm` projection, golden test, `cell_of` → `Option` |
| `core/crates/we-frames/src/face.rs` | Modify | `Face::index` / `from_index`; discriminants frozen as ABI |
| `core/crates/we-frames/src/layout.rs` | Replace | `PlanetLayout::new(origin_x, origin_z, sphere)` with private fields |
| `core/crates/we-planet/` (`Cargo.toml`, `src/lib.rs`) | Create | Crate root and determinism rules |
| `core/crates/we-planet/src/hash.rs` | Create | `mix`, `hash3`, `layer_seed` |
| `core/crates/we-planet/src/noise.rs` | Create | `gradient` noise, `fbm` |
| `core/crates/we-planet/src/terrain.rs` | Create | `PlanetKind`, `Biome`, `Column`, `Terrain` |
| `core/crates/we-planet/src/columns.rs` | Create | `fill_columns`, `ColumnsError`, `MAX_COLUMNS` |
| `core/crates/we-ffi/Cargo.toml`, `src/lib.rs` | Modify | ABI v2; the `terrain` module |
| `core/crates/we-ffi/src/terrain.rs` | Create | `we_terrain_columns` |
| `core/crates/we-ffi/tests/abi.rs` | Replace | Adds the terrain call sequence |
| `mod/src/main/java/.../nativebridge/{PlanetKind,PlanetBiome,TerrainColumns}.java` | Create | Java views of the ABI enums and the result |
| `mod/src/main/java/.../nativebridge/NativeCore.java` | Replace | ABI v2 check before symbol lookup; `terrainColumns` |
| `mod/src/test/java/.../nativebridge/NativeTerrainTest.java` | Create | JUnit for the terrain bridge |
| `mod/src/main/java/.../WorldEngineMod.java` | Replace | `/worldengine terrain <face> <x> <z>` |

(`...` = `io/github/chabelabela/worldengine`)

---

### Task 1: Bit-identical projection on every platform (`we-frames`)

**Files:**
- Modify: `core/Cargo.toml`, `core/crates/we-frames/Cargo.toml`, `core/crates/we-frames/src/cube_sphere.rs`

- [ ] **Step 1: Write the failing golden test.** In `cube_sphere.rs`'s test module, add this constant right after `const N: u32 = 16_384;`:

```rust
    const PROJECTION_GOLDEN: u64 = 0x88c6_ce59_9442_24d1;
```

and this test right after the test `cell_of_is_half_open_with_the_far_edge_in_the_last_cell`:

```rust
    /// FNV-1a over the exact bits of many projections. A failure on one
    /// platform only means its maths library differs: the projection must
    /// use `libm` (pure Rust), never `f64::tan` / `f64::atan`.
    #[test]
    fn projection_is_bit_identical_on_every_platform() {
        let s = sphere();
        let mut hash = 0xcbf2_9ce4_8422_2325_u64;
        for face in Face::ALL {
            for i in 0..32 {
                for j in 0..32 {
                    let x = f64::from(i) * 512.0 + 0.5 + f64::from(j) * 0.013;
                    let z = f64::from(j) * 512.0 + 0.5 + f64::from(i) * 0.029;
                    let d = s.direction(face, x, z);
                    let (_, gx, gz) = s.grid_of(d);
                    for v in [d.x, d.y, d.z, gx, gz] {
                        for byte in v.to_bits().to_le_bytes() {
                            hash ^= u64::from(byte);
                            hash = hash.wrapping_mul(0x0100_0000_01b3);
                        }
                    }
                }
            }
        }
        assert_eq!(hash, PROJECTION_GOLDEN, "got {hash:#018x}");
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd core && cargo test -p we-frames projection_is_bit_identical`
Expected: FAIL with `got 0xa7b230aece4e263d` on Linux/glibc (other platforms print other values). This is the platform maths library differing from `libm`.

- [ ] **Step 3: Add the `libm` dependency.**
- In `core/Cargo.toml`, add this line under `[workspace.dependencies]`, right after `glam = "0.34"`:

```toml
libm = "0.2.16"
```

- In `core/crates/we-frames/Cargo.toml`, add this line under `[dependencies]`, right after `glam.workspace = true`:

```toml
libm.workspace = true
```

- [ ] **Step 4: Use `libm` in the projection.** In `cube_sphere.rs`, `fn tangents`, replace

```rust
        ((a * FRAC_PI_4).tan(), (b * FRAC_PI_4).tan())
```

with

```rust
        // libm (pure Rust), not f64::tan: platform maths libraries differ in
        // the last bit, and terrain must be identical on every OS.
        (libm::tan(a * FRAC_PI_4), libm::tan(b * FRAC_PI_4))
```

and in `fn grid_of`, replace

```rust
        let a = (dir.dot(face.east()) / along).atan() / FRAC_PI_4;
        let b = (dir.dot(face.south()) / along).atan() / FRAC_PI_4;
```

with

```rust
        let a = libm::atan(dir.dot(face.east()) / along) / FRAC_PI_4;
        let b = libm::atan(dir.dot(face.south()) / along) / FRAC_PI_4;
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-frames`
Expected: `test result: ok. 42 passed`

- [ ] **Step 6: Commit**

```bash
git add core/Cargo.toml core/Cargo.lock core/crates/we-frames
git commit -m "fix(frames): bit-identical projection on every platform via libm"
```

---

### Task 2: Face indexes are ABI (`we-frames`)

**Files:**
- Modify: `core/crates/we-frames/src/face.rs`

- [ ] **Step 1: Write the failing test.** Add this test as the last test in `face.rs`'s test module:

```rust
    #[test]
    fn indexes_round_trip_and_match_the_abi_numbering() {
        for (i, face) in Face::ALL.into_iter().enumerate() {
            assert_eq!(usize::from(face.index()), i);
            assert_eq!(Face::from_index(face.index()), Some(face));
        }
        assert_eq!(Face::North.index(), 4);
        assert_eq!(Face::from_index(6), None);
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `no method named index found for enum Face`.

- [ ] **Step 3: Write the implementation.** In the doc comment above `pub enum Face`, after the line `/// axis) and `South` is −Y.`, add:

```rust
///
/// The discriminants 0–5 cross the C ABI (`we_terrain_columns` and later
/// calls), so they are frozen: changing them requires bumping
/// `WE_ABI_VERSION`.
```

Then, inside `impl Face`, insert right before `/// Outward normal in the planet body frame.`:

```rust
    /// The face's C ABI index, 0–5.
    pub fn index(self) -> u8 {
        self as u8
    }

    /// The face with C ABI index `index`, or `None` if out of range.
    pub fn from_index(index: u8) -> Option<Face> {
        Face::ALL.get(usize::from(index)).copied()
    }

```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-frames`
Expected: `test result: ok. 43 passed`

- [ ] **Step 5: Commit**

```bash
git add core/crates/we-frames
git commit -m "feat(frames): face indexes, frozen as part of the C ABI"
```

---

### Task 3: One source of truth for face size; `cell_of` without debug panics (`we-frames`)

**Files:**
- Replace: `core/crates/we-frames/src/layout.rs`
- Modify: `core/crates/we-frames/src/cube_sphere.rs`

- [ ] **Step 1: Write the failing tests.** In `cube_sphere.rs`, replace the four `assert_eq!` lines of `cell_of_is_half_open_with_the_far_edge_in_the_last_cell` with:

```rust
        assert_eq!(s.cell_of(point(Face::E0, 0.0, 0.0, 0.0)), Some((0, 0)));
        assert_eq!(s.cell_of(point(Face::E0, n, 0.0, n)), Some((last, last)));
        assert_eq!(s.cell_of(point(Face::E0, 1.0, 0.0, 2.999)), Some((1, 2)));
        assert_eq!(
            s.cell_of(point(Face::E0, n - 1e-9, 0.0, 0.5)),
            Some((last, 0))
        );
```

and add this test right after that test:

```rust
    #[test]
    fn cell_of_rejects_points_off_the_face() {
        let s = sphere();
        let n = f64::from(N);
        assert_eq!(s.cell_of(point(Face::E0, -0.5, 0.0, 3.0)), None);
        assert_eq!(s.cell_of(point(Face::E0, 3.0, 0.0, n + 0.5)), None);
        assert_eq!(s.cell_of(point(Face::E0, f64::NAN, 0.0, 3.0)), None);
    }
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `expected (i64, i64), found Option<_>`.

- [ ] **Step 3: Make `cell_of` return `Option`.** In `cube_sphere.rs`, replace

```rust
    /// Points beyond an edge must be folded first ([`Self::fold`]).
    pub fn cell_of(self, p: SurfacePoint) -> (i64, i64) {
        let n = f64::from(self.cells);
        // Small tolerance: grid_of may overshoot a seam by rounding error.
        debug_assert!(
            (-1e-6..=n + 1e-6).contains(&p.x) && (-1e-6..=n + 1e-6).contains(&p.z),
            "point outside its face: {p:?}"
        );
        let last = i64::from(self.cells) - 1;
        let cell = |v: f64| (v.floor() as i64).clamp(0, last);
        (cell(p.x), cell(p.z))
    }
```

with

```rust
    /// Points beyond an edge must be folded first ([`Self::fold`]); for them,
    /// and for non-finite coordinates, this returns `None`.
    pub fn cell_of(self, p: SurfacePoint) -> Option<(i64, i64)> {
        let n = f64::from(self.cells);
        // Small tolerance: grid_of may overshoot a seam by rounding error.
        let on_face = |v: f64| (-1e-6..=n + 1e-6).contains(&v);
        if !on_face(p.x) || !on_face(p.z) {
            return None;
        }
        let last = i64::from(self.cells) - 1;
        let cell = |v: f64| (v.floor() as i64).clamp(0, last);
        Some((cell(p.x), cell(p.z)))
    }
```

- [ ] **Step 4: Build `PlanetLayout` from a `CubeSphere`.** Replace `core/crates/we-frames/src/layout.rs` with:

````rust
use crate::cube_sphere::CubeSphere;
use crate::face::Face;

/// Where a planet's six faces sit in a Minecraft level, as a cross-shaped
/// cube net: the equatorial faces side by side along +x (so walking east
/// around the equator is ordinary walking until the wrap from E3 back to E0),
/// with `North` directly north of `E1` and `South` directly south of it.
///
/// ```text
///            [North]
///   [E0] [E1] [E2] [E3]
///            [South]
/// ```
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PlanetLayout {
    origin_x: i64,
    origin_z: i64,
    cells: u32,
}

impl PlanetLayout {
    /// Place `sphere`'s faces with E0's north-west corner at level block
    /// (`origin_x`, `origin_z`). The face size always comes from the sphere,
    /// so the layout and the projection can't disagree.
    pub fn new(origin_x: i64, origin_z: i64, sphere: CubeSphere) -> Self {
        Self {
            origin_x,
            origin_z,
            cells: sphere.cells(),
        }
    }

    /// Level block x of E0's north-west corner.
    pub fn origin_x(self) -> i64 {
        self.origin_x
    }

    /// Level block z of E0's north-west corner.
    pub fn origin_z(self) -> i64 {
        self.origin_z
    }

    /// Blocks per face edge.
    pub fn cells(self) -> u32 {
        self.cells
    }

    fn offset(self, face: Face) -> (i64, i64) {
        let n = i64::from(self.cells);
        match face {
            Face::E0 => (0, 0),
            Face::E1 => (n, 0),
            Face::E2 => (2 * n, 0),
            Face::E3 => (3 * n, 0),
            Face::North => (n, -n),
            Face::South => (n, n),
        }
    }

    /// Level coordinates of grid position (`x`, `z`) on `face`.
    pub fn to_level(self, face: Face, x: f64, z: f64) -> (f64, f64) {
        let (ox, oz) = self.offset(face);
        (
            (self.origin_x + ox) as f64 + x,
            (self.origin_z + oz) as f64 + z,
        )
    }

    /// The face and grid position at level coordinates (`lx`, `lz`), or
    /// `None` outside every face.
    pub fn from_level(self, lx: f64, lz: f64) -> Option<(Face, f64, f64)> {
        if !(lx.is_finite() && lz.is_finite()) {
            return None;
        }
        let n = f64::from(self.cells);
        let rx = lx - self.origin_x as f64;
        let rz = lz - self.origin_z as f64;
        let column = (rx / n).floor();
        let row = (rz / n).floor();
        let face = match (row as i64, column as i64) {
            (0, 0) => Face::E0,
            (0, 1) => Face::E1,
            (0, 2) => Face::E2,
            (0, 3) => Face::E3,
            (-1, 1) => Face::North,
            (1, 1) => Face::South,
            _ => return None,
        };
        Some((face, rx - column * n, rz - row * n))
    }

    /// The face and grid cell of level block (`bx`, `bz`), or `None`.
    pub fn block_to_cell(self, bx: i64, bz: i64) -> Option<(Face, u32, u32)> {
        let (face, x, z) = self.from_level(bx as f64 + 0.5, bz as f64 + 0.5)?;
        Some((face, x as u32, z as u32))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cube_sphere::{CubeSphere, SurfacePoint};
    use crate::face::QuarterTurns;

    const N: u32 = 16_384;

    fn layout() -> PlanetLayout {
        PlanetLayout::new(-40_000, 1_000_000, CubeSphere::new(N))
    }

    #[test]
    fn level_round_trip() {
        let l = layout();
        for face in Face::ALL {
            for (x, z) in [(0.0, 0.0), (0.5, 16_383.5), (9_000.25, 77.75)] {
                let (lx, lz) = l.to_level(face, x, z);
                assert_eq!(l.from_level(lx, lz), Some((face, x, z)));
            }
        }
    }

    #[test]
    fn outside_the_net_is_no_face() {
        let l = layout();
        let n = f64::from(N);
        let (ox, oz) = (l.origin_x as f64, l.origin_z as f64);
        for (rx, rz) in [
            (-1.0, 5.0),
            (4.0 * n + 1.0, 5.0),
            (5.0, -5.0),
            (2.5 * n, n + 5.0),
        ] {
            assert_eq!(l.from_level(ox + rx, oz + rz), None);
        }
    }

    #[test]
    fn non_finite_level_coordinates_are_no_face() {
        let l = layout();
        assert_eq!(l.from_level(f64::NAN, 0.0), None);
        assert_eq!(l.from_level(0.0, f64::INFINITY), None);
        assert_eq!(l.from_level(f64::NEG_INFINITY, f64::NAN), None);
    }

    #[test]
    fn blocks_map_to_cells() {
        let l = layout();
        let n = i64::from(N);
        assert_eq!(
            l.block_to_cell(l.origin_x, l.origin_z),
            Some((Face::E0, 0, 0))
        );
        assert_eq!(
            l.block_to_cell(l.origin_x + n + 3, l.origin_z - 1),
            Some((Face::North, 3, N - 1))
        );
        assert_eq!(l.block_to_cell(l.origin_x - 1, l.origin_z), None);
    }

    #[test]
    fn contiguous_edges_agree_with_fold() {
        // Wherever two faces touch in the level, stepping across that boundary
        // must land exactly where folding the cube would put you, unrotated.
        let l = layout();
        let s = CubeSphere::new(N);
        let n = f64::from(N);
        let crossings = [
            (Face::E0, n + 0.5, 4_321.5),
            (Face::E1, n + 0.5, 4_321.5),
            (Face::E2, n + 0.5, 4_321.5),
            (Face::E1, 4_321.5, -0.5),
            (Face::E1, 4_321.5, n + 0.5),
            (Face::North, 4_321.5, n + 0.5),
            (Face::South, 4_321.5, -0.5),
        ];
        for (face, x, z) in crossings {
            let (lx, lz) = l.to_level(face, x, z);
            let in_level = l.from_level(lx, lz).expect("neighbour face in the net");
            let folded = s
                .fold(SurfacePoint { face, x, y: 0.0, z })
                .expect("beyond one edge");
            assert_eq!(folded.turns, QuarterTurns::NONE, "{face:?}");
            assert_eq!(
                in_level,
                (folded.point.face, folded.point.x, folded.point.z),
                "{face:?} {x},{z}"
            );
        }
    }

    #[test]
    fn equator_wraps_by_pure_translation() {
        // East of E3 the net ends; the fold lands on E0 at the same z, x
        // shifted by four faces.
        let l = layout();
        let s = CubeSphere::new(N);
        let n = f64::from(N);
        let folded = s
            .fold(SurfacePoint {
                face: Face::E3,
                x: n + 2.5,
                y: 0.0,
                z: 777.5,
            })
            .unwrap();
        assert_eq!(folded.turns, QuarterTurns::NONE);
        let (lx, lz) = l.to_level(Face::E3, n + 2.5, 777.5);
        let (wx, wz) = l.to_level(folded.point.face, folded.point.x, folded.point.z);
        assert_eq!((lx - wx, lz - wz), (4.0 * n, 0.0));
    }
}
````

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo fmt --all --check && cargo clippy -p we-frames --all-targets -- -D warnings && cargo test -p we-frames`
Expected: clean, `test result: ok. 44 passed`

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-frames
git commit -m "refactor(frames): PlanetLayout from CubeSphere; cell_of returns Option"
```

---

### Task 4: `we-planet` crate and its hashing

**Files:**
- Modify: `core/Cargo.toml`
- Create: `core/crates/we-planet/Cargo.toml`, `core/crates/we-planet/src/lib.rs`, `core/crates/we-planet/src/hash.rs`

- [ ] **Step 1: Register the crate.** Replace `core/Cargo.toml` with:

```toml
[workspace]
resolver = "3"
members = ["crates/we-orbit", "crates/we-frames", "crates/we-planet", "crates/we-ffi"]

[workspace.package]
version = "0.1.0"
edition = "2024"
rust-version = "1.85"

[workspace.dependencies]
glam = "0.34"
libm = "0.2.16"
proptest = "1.12"
we-orbit = { path = "crates/we-orbit" }
we-frames = { path = "crates/we-frames" }
we-planet = { path = "crates/we-planet" }

[profile.release]
# Keep the default panic = "unwind": we-ffi's guard relies on catching panics.
debug = "line-tables-only"
```

- [ ] **Step 2: Create `core/crates/we-planet/Cargo.toml`**

```toml
[package]
name = "we-planet"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
description = "Deterministic planet terrain and climate for World Engine"

[dependencies]
glam.workspace = true
we-frames.workspace = true
```

- [ ] **Step 3: Create `core/crates/we-planet/src/lib.rs` (stage 1)**

```rust
//! Deterministic planet terrain: height, climate and biome as functions on
//! the sphere, keyed by a seed.
//!
//! - Everything is computed from integer hashing and IEEE arithmetic (plus
//!   `libm` inside `we-frames`), so a seed gives bit-identical terrain on
//!   every platform. Never use `f64::sin`/`tan`/`exp` here: platform maths
//!   libraries differ in the last bit.
//! - Terrain is sampled on the sphere, so it matches across cube seams
//!   without any stitching.
//! - There is no state: [`Terrain`] is `Copy` and every function is pure, so
//!   world generation may call it from any number of threads.

mod hash;

pub use hash::{hash3, layer_seed, mix};
```

- [ ] **Step 4: Write the failing tests.** Create `core/crates/we-planet/src/hash.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hashes_are_stable_values() {
        // Pinned: changing the hash changes every world generated so far.
        assert_eq!(mix(0), 0);
        assert_eq!(mix(1), 0x5692_161d_100b_05e5);
        assert_eq!(hash3(42, 1, -2, 3), hash3(42, 1, -2, 3));
    }

    #[test]
    fn nearby_points_and_seeds_hash_differently() {
        let mut seen = std::collections::HashSet::new();
        for i in -20..20 {
            for j in -20..20 {
                for k in -5..5 {
                    assert!(seen.insert(hash3(7, i, j, k)), "collision at {i},{j},{k}");
                }
            }
        }
        assert_ne!(hash3(1, 0, 0, 0), hash3(2, 0, 0, 0));
        assert_ne!(layer_seed(1, 0), layer_seed(1, 1));
    }

    #[test]
    fn low_bits_are_balanced() {
        let ones = (0..20_000)
            .filter(|&i| hash3(99, i, i / 7, -i) & 1 == 1)
            .count();
        let share = ones as f64 / 20_000.0;
        assert!((0.48..0.52).contains(&share), "share of ones {share}");
    }
}
```

- [ ] **Step 5: Run them to confirm they fail**

Run: `cd core && cargo test -p we-planet`
Expected: FAIL to compile: `cannot find function mix` (and `hash3`, `layer_seed`).

- [ ] **Step 6: Write the implementation.** Insert this at the top of `hash.rs`, above `#[cfg(test)]`:

```rust
//! Integer hashing: the only source of randomness in terrain.

/// SplitMix64's finaliser: a fast, well-mixed permutation of 64-bit values.
pub fn mix(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}

/// Hash of the integer lattice point (`i`, `j`, `k`) under `seed`.
pub fn hash3(seed: u64, i: i64, j: i64, k: i64) -> u64 {
    let mut h = mix(seed);
    for v in [i, j, k] {
        h = mix(h ^ (v as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15));
    }
    h
}

/// Seed of one independent noise layer, derived from a planet seed.
pub fn layer_seed(seed: u64, layer: u64) -> u64 {
    mix(seed ^ mix(layer.wrapping_add(0x632b_e59b_d9b4_e019)))
}
```

- [ ] **Step 7: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-planet`
Expected: `test result: ok. 3 passed`

- [ ] **Step 8: Commit**

```bash
git add core/Cargo.toml core/Cargo.lock core/crates/we-planet
git commit -m "feat(planet): we-planet crate with deterministic integer hashing"
```

---

### Task 5: Gradient noise (`we-planet::noise`)

**Files:**
- Create: `core/crates/we-planet/src/noise.rs`
- Modify: `core/crates/we-planet/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `lib.rs` with (stage 2):

```rust
//! Deterministic planet terrain: height, climate and biome as functions on
//! the sphere, keyed by a seed.
//!
//! - Everything is computed from integer hashing and IEEE arithmetic (plus
//!   `libm` inside `we-frames`), so a seed gives bit-identical terrain on
//!   every platform. Never use `f64::sin`/`tan`/`exp` here: platform maths
//!   libraries differ in the last bit.
//! - Terrain is sampled on the sphere, so it matches across cube seams
//!   without any stitching.
//! - There is no state: [`Terrain`] is `Copy` and every function is pure, so
//!   world generation may call it from any number of threads.

mod hash;
mod noise;

pub use hash::{hash3, layer_seed, mix};
pub use noise::{fbm, gradient};
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-planet/src/noise.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn samples() -> impl Iterator<Item = DVec3> {
        (0..20_000).map(|i| {
            let t = f64::from(i);
            DVec3::new(
                t * 0.137 - 900.0,
                (t * 0.731).rem_euclid(97.0) - 40.0,
                t * 0.053,
            )
        })
    }

    #[test]
    fn zero_at_lattice_points() {
        for i in -5..5 {
            for j in -5..5 {
                let p = DVec3::new(f64::from(i), f64::from(j), 3.0);
                assert_eq!(gradient(11, p), 0.0);
            }
        }
    }

    #[test]
    fn stays_in_range_and_is_not_flat() {
        let (mut lo, mut hi) = (f64::MAX, f64::MIN);
        for p in samples() {
            let v = gradient(5, p);
            lo = lo.min(v);
            hi = hi.max(v);
        }
        assert!(lo >= -1.1 && hi <= 1.1, "range {lo}..{hi}");
        assert!(lo < -0.5 && hi > 0.5, "too flat: {lo}..{hi}");
    }

    #[test]
    fn is_continuous() {
        // Gradient noise is Lipschitz: tiny steps give tiny changes.
        for p in samples().take(2_000) {
            let step = DVec3::new(1e-4, -2e-4, 1e-4);
            let change = (gradient(5, p + step) - gradient(5, p)).abs();
            assert!(change < 4.0 * step.length(), "jump of {change} at {p:?}");
        }
    }

    #[test]
    fn seeds_give_different_fields() {
        let differ = samples()
            .take(100)
            .filter(|&p| gradient(1, p) != gradient(2, p))
            .count();
        assert!(differ > 90);
    }

    #[test]
    fn fbm_is_normalised() {
        assert_eq!(fbm(3, DVec3::splat(0.4), 0), 0.0);
        for p in samples().take(5_000) {
            let v = fbm(3, p * 0.1, 5);
            assert!((-1.1..=1.1).contains(&v), "fbm {v} at {p:?}");
        }
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-planet`
Expected: FAIL to compile: `cannot find function gradient` and `fbm`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `noise.rs`, above `#[cfg(test)]`:

```rust
use crate::hash::{hash3, layer_seed};
use glam::DVec3;

/// Edge midpoints of a cube: the classic Perlin gradient set.
const GRADIENTS: [DVec3; 12] = [
    DVec3::new(1.0, 1.0, 0.0),
    DVec3::new(-1.0, 1.0, 0.0),
    DVec3::new(1.0, -1.0, 0.0),
    DVec3::new(-1.0, -1.0, 0.0),
    DVec3::new(1.0, 0.0, 1.0),
    DVec3::new(-1.0, 0.0, 1.0),
    DVec3::new(1.0, 0.0, -1.0),
    DVec3::new(-1.0, 0.0, -1.0),
    DVec3::new(0.0, 1.0, 1.0),
    DVec3::new(0.0, -1.0, 1.0),
    DVec3::new(0.0, 1.0, -1.0),
    DVec3::new(0.0, -1.0, -1.0),
];

fn fade(t: f64) -> f64 {
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}

fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + t * (b - a)
}

/// Perlin-style gradient noise at `p`: smooth, roughly in [-1, 1], and zero at
/// every integer lattice point. Gradients come from hashing, so there are no
/// tables and no state.
pub fn gradient(seed: u64, p: DVec3) -> f64 {
    let base = p.floor();
    let f = p - base;
    let (i, j, k) = (base.x as i64, base.y as i64, base.z as i64);
    let corner = |di: i64, dj: i64, dk: i64| {
        let h = hash3(
            seed,
            i.wrapping_add(di),
            j.wrapping_add(dj),
            k.wrapping_add(dk),
        );
        let g = GRADIENTS[(h % 12) as usize];
        g.dot(f - DVec3::new(di as f64, dj as f64, dk as f64))
    };
    let (u, v, w) = (fade(f.x), fade(f.y), fade(f.z));
    lerp(
        lerp(
            lerp(corner(0, 0, 0), corner(1, 0, 0), u),
            lerp(corner(0, 1, 0), corner(1, 1, 0), u),
            v,
        ),
        lerp(
            lerp(corner(0, 0, 1), corner(1, 0, 1), u),
            lerp(corner(0, 1, 1), corner(1, 1, 1), u),
            v,
        ),
        w,
    )
}

/// Fractal sum of `octaves` layers of [`gradient`] noise, each with half the
/// amplitude and twice the frequency of the last, normalised to roughly
/// [-1, 1]. Zero octaves gives 0.
pub fn fbm(seed: u64, p: DVec3, octaves: u32) -> f64 {
    let (mut sum, mut amplitude, mut frequency, mut norm) = (0.0, 1.0, 1.0, 0.0);
    for octave in 0..octaves {
        sum += amplitude * gradient(layer_seed(seed, u64::from(octave)), p * frequency);
        norm += amplitude;
        amplitude *= 0.5;
        frequency *= 2.0;
    }
    if norm > 0.0 { sum / norm } else { 0.0 }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-planet`
Expected: `test result: ok. 8 passed`

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-planet
git commit -m "feat(planet): stateless gradient noise and fractal sums"
```

---

### Task 6: The terrain model (`we-planet::terrain`)

**Files:**
- Create: `core/crates/we-planet/src/terrain.rs`
- Modify: `core/crates/we-planet/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `lib.rs` with (stage 3):

```rust
//! Deterministic planet terrain: height, climate and biome as functions on
//! the sphere, keyed by a seed.
//!
//! - Everything is computed from integer hashing and IEEE arithmetic (plus
//!   `libm` inside `we-frames`), so a seed gives bit-identical terrain on
//!   every platform. Never use `f64::sin`/`tan`/`exp` here: platform maths
//!   libraries differ in the last bit.
//! - Terrain is sampled on the sphere, so it matches across cube seams
//!   without any stitching.
//! - There is no state: [`Terrain`] is `Copy` and every function is pure, so
//!   world generation may call it from any number of threads.

mod hash;
mod noise;
mod terrain;

pub use hash::{hash3, layer_seed, mix};
pub use noise::{fbm, gradient};
pub use terrain::{Biome, Column, PlanetKind, Terrain};
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-planet/src/terrain.rs` containing only the test module below. `TERRAIN_GOLDEN` pins the exact bits of seed 42 on both planet kinds, so any change to the model or its maths shows up as a failure on purpose.

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use we_frames::{Face, SurfacePoint};

    const SEED: u64 = 42;
    const TERRAIN_GOLDEN: u64 = 0xc03c_eaa0_22c8_43dc;

    fn home() -> (CubeSphere, Terrain) {
        let sphere = CubeSphere::new(16_384);
        (sphere, Terrain::new(SEED, PlanetKind::Terrestrial, sphere))
    }

    /// Directions spread over the whole planet: a 64 × 64 grid on every face.
    fn planet_samples(sphere: CubeSphere) -> impl Iterator<Item = DVec3> {
        let n = f64::from(sphere.cells());
        Face::ALL.into_iter().flat_map(move |face| {
            (0..64).flat_map(move |i| {
                (0..64).map(move |j| {
                    let x = (f64::from(i) + 0.5) / 64.0 * n;
                    let z = (f64::from(j) + 0.5) / 64.0 * n;
                    sphere.direction(face, x, z)
                })
            })
        })
    }

    #[test]
    fn same_seed_same_terrain_and_seeds_differ() {
        let (sphere, terrain) = home();
        let other = Terrain::new(SEED + 1, PlanetKind::Terrestrial, sphere);
        let mut differ = 0;
        for dir in planet_samples(sphere).take(500) {
            assert_eq!(terrain.sample(dir), terrain.sample(dir));
            if terrain.sample(dir).height != other.sample(dir).height {
                differ += 1;
            }
        }
        assert!(
            differ > 450,
            "only {differ} of 500 columns changed with the seed"
        );
    }

    #[test]
    fn cube_corners_are_deep_ocean() {
        let (_, terrain) = home();
        for sx in [-1.0, 1.0] {
            for sy in [-1.0, 1.0] {
                for sz in [-1.0, 1.0] {
                    let column = terrain.sample(DVec3::new(sx, sy, sz).normalize());
                    assert!(
                        column.height <= TERRESTRIAL_CORNER_FLOOR + 1e-9,
                        "{column:?}"
                    );
                    assert_eq!(column.biome, Biome::DeepOcean);
                }
            }
        }
    }

    #[test]
    fn corner_bias_fades_out_by_eight_degrees() {
        for face in Face::ALL {
            assert_eq!(corner_weight(face.normal()), 0.0);
        }
        // 10 degrees from the (1,1,1) corner, towards +X.
        let corner = DVec3::ONE.normalize();
        let away = (corner * 10f64.to_radians().cos()
            + DVec3::new(2.0, -1.0, -1.0).normalize() * 10f64.to_radians().sin())
        .normalize();
        assert_eq!(corner_weight(away), 0.0);
        assert_eq!(corner_weight(corner), 1.0);
    }

    #[test]
    fn poles_are_colder_than_the_equator() {
        let (_, terrain) = home();
        let mean_temperature = |sin_lat: f64| {
            let cos_lat = (1.0 - sin_lat * sin_lat).sqrt();
            let total: f64 = (0..360)
                .map(|k| {
                    let lon = f64::from(k).to_radians();
                    terrain
                        .sample(DVec3::new(
                            cos_lat * lon.cos(),
                            sin_lat,
                            cos_lat * lon.sin(),
                        ))
                        .temperature
                })
                .sum();
            total / 360.0
        };
        let equator = mean_temperature(0.0);
        let north = mean_temperature(0.98);
        let south = mean_temperature(-0.98);
        assert!(
            north < equator - 30.0 && south < equator - 30.0,
            "{equator} {north} {south}"
        );
    }

    #[test]
    fn heights_stay_in_range_with_land_and_sea() {
        let (sphere, terrain) = home();
        let mut land = 0;
        let mut total = 0;
        let mut biomes = std::collections::HashSet::new();
        for dir in planet_samples(sphere) {
            let column = terrain.sample(dir);
            assert!((-320.0..=600.0).contains(&column.height), "{column:?}");
            assert!((0.0..=1.0).contains(&column.moisture));
            if !column.biome.is_water() {
                land += 1;
            }
            total += 1;
            biomes.insert(column.biome);
        }
        let share = f64::from(land) / f64::from(total);
        assert!((0.25..=0.75).contains(&share), "land share {share}");
        assert!(
            biomes.len() >= 8,
            "only {} biomes: {biomes:?}",
            biomes.len()
        );
        assert!(!biomes.contains(&Biome::Barren));
    }

    #[test]
    fn terrain_is_continuous_across_seams() {
        // Sampling on the sphere means the last block inside an edge and the
        // first block past it (on the neighbouring face) are about 1 m apart.
        let (sphere, terrain) = home();
        let n = f64::from(sphere.cells());
        for face in Face::ALL {
            for k in 0..50 {
                let along = (f64::from(k) + 0.5) / 50.0 * n;
                for (inside, beyond) in [
                    ((n - 0.5, along), (n + 0.5, along)),
                    ((0.5, along), (-0.5, along)),
                    ((along, n - 0.5), (along, n + 0.5)),
                    ((along, 0.5), (along, -0.5)),
                ] {
                    let a = terrain.sample(sphere.direction(face, inside.0, inside.1));
                    let q = sphere
                        .fold(SurfacePoint {
                            face,
                            x: beyond.0,
                            y: 0.0,
                            z: beyond.1,
                        })
                        .expect("one cell beyond the edge")
                        .point;
                    let b = terrain.sample(sphere.direction(q.face, q.x, q.z));
                    let step = (a.height - b.height).abs();
                    assert!(step < 5.0, "{face:?} {beyond:?}: {step} m step");
                }
            }
        }
    }

    #[test]
    fn barren_worlds_have_no_water_and_crater_corners() {
        let sphere = CubeSphere::new(4_096);
        let moon = Terrain::new(SEED, PlanetKind::Barren, sphere);
        for dir in planet_samples(sphere) {
            let column = moon.sample(dir);
            assert_eq!(column.biome, Biome::Barren);
            assert!((-160.0..=150.0).contains(&column.height), "{column:?}");
        }
        let corner = moon.sample(DVec3::ONE.normalize());
        assert!(corner.height <= BARREN_CORNER_FLOOR + 1e-9);
    }

    #[test]
    fn height_blocks_round_down() {
        let column = |height| Column {
            height,
            temperature: 0.0,
            moisture: 0.0,
            biome: Biome::Plains,
        };
        assert_eq!(column(12.9).height_block(), 12);
        assert_eq!(column(-0.5).height_block(), -1);
        assert_eq!(column(-3.0).height_block(), -3);
    }

    #[test]
    fn indexes_round_trip_and_match_the_abi_numbering() {
        for (i, biome) in Biome::ALL.into_iter().enumerate() {
            assert_eq!(usize::from(biome.index()), i);
            assert_eq!(Biome::from_index(biome.index()), Some(biome));
        }
        assert_eq!(Biome::Barren.index(), 12);
        assert_eq!(Biome::from_index(13), None);
        assert_eq!(PlanetKind::from_index(1), Some(PlanetKind::Barren));
        assert_eq!(PlanetKind::from_index(2), None);
    }

    /// FNV-1a over the exact bits of many columns of both planet kinds. A
    /// failure on one platform only means some maths differs there; terrain
    /// must be bit-identical everywhere (client LOD vs server chunks, worlds
    /// moved between machines).
    #[test]
    fn terrain_is_bit_identical_on_every_platform() {
        let (sphere, terrain) = home();
        let moon = Terrain::new(SEED, PlanetKind::Barren, CubeSphere::new(4_096));
        let mut hash = 0xcbf2_9ce4_8422_2325_u64;
        let mut feed = |bits: u64| {
            for byte in bits.to_le_bytes() {
                hash ^= u64::from(byte);
                hash = hash.wrapping_mul(0x0100_0000_01b3);
            }
        };
        for dir in planet_samples(sphere).step_by(7) {
            for column in [terrain.sample(dir), moon.sample(dir)] {
                feed(column.height.to_bits());
                feed(column.temperature.to_bits());
                feed(column.moisture.to_bits());
                feed(u64::from(column.biome.index()));
            }
        }
        assert_eq!(hash, TERRAIN_GOLDEN, "got {hash:#018x}");
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-planet`
Expected: FAIL to compile: `cannot find type Terrain`, `PlanetKind`, `Biome` and `Column`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `terrain.rs`, above `#[cfg(test)]`:

```rust
use crate::hash::layer_seed;
use crate::noise::fbm;
use glam::DVec3;
use we_frames::CubeSphere;

/// What kind of world a planet is. The discriminants cross the C ABI, so
/// they are frozen: changing them requires bumping `WE_ABI_VERSION`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum PlanetKind {
    /// Oceans, climate bands and biomes (the home planet).
    Terrestrial = 0,
    /// Airless, waterless rolling regolith (the moon).
    Barren = 1,
}

impl PlanetKind {
    pub fn index(self) -> u8 {
        self as u8
    }

    pub fn from_index(index: u32) -> Option<Self> {
        match index {
            0 => Some(Self::Terrestrial),
            1 => Some(Self::Barren),
            _ => None,
        }
    }
}

/// Surface biome. The discriminants cross the C ABI (and match Java's
/// `PlanetBiome`), so they are frozen: changing them requires bumping
/// `WE_ABI_VERSION`. M1d maps each one to Minecraft biomes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Biome {
    DeepOcean = 0,
    Ocean = 1,
    Beach = 2,
    Plains = 3,
    Forest = 4,
    Taiga = 5,
    Tundra = 6,
    Desert = 7,
    Savanna = 8,
    Jungle = 9,
    Mountains = 10,
    SnowyPeaks = 11,
    Barren = 12,
}

impl Biome {
    pub const ALL: [Biome; 13] = [
        Biome::DeepOcean,
        Biome::Ocean,
        Biome::Beach,
        Biome::Plains,
        Biome::Forest,
        Biome::Taiga,
        Biome::Tundra,
        Biome::Desert,
        Biome::Savanna,
        Biome::Jungle,
        Biome::Mountains,
        Biome::SnowyPeaks,
        Biome::Barren,
    ];

    pub fn index(self) -> u8 {
        self as u8
    }

    pub fn from_index(index: u8) -> Option<Self> {
        Self::ALL.get(usize::from(index)).copied()
    }

    pub fn is_water(self) -> bool {
        matches!(self, Biome::DeepOcean | Biome::Ocean)
    }
}

/// One terrain column.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Column {
    /// Surface height in metres above (negative: below) the datum sphere,
    /// which is sea level on terrestrial planets.
    pub height: f64,
    /// Surface air temperature, °C.
    pub temperature: f64,
    /// 0 (arid) to 1 (wet).
    pub moisture: f64,
    pub biome: Biome,
}

impl Column {
    /// The surface as a whole-block offset from the datum: `floor(height)`.
    pub fn height_block(self) -> i32 {
        self.height.floor() as i32
    }
}

/// cos 4°: within this angle of a cube corner terrain is fully forced down.
const CORNER_INNER_COS: f64 = 0.997_564_050_259_824_2;
/// cos 8°: beyond this angle from every corner terrain is untouched.
const CORNER_OUTER_COS: f64 = 0.990_268_068_741_570_4;
/// Terrestrial terrain sinks to deep ocean at cube corners, where three seam
/// overlap bands meet (ROADMAP §4.3; M1e keeps those spots unbuildable).
pub const TERRESTRIAL_CORNER_FLOOR: f64 = -300.0;
/// Barren worlds get a deep crater floor at cube corners instead.
pub const BARREN_CORNER_FLOOR: f64 = -150.0;

fn smoothstep(edge0: f64, edge1: f64, x: f64) -> f64 {
    let t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + t * (b - a)
}

/// 1 within 4° of a cube corner, 0 beyond 8° from every corner.
fn corner_weight(dir: DVec3) -> f64 {
    // For a unit vector, the cosine of the angle to the nearest corner
    // direction (±1, ±1, ±1)/√3 is (|x| + |y| + |z|)/√3.
    let a = dir.abs();
    let nearest = (a.x + a.y + a.z) / 3.0_f64.sqrt();
    smoothstep(CORNER_OUTER_COS, CORNER_INNER_COS, nearest)
}

fn classify(height: f64, temperature: f64, moisture: f64) -> Biome {
    if height < -60.0 {
        Biome::DeepOcean
    } else if height < 0.0 {
        Biome::Ocean
    } else if height < 3.0 {
        Biome::Beach
    } else if height > 260.0 {
        if temperature < 0.0 {
            Biome::SnowyPeaks
        } else {
            Biome::Mountains
        }
    } else if temperature < -3.0 {
        Biome::Tundra
    } else if temperature < 8.0 {
        if moisture > 0.45 {
            Biome::Taiga
        } else {
            Biome::Tundra
        }
    } else if temperature < 22.0 {
        if moisture > 0.55 {
            Biome::Forest
        } else {
            Biome::Plains
        }
    } else if moisture < 0.3 {
        Biome::Desert
    } else if moisture < 0.55 {
        Biome::Savanna
    } else {
        Biome::Jungle
    }
}

/// Deterministic terrain for one planet: a seed, a kind, and the planet's
/// size (noise wavelengths are in metres on its datum sphere).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Terrain {
    seed: u64,
    kind: PlanetKind,
    radius: f64,
}

impl Terrain {
    pub fn new(seed: u64, kind: PlanetKind, sphere: CubeSphere) -> Self {
        Self {
            seed,
            kind,
            radius: sphere.radius(),
        }
    }

    pub fn seed(self) -> u64 {
        self.seed
    }

    pub fn kind(self) -> PlanetKind {
        self.kind
    }

    /// The column under unit direction `dir` (planet body frame; +Y is the
    /// spin axis, so `dir.y` is the sine of the latitude).
    pub fn sample(self, dir: DVec3) -> Column {
        let p = dir * self.radius;
        let corner = corner_weight(dir);
        match self.kind {
            PlanetKind::Terrestrial => self.terrestrial(dir, p, corner),
            PlanetKind::Barren => self.barren(p, corner),
        }
    }

    fn layer(self, layer: u64) -> u64 {
        layer_seed(self.seed, layer)
    }

    fn terrestrial(self, dir: DVec3, p: DVec3, corner: f64) -> Column {
        // Continents: one smooth large-scale field, continuous across the
        // coastline (both branches give -8 m at continent = 0).
        let continent = fbm(self.layer(1), p / 7_000.0, 5) * 1.6 + 0.1;
        let mut height = if continent < 0.0 {
            -8.0 + 220.0 * continent
        } else {
            -8.0 + 140.0 * continent
        };
        // Ridged mountains, only well inland.
        let ridge = 1.0 - fbm(self.layer(2), p / 2_200.0, 4).abs();
        height += smoothstep(0.2, 0.6, continent) * ridge * ridge * 420.0;
        // Small hills and dips everywhere.
        height += fbm(self.layer(3), p / 180.0, 3) * 10.0;
        height = lerp(height, TERRESTRIAL_CORNER_FLOOR, corner);

        let sin_latitude = dir.y;
        let temperature =
            28.0 - 50.0 * sin_latitude * sin_latitude - 18.0 * (height.max(0.0) / 1_000.0)
                + 5.0 * fbm(self.layer(4), p / 5_000.0, 2);
        let moisture = (0.5 + 0.6 * fbm(self.layer(5), p / 4_000.0, 3)).clamp(0.0, 1.0);
        Column {
            height,
            temperature,
            moisture,
            biome: classify(height, temperature, moisture),
        }
    }

    fn barren(self, p: DVec3, corner: f64) -> Column {
        let mut height =
            fbm(self.layer(1), p / 1_500.0, 5) * 90.0 + fbm(self.layer(3), p / 120.0, 3) * 12.0;
        height = lerp(height, BARREN_CORNER_FLOOR, corner);
        Column {
            height,
            temperature: -40.0,
            moisture: 0.0,
            biome: Biome::Barren,
        }
    }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-planet`
Expected: `test result: ok. 18 passed`. If `terrain_is_bit_identical_on_every_platform` fails while every other test passes, the code differs from this plan. Compare it character by character rather than updating the constant.

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-planet
git commit -m "feat(planet): terrain model with climate bands, biomes and corner oceans"
```

---

### Task 7: Batched columns (`we-planet::columns`)

**Files:**
- Create: `core/crates/we-planet/src/columns.rs`
- Modify: `core/crates/we-planet/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `lib.rs` with its final version:

```rust
//! Deterministic planet terrain: height, climate and biome as functions on
//! the sphere, keyed by a seed.
//!
//! - Everything is computed from integer hashing and IEEE arithmetic (plus
//!   `libm` inside `we-frames`), so a seed gives bit-identical terrain on
//!   every platform. Never use `f64::sin`/`tan`/`exp` here: platform maths
//!   libraries differ in the last bit.
//! - Terrain is sampled on the sphere, so it matches across cube seams
//!   without any stitching.
//! - There is no state: [`Terrain`] is `Copy` and every function is pure, so
//!   world generation may call it from any number of threads.

mod columns;
mod hash;
mod noise;
mod terrain;

pub use columns::{ColumnsError, MAX_COLUMNS, fill_columns};
pub use hash::{hash3, layer_seed, mix};
pub use noise::{fbm, gradient};
pub use terrain::{Biome, Column, PlanetKind, Terrain};
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-planet/src/columns.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::terrain::PlanetKind;

    fn home() -> (CubeSphere, Terrain) {
        let sphere = CubeSphere::new(16_384);
        (sphere, Terrain::new(42, PlanetKind::Terrestrial, sphere))
    }

    #[test]
    fn fills_row_major_cell_centres() {
        let (sphere, terrain) = home();
        let (x0, z0, w, h) = (8_000, 300, 5, 3);
        let mut heights = vec![0; 15];
        let mut biomes = vec![0; 15];
        fill_columns(
            sphere,
            terrain,
            Face::E2,
            x0,
            z0,
            w,
            h,
            &mut heights,
            &mut biomes,
        )
        .unwrap();
        for dz in 0..h {
            for dx in 0..w {
                let x = (x0 + i64::from(dx)) as f64 + 0.5;
                let z = (z0 + i64::from(dz)) as f64 + 0.5;
                let column = terrain.sample(sphere.direction(Face::E2, x, z));
                let i = (dz * w + dx) as usize;
                assert_eq!(heights[i], column.height_block());
                assert_eq!(biomes[i], column.biome.index());
            }
        }
    }

    #[test]
    fn rejects_bad_requests() {
        let (sphere, terrain) = home();
        let n = i64::from(sphere.cells());
        let mut heights = vec![0; 16];
        let mut biomes = vec![0; 16];
        let fill = |x0, z0, w, h, heights: &mut [i32], biomes: &mut [u8]| {
            fill_columns(sphere, terrain, Face::North, x0, z0, w, h, heights, biomes)
        };
        assert_eq!(
            fill(-1, 0, 4, 4, &mut heights, &mut biomes),
            Err(ColumnsError::OutsideFace)
        );
        assert_eq!(
            fill(n - 3, 0, 4, 4, &mut heights, &mut biomes),
            Err(ColumnsError::OutsideFace)
        );
        assert_eq!(
            fill(0, i64::MAX, 4, 4, &mut heights, &mut biomes),
            Err(ColumnsError::OutsideFace)
        );
        assert_eq!(
            fill(0, 0, 2048, 1024, &mut heights, &mut biomes),
            Err(ColumnsError::TooMany)
        );
        assert_eq!(
            fill(0, 0, 5, 4, &mut heights, &mut biomes),
            Err(ColumnsError::BufferTooSmall)
        );
        assert_eq!(fill(n - 4, n - 4, 4, 4, &mut heights, &mut biomes), Ok(()));
        assert_eq!(fill(0, 0, 0, 7, &mut [], &mut []), Ok(()));
    }

    #[test]
    fn concurrent_callers_get_identical_results() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<Terrain>();
        assert_send_sync::<CubeSphere>();
        let (sphere, terrain) = home();
        let region = move || {
            let mut heights = vec![0; 32 * 32];
            let mut biomes = vec![0; 32 * 32];
            fill_columns(
                sphere,
                terrain,
                Face::E0,
                100,
                100,
                32,
                32,
                &mut heights,
                &mut biomes,
            )
            .unwrap();
            (heights, biomes)
        };
        let expected = region();
        let threads: Vec<_> = (0..8).map(|_| std::thread::spawn(region)).collect();
        for thread in threads {
            assert_eq!(thread.join().unwrap(), expected);
        }
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-planet`
Expected: FAIL to compile: `cannot find function fill_columns`, `cannot find type ColumnsError`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `columns.rs`, above `#[cfg(test)]`:

```rust
use crate::terrain::Terrain;
use we_frames::{CubeSphere, Face};

/// Most columns one call may fill: a 1024 × 1024 region.
pub const MAX_COLUMNS: u64 = 1 << 20;

/// Why [`fill_columns`] refused a request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ColumnsError {
    /// Part of the region lies outside the face.
    OutsideFace,
    /// More than [`MAX_COLUMNS`] columns.
    TooMany,
    /// An output buffer is shorter than `count_x * count_z`.
    BufferTooSmall,
}

/// Fill a `count_x` × `count_z` region of columns whose north-west cell is
/// (`x0`, `z0`) on `face`. Each cell is sampled at its centre. Outputs are
/// row-major (index `dz * count_x + dx`): the surface as whole blocks above
/// the datum (`Column::height_block`) and the biome index.
///
/// Pure and thread-safe: call it from as many world-generation threads as
/// you like.
#[allow(clippy::too_many_arguments)]
pub fn fill_columns(
    sphere: CubeSphere,
    terrain: Terrain,
    face: Face,
    x0: i64,
    z0: i64,
    count_x: u32,
    count_z: u32,
    heights: &mut [i32],
    biomes: &mut [u8],
) -> Result<(), ColumnsError> {
    let total = u64::from(count_x) * u64::from(count_z);
    if total > MAX_COLUMNS {
        return Err(ColumnsError::TooMany);
    }
    let cells = i64::from(sphere.cells());
    let fits = |start: i64, count: u32| {
        start >= 0
            && start
                .checked_add(i64::from(count))
                .is_some_and(|end| end <= cells)
    };
    if !fits(x0, count_x) || !fits(z0, count_z) {
        return Err(ColumnsError::OutsideFace);
    }
    let total = total as usize;
    if heights.len() < total || biomes.len() < total {
        return Err(ColumnsError::BufferTooSmall);
    }
    let width = count_x as usize;
    for dz in 0..count_z {
        let z = (z0 + i64::from(dz)) as f64 + 0.5;
        for dx in 0..count_x {
            let x = (x0 + i64::from(dx)) as f64 + 0.5;
            let column = terrain.sample(sphere.direction(face, x, z));
            let index = dz as usize * width + dx as usize;
            heights[index] = column.height_block();
            biomes[index] = column.biome.index();
        }
    }
    Ok(())
}
```

- [ ] **Step 5: Run the full workspace checks**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
Expected: clean. `we_planet` 21 passed, `we_frames` 44.

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-planet
git commit -m "feat(planet): validated, thread-safe batch fill of terrain columns"
```

---

### Task 8: `we_terrain_columns` in the C ABI (v2)

**Files:**
- Create: `core/crates/we-ffi/src/terrain.rs`
- Modify: `core/crates/we-ffi/Cargo.toml`, `core/crates/we-ffi/src/lib.rs`
- Replace: `core/crates/we-ffi/tests/abi.rs`

- [ ] **Step 1: Write the failing tests.** Create `core/crates/we-ffi/src/terrain.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    const HOME_CELLS: u32 = 16_384;

    fn call(face: u32, x0: i32, z0: i32, w: u32, h: u32) -> (i32, Vec<i32>, Vec<u8>) {
        let n = (w * h) as usize;
        let mut heights = vec![0; n.max(1)];
        let mut biomes = vec![0; n.max(1)];
        let status = unsafe {
            we_terrain_columns(
                42,
                HOME_CELLS,
                0,
                face,
                x0,
                z0,
                w,
                h,
                heights.as_mut_ptr(),
                biomes.as_mut_ptr(),
            )
        };
        (status, heights, biomes)
    }

    #[test]
    fn matches_we_planet() {
        let (status, heights, biomes) = call(1, 4_000, 9_000, 16, 16);
        assert_eq!(status, WE_OK);
        let sphere = CubeSphere::new(HOME_CELLS);
        let terrain = Terrain::new(42, PlanetKind::Terrestrial, sphere);
        let mut expected_h = vec![0; 256];
        let mut expected_b = vec![0; 256];
        fill_columns(
            sphere,
            terrain,
            Face::E1,
            4_000,
            9_000,
            16,
            16,
            &mut expected_h,
            &mut expected_b,
        )
        .unwrap();
        assert_eq!(heights, expected_h);
        assert_eq!(biomes, expected_b);
    }

    #[test]
    fn rejects_invalid_requests() {
        assert_eq!(call(6, 0, 0, 4, 4).0, WE_ERR_INVALID, "face");
        assert_eq!(call(0, -1, 0, 4, 4).0, WE_ERR_INVALID, "outside");
        assert_eq!(call(0, 16_382, 0, 4, 4).0, WE_ERR_INVALID, "outside");
        let mut h = [0i32; 4];
        let mut b = [0u8; 4];
        let raw = |seed, cells, kind, w, h_: u32, hp: *mut i32, bp: *mut u8| unsafe {
            we_terrain_columns(seed, cells, kind, 0, 0, 0, w, h_, hp, bp)
        };
        assert_eq!(
            raw(1, 3, 0, 2, 2, h.as_mut_ptr(), b.as_mut_ptr()),
            WE_ERR_INVALID,
            "odd cells"
        );
        assert_eq!(
            raw(1, HOME_CELLS, 2, 2, 2, h.as_mut_ptr(), b.as_mut_ptr()),
            WE_ERR_INVALID,
            "kind"
        );
        assert_eq!(
            raw(1, HOME_CELLS, 0, 2048, 1024, h.as_mut_ptr(), b.as_mut_ptr()),
            WE_ERR_INVALID,
            "too many"
        );
        assert_eq!(
            raw(1, HOME_CELLS, 0, 2, 2, std::ptr::null_mut(), b.as_mut_ptr()),
            WE_ERR_NULL
        );
        assert_eq!(
            raw(1, HOME_CELLS, 0, 2, 2, h.as_mut_ptr(), std::ptr::null_mut()),
            WE_ERR_NULL
        );
        assert_eq!(
            raw(
                1,
                HOME_CELLS,
                0,
                0,
                5,
                std::ptr::null_mut(),
                std::ptr::null_mut()
            ),
            WE_OK,
            "empty"
        );
    }

    #[test]
    fn writes_through_unaligned_buffers() {
        let (_, expected, _) = call(4, 10, 20, 3, 2);
        let mut raw = [0u8; 6 * 4 + 1];
        let mut biomes = [0u8; 6];
        let out = raw.as_mut_ptr().wrapping_add(1).cast::<i32>();
        let status = unsafe {
            we_terrain_columns(42, HOME_CELLS, 0, 4, 10, 20, 3, 2, out, biomes.as_mut_ptr())
        };
        assert_eq!(status, WE_OK);
        let got: Vec<i32> = (0..6)
            .map(|i| unsafe { out.add(i).read_unaligned() })
            .collect();
        assert_eq!(got, expected);
    }

    #[test]
    fn is_safe_to_call_from_many_threads() {
        let expected = call(2, 300, 300, 32, 32);
        let threads: Vec<_> = (0..8)
            .map(|_| std::thread::spawn(|| call(2, 300, 300, 32, 32)))
            .collect();
        for thread in threads {
            assert_eq!(thread.join().unwrap(), expected);
        }
    }
}
```

and replace `core/crates/we-ffi/tests/abi.rs` with:

```rust
//! Drives the C ABI through the rlib in the same order the Java bridge does.

use worldengine_core::*;

#[test]
fn java_call_sequence() {
    assert_eq!(we_abi_version(), WE_ABI_VERSION);
    let universe = we_universe_new_demo();
    assert!(!universe.is_null());
    unsafe {
        assert_eq!(we_universe_body_count(universe), 2);

        let len = we_universe_body_name(universe, 1, std::ptr::null_mut(), 0);
        let mut name = vec![0u8; len as usize];
        assert_eq!(
            we_universe_body_name(universe, 1, name.as_mut_ptr(), len as u32),
            len
        );
        assert_eq!(name, b"moon");

        assert_eq!(we_universe_set_time(universe, 120.0), WE_OK);
        let mut xyz = [0.0f64; 3];
        assert_eq!(
            we_universe_body_position(universe, 1, xyz.as_mut_ptr()),
            WE_OK
        );
        let distance = xyz.iter().map(|v| v * v).sum::<f64>().sqrt();
        assert!((distance - 60_000.0).abs() < 1e-6, "distance {distance}");

        we_universe_free(universe);
    }
}

#[test]
fn terrain_call_sequence() {
    // One 16 x 16 chunk on the home planet's E0 face (16,384 cells).
    let mut heights = [0i32; 256];
    let mut biomes = [0u8; 256];
    let status = unsafe {
        we_terrain_columns(
            42,
            16_384,
            0,
            0,
            8_192,
            8_192,
            16,
            16,
            heights.as_mut_ptr(),
            biomes.as_mut_ptr(),
        )
    };
    assert_eq!(status, WE_OK);
    assert!(heights.iter().all(|h| (-320..=600).contains(h)));
    assert!(biomes.iter().all(|b| *b <= 12));
}
```

- [ ] **Step 2: Wire the module in, and bump the ABI.** In `core/crates/we-ffi/src/lib.rs`:
- insert these lines right before `use std::panic::{AssertUnwindSafe, catch_unwind};`:

```rust
mod terrain;

pub use terrain::we_terrain_columns;

```

- change `pub const WE_ABI_VERSION: u32 = 1;` to `pub const WE_ABI_VERSION: u32 = 2;`
- change `fn guard(f: impl FnOnce() -> i32) -> i32 {` to `pub(crate) fn guard(f: impl FnOnce() -> i32) -> i32 {`
- in the test `reports_abi_version`, change `assert_eq!(we_abi_version(), 1);` to `assert_eq!(we_abi_version(), 2);`
- in the module docs, insert these lines right before the line `//! Threading and lifetime: a `WeUniverse` is **not** thread-safe. All calls on`:

```rust
//! `we_terrain_columns` takes no handle and keeps no state: any number of
//! threads may call it at once.
//!
```

In `core/crates/we-ffi/Cargo.toml`, replace the `[dependencies]` section with:

```toml
[dependencies]
we-frames.workspace = true
we-orbit.workspace = true
we-planet.workspace = true
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-ffi`
Expected: FAIL to compile: `unresolved import terrain::we_terrain_columns` (and `cannot find function we_terrain_columns` in the tests).

- [ ] **Step 4: Write the implementation.** Insert this at the top of `core/crates/we-ffi/src/terrain.rs`, above `#[cfg(test)]`:

```rust
//! Terrain for world generation. Stateless: no handle, no globals.

use crate::{WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use we_frames::{CubeSphere, Face};
use we_planet::{MAX_COLUMNS, PlanetKind, Terrain, fill_columns};

/// Fill `count_x` × `count_z` terrain columns whose north-west cell is
/// (`x0`, `z0`) on face `face` (0–5, see `we_frames::Face`) of a planet
/// with `cells` blocks per face edge, generated from `seed` as `kind`
/// (0 terrestrial, 1 barren).
///
/// Outputs are row-major (index `dz * count_x + dx`). `out_heights` gets
/// the surface as whole blocks above the datum (sea level); `out_biomes`
/// gets the biome index (see `we_planet::Biome`).
///
/// Unlike the universe calls, this takes no handle and keeps no state: any
/// number of threads may call it at once.
///
/// Returns `WE_OK`; `WE_ERR_NULL` for a null output with a non-empty region;
/// or `WE_ERR_INVALID` for a bad face, kind or cell count, a region outside
/// the face, or more than `we_planet::MAX_COLUMNS` columns.
///
/// # Safety
/// `out_heights` must be valid for writing `count_x * count_z` `i32`s, and
/// `out_biomes` for as many bytes. Neither needs to be aligned.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn we_terrain_columns(
    seed: u64,
    cells: u32,
    kind: u32,
    face: u32,
    x0: i32,
    z0: i32,
    count_x: u32,
    count_z: u32,
    out_heights: *mut i32,
    out_biomes: *mut u8,
) -> i32 {
    let total = u64::from(count_x) * u64::from(count_z);
    if total > 0 && (out_heights.is_null() || out_biomes.is_null()) {
        return WE_ERR_NULL;
    }
    let (Some(sphere), Some(kind), Some(face)) = (
        CubeSphere::try_new(cells),
        PlanetKind::from_index(kind),
        u8::try_from(face).ok().and_then(Face::from_index),
    ) else {
        return WE_ERR_INVALID;
    };
    if total > MAX_COLUMNS {
        return WE_ERR_INVALID;
    }
    guard(|| {
        let n = total as usize;
        let mut heights = vec![0i32; n];
        let mut biomes = vec![0u8; n];
        let terrain = Terrain::new(seed, kind, sphere);
        let filled = fill_columns(
            sphere,
            terrain,
            face,
            i64::from(x0),
            i64::from(z0),
            count_x,
            count_z,
            &mut heights,
            &mut biomes,
        );
        if filled.is_err() {
            return WE_ERR_INVALID;
        }
        // Byte-wise copies: the caller's buffers need not be aligned.
        unsafe {
            std::ptr::copy_nonoverlapping(
                heights.as_ptr().cast::<u8>(),
                out_heights.cast::<u8>(),
                n * size_of::<i32>(),
            );
            std::ptr::copy_nonoverlapping(biomes.as_ptr(), out_biomes, n);
        }
        WE_OK
    })
}
```

- [ ] **Step 5: Run the full workspace checks**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo build --release -p we-ffi && nm -D --defined-only target/release/libworldengine_core.so | grep -c ' T we_'`
Expected: clean; `worldengine_core` 16 passed, `abi` 2 passed; the symbol count is `8`. (On macOS use `nm -gU target/release/libworldengine_core.dylib | grep -c _we_`.)

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-ffi core/Cargo.lock
git commit -m "feat(ffi): stateless we_terrain_columns; ABI v2"
```

---

### Task 9: Terrain in the Java bridge

**Files:**
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/PlanetKind.java`, `PlanetBiome.java`, `TerrainColumns.java`
- Replace: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java`
- Create: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeTerrainTest.java`

- [ ] **Step 1: Write the failing tests.** Create `NativeTerrainTest.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NativeTerrainTest {
    /** Set by the Gradle test task to the freshly built library (see build.gradle). */
    private static final String LIBRARY_PROPERTY = "worldengine.native.path";

    private static final int HOME_CELLS = 16_384;
    private static final int MOON_CELLS = 4_096;
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty(LIBRARY_PROPERTY);
        assertNotNull(path, "the Gradle test task must set " + LIBRARY_PROPERTY);
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void fillsAChunkWithPlausibleTerrain() {
        TerrainColumns chunk = core.terrainColumns(42, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 8_192, 8_192, 16, 16);
        assertEquals(256, chunk.heights().length);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int height = chunk.height(dx, dz);
                assertTrue(height >= -320 && height <= 600, "height " + height);
                assertTrue(chunk.biome(dx, dz) != PlanetBiome.BARREN);
            }
        }
    }

    @Test
    void batchesMatchSingleColumns() {
        TerrainColumns block = core.terrainColumns(7, HOME_CELLS, PlanetKind.TERRESTRIAL, 3, 1_000, 2_000, 4, 3);
        for (int dz = 0; dz < 3; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                TerrainColumns one = core.terrainColumns(
                        7, HOME_CELLS, PlanetKind.TERRESTRIAL, 3, 1_000 + dx, 2_000 + dz, 1, 1);
                assertEquals(one.height(0, 0), block.height(dx, dz));
                assertEquals(one.biome(0, 0), block.biome(dx, dz));
            }
        }
    }

    @Test
    void cubeCornersAreDeepOcean() {
        // Cell (0, 0) of face E0 sits at the (1, 1, 1) corner of the cube.
        TerrainColumns corner = core.terrainColumns(42, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 0, 0, 1, 1);
        assertEquals(PlanetBiome.DEEP_OCEAN, corner.biome(0, 0));
    }

    @Test
    void theMoonIsBarren() {
        TerrainColumns moon = core.terrainColumns(42, MOON_CELLS, PlanetKind.BARREN, 4, 1_000, 1_000, 8, 8);
        for (int dz = 0; dz < 8; dz++) {
            for (int dx = 0; dx < 8; dx++) {
                assertEquals(PlanetBiome.BARREN, moon.biome(dx, dz));
            }
        }
    }

    @Test
    void seedsChangeTheTerrain() {
        TerrainColumns a = core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 2, 5_000, 5_000, 16, 16);
        TerrainColumns b = core.terrainColumns(2, HOME_CELLS, PlanetKind.TERRESTRIAL, 2, 5_000, 5_000, 16, 16);
        assertFalse(Arrays.equals(a.heights(), b.heights()));
    }

    @Test
    void rejectsInvalidRequests() {
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 6, 0, 0, 1, 1));
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, 3, PlanetKind.TERRESTRIAL, 0, 0, 0, 1, 1));
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, HOME_CELLS - 2, 0, 4, 1));
        assertThrows(NativeCoreException.class,
                () -> core.terrainColumns(1, HOME_CELLS, PlanetKind.TERRESTRIAL, 0, 0, 0, -1, 1));
    }

    @Test
    void isSafeToCallFromManyThreads() throws Exception {
        TerrainColumns expected = core.terrainColumns(42, HOME_CELLS, PlanetKind.TERRESTRIAL, 2, 300, 300, 32, 32);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<TerrainColumns>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(
                        () -> core.terrainColumns(42, HOME_CELLS, PlanetKind.TERRESTRIAL, 2, 300, 300, 32, 32)));
            }
            for (Future<TerrainColumns> result : results) {
                assertArrayEquals(expected.heights(), result.get().heights());
                assertArrayEquals(expected.biomes(), result.get().biomes());
            }
        }
    }

    @Test
    void biomeIdsMatchTheAbi() {
        assertEquals(13, PlanetBiome.values().length);
        assertEquals(0, PlanetBiome.DEEP_OCEAN.id());
        assertEquals(4, PlanetBiome.FOREST.id());
        assertEquals(12, PlanetBiome.BARREN.id());
        assertEquals(PlanetBiome.SNOWY_PEAKS, PlanetBiome.fromId(11));
        assertThrows(NativeCoreException.class, () -> PlanetBiome.fromId(13));
        assertEquals(1, PlanetKind.BARREN.id());
    }
}
```

- [ ] **Step 2: Run them to confirm they fail** (needs JDK 25)

Run: `cd mod && ./gradlew test --tests '*NativeTerrainTest'`
Expected: FAIL to compile: `cannot find symbol: class TerrainColumns` (and `PlanetKind`, `PlanetBiome`).

- [ ] **Step 3: Create the ABI enums and the result type.**

`PlanetKind.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

/** How a planet's terrain is generated. The ids match {@code we_planet::PlanetKind} across the C ABI. */
public enum PlanetKind {
    /** Oceans, climate bands and biomes (the home planet). */
    TERRESTRIAL(0),
    /** Airless, waterless rolling regolith (the moon). */
    BARREN(1);

    private final int id;

    PlanetKind(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
```

`PlanetBiome.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

/**
 * Surface biomes from the Rust terrain model. The declaration order is the C ABI id
 * ({@code we_planet::Biome}), so it is frozen: changing it requires bumping the ABI version.
 */
public enum PlanetBiome {
    DEEP_OCEAN,
    OCEAN,
    BEACH,
    PLAINS,
    FOREST,
    TAIGA,
    TUNDRA,
    DESERT,
    SAVANNA,
    JUNGLE,
    MOUNTAINS,
    SNOWY_PEAKS,
    BARREN;

    private static final PlanetBiome[] BY_ID = values();

    /** The biome with C ABI id {@code id}. */
    public static PlanetBiome fromId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            throw new NativeCoreException("Unknown biome id " + id);
        }
        return BY_ID[id];
    }

    public int id() {
        return ordinal();
    }

    public boolean isWater() {
        return this == DEEP_OCEAN || this == OCEAN;
    }
}
```

`TerrainColumns.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

import java.util.Objects;

/**
 * A row-major block of terrain columns from {@link NativeCore#terrainColumns}: index
 * {@code dz * countX + dx}.
 */
public record TerrainColumns(int countX, int countZ, int[] heights, byte[] biomes) {

    /** Surface height at ({@code dx}, {@code dz}), in whole blocks above the datum (sea level). */
    public int height(int dx, int dz) {
        return heights[index(dx, dz)];
    }

    public PlanetBiome biome(int dx, int dz) {
        return PlanetBiome.fromId(Byte.toUnsignedInt(biomes[index(dx, dz)]));
    }

    private int index(int dx, int dz) {
        Objects.checkIndex(dx, countX);
        Objects.checkIndex(dz, countZ);
        return dz * countX + dx;
    }
}
```

- [ ] **Step 4: Bind the new call.** Replace `NativeCore.java` with the version below. Its changes:
- `EXPECTED_ABI_VERSION = 2`
- `load` reads the ABI version *before* looking up newer symbols, so a stale library reports a version mismatch rather than a missing symbol
- the `we_terrain_columns` downcall
- `terrainColumns`, documented as thread-safe

```java
package io.github.chabelabela.worldengine.nativebridge;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Java view of the Rust core's C ABI ({@code core/crates/we-ffi/src/lib.rs}).
 * Keep the descriptors here in lock-step with the Rust signatures and bump
 * {@link #EXPECTED_ABI_VERSION} together with {@code WE_ABI_VERSION}.
 */
public final class NativeCore {
    public static final int EXPECTED_ABI_VERSION = 2;

    private final MethodHandle abiVersionFn;
    private final MethodHandle newDemoFn;
    private final MethodHandle freeFn;
    private final MethodHandle setTimeFn;
    private final MethodHandle bodyCountFn;
    private final MethodHandle bodyPositionFn;
    private final MethodHandle bodyNameFn;
    private final MethodHandle terrainColumnsFn;

    private NativeCore(SymbolLookup lib) {
        Linker linker = Linker.nativeLinker();
        abiVersionFn = linker.downcallHandle(find(lib, "we_abi_version"), FunctionDescriptor.of(JAVA_INT));
        newDemoFn = linker.downcallHandle(find(lib, "we_universe_new_demo"), FunctionDescriptor.of(ADDRESS));
        freeFn = linker.downcallHandle(find(lib, "we_universe_free"), FunctionDescriptor.ofVoid(ADDRESS));
        setTimeFn = linker.downcallHandle(
                find(lib, "we_universe_set_time"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE));
        bodyCountFn = linker.downcallHandle(
                find(lib, "we_universe_body_count"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
        bodyPositionFn = linker.downcallHandle(
                find(lib, "we_universe_body_position"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        bodyNameFn = linker.downcallHandle(
                find(lib, "we_universe_body_name"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        terrainColumnsFn = linker.downcallHandle(
                find(lib, "we_terrain_columns"),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    }

    /** Load the library at {@code library} for the lifetime of the JVM and check its ABI version. */
    public static NativeCore load(Path library) {
        SymbolLookup lib;
        try {
            lib = SymbolLookup.libraryLookup(library, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new NativeCoreException("Could not load the World Engine core from " + library, e);
        }
        // Check the version before looking up anything newer, so a stale library
        // reports a version mismatch instead of a missing symbol.
        int abi = readAbiVersion(lib);
        if (abi != EXPECTED_ABI_VERSION) {
            throw new NativeCoreException("World Engine core at " + library + " has ABI v" + abi
                    + ", but this mod needs v" + EXPECTED_ABI_VERSION);
        }
        return new NativeCore(lib);
    }

    private static int readAbiVersion(SymbolLookup lib) {
        MethodHandle abiVersion = Linker.nativeLinker()
                .downcallHandle(find(lib, "we_abi_version"), FunctionDescriptor.of(JAVA_INT));
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    private static MemorySegment find(SymbolLookup lib, String name) {
        return lib.find(name).orElseThrow(() -> new NativeCoreException("Missing native symbol " + name));
    }

    private static NativeCoreException wrap(Throwable t) {
        return t instanceof NativeCoreException e ? e : new NativeCoreException("Native call failed", t);
    }

    private static void check(int status, String call) {
        if (status < 0) {
            throw new NativeCoreException(call + " failed with status " + status);
        }
    }

    public int abiVersion() {
        try {
            return (int) abiVersionFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /**
     * Terrain for a {@code countX} by {@code countZ} block of columns whose north-west cell is
     * ({@code x0}, {@code z0}) on {@code face} (0-5, {@code we_frames::Face}) of a planet with
     * {@code cells} blocks per face edge, generated from {@code seed}.
     *
     * <p>Unlike {@link Universe}, this is stateless and thread-safe: world-generation threads may
     * call it concurrently.
     */
    public TerrainColumns terrainColumns(
            long seed, int cells, PlanetKind kind, int face, int x0, int z0, int countX, int countZ) {
        try (Arena arena = Arena.ofConfined()) {
            if (countX < 0 || countZ < 0) {
                throw new NativeCoreException("Negative region size " + countX + " x " + countZ);
            }
            int n = Math.multiplyExact(countX, countZ);
            MemorySegment heights = arena.allocate(JAVA_INT, Math.max(n, 1));
            MemorySegment biomes = arena.allocate(JAVA_BYTE, Math.max(n, 1));
            check((int) terrainColumnsFn.invokeExact(
                    seed, cells, kind.id(), face, x0, z0, countX, countZ, heights, biomes),
                    "we_terrain_columns");
            return new TerrainColumns(countX, countZ,
                    heights.asSlice(0, (long) n * Integer.BYTES).toArray(JAVA_INT),
                    biomes.asSlice(0, n).toArray(JAVA_BYTE));
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** A home planet with a moon; see {@code CelestialSystem::demo} in we-orbit. */
    public Universe newDemoUniverse() {
        MemorySegment handle;
        try {
            handle = (MemorySegment) newDemoFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new NativeCoreException("we_universe_new_demo returned null");
        }
        return new Universe(handle);
    }

    /**
     * Owns one native universe. Not thread-safe: create, use and close it on one
     * thread (the server thread). It holds native memory, so always {@link #close()}
     * it (the mod does this on server stop); there is no cleaner.
     */
    public final class Universe implements AutoCloseable {
        private MemorySegment handle;

        private Universe(MemorySegment handle) {
            this.handle = handle;
        }

        private MemorySegment live() {
            if (handle == null) {
                throw new IllegalStateException("Universe already closed");
            }
            return handle;
        }

        public void setTime(double seconds) {
            MemorySegment h = live();
            try {
                check((int) setTimeFn.invokeExact(h, seconds), "we_universe_set_time");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        public int bodyCount() {
            MemorySegment h = live();
            try {
                return (int) bodyCountFn.invokeExact(h);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Position in metres (Y-up, relative to the root body) at the current time. */
        public double[] bodyPosition(int index) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE, 3);
                check((int) bodyPositionFn.invokeExact(h, index, out), "we_universe_body_position(" + index + ")");
                return out.toArray(JAVA_DOUBLE);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        public String bodyName(int index) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(64);
                int len = (int) bodyNameFn.invokeExact(h, index, buf, (int) buf.byteSize());
                check(len, "we_universe_body_name(" + index + ")");
                if (len > buf.byteSize()) {
                    buf = arena.allocate(len);
                    len = (int) bodyNameFn.invokeExact(h, index, buf, (int) buf.byteSize());
                    check(len, "we_universe_body_name(" + index + ")");
                }
                return new String(buf.asSlice(0, len).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        @Override
        public void close() {
            if (handle == null) {
                return;
            }
            MemorySegment h = handle;
            handle = null;
            try {
                freeFn.invokeExact(h);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd mod && ./gradlew test`
Expected: `BUILD SUCCESSFUL`. `NativeTerrainTest` has 8 tests and all existing tests still pass (`NativeCoreTest.reportsAbiVersion` follows `EXPECTED_ABI_VERSION`).

- [ ] **Step 6: Commit**

```bash
git add mod/src
git commit -m "feat(mod): thread-safe terrain columns in the Java bridge (ABI v2)"
```

---

### Task 10: `/worldengine terrain` debug command

**Files:**
- Replace: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java`

- [ ] **Step 1: Replace the entrypoint.** The new command prints the home planet's column at a face cell for this world's seed. Everything else is unchanged.

```java
package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldEngineMod implements ModInitializer {
    public static final String MOD_ID = "worldengine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** Blocks per face edge of the home planet (ROADMAP §4.4). */
    public static final int HOME_CELLS = 16_384;

    private static NativeCore core; // set once in onInitialize; null if the core is unavailable
    private static NativeCore.Universe universe; // server thread only

    @Override
    public void onInitialize() {
        NativeCore loaded = loadCore();
        core = loaded;
        if (loaded != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
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
                dispatcher.register(Commands.literal("worldengine")
                        .then(Commands.literal("bodies").executes(context -> printBodies(context.getSource())))
                        .then(Commands.literal("terrain")
                                .then(Commands.argument("face", IntegerArgumentType.integer(0, 5))
                                        .then(Commands.argument("x", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                                .then(Commands.argument("z", IntegerArgumentType.integer(0, HOME_CELLS - 1))
                                                        .executes(context -> printTerrain(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "face"),
                                                                IntegerArgumentType.getInteger(context, "x"),
                                                                IntegerArgumentType.getInteger(context, "z")))))))));
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

    /** Debug: the home planet's terrain column at cell (x, z) of a face, for this world's seed. */
    private static int printTerrain(CommandSourceStack source, int face, int x, int z) {
        if (core == null) {
            source.sendFailure(Component.literal("World Engine core failed to load (see the game log)"));
            return 0;
        }
        long seed = source.getLevel().getSeed();
        TerrainColumns column = core.terrainColumns(seed, HOME_CELLS, PlanetKind.TERRESTRIAL, face, x, z, 1, 1);
        String line = String.format(Locale.ROOT, "home planet, face %d, cell (%d, %d): %d m, %s",
                face, x, z, column.height(0, 0), column.biome(0, 0));
        source.sendSuccess(() -> Component.literal(line), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int printBodies(CommandSourceStack source) {
        if (universe == null) {
            source.sendFailure(Component.literal(core == null
                    ? "World Engine core failed to load (see the game log)"
                    : "World Engine universe is not running"));
            return 0;
        }
        universe.setTime(source.getLevel().getGameTime() / 20.0);
        for (int i = 0; i < universe.bodyCount(); i++) {
            double[] p = universe.bodyPosition(i);
            String line = String.format(Locale.ROOT, "%s: x=%.1f y=%.1f z=%.1f m, |r|=%.1f m",
                    universe.bodyName(i), p[0], p[1], p[2], Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]));
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
```

- [ ] **Step 2: Build**

Run: `cd mod && ./gradlew build`
Expected: `BUILD SUCCESSFUL`. If `ServerLevel#getSeed` has a different name in 26.3, find it in the decompiled sources (`./gradlew genSources`) and note the change in the commit.

- [ ] **Step 3: Check in game** (optional, needs a desktop)

Run: `cd mod && ./gradlew runClient`. In a new world, run `/worldengine terrain 0 0 0`, then `/worldengine terrain 1 8000 8000`.
Expected: the first prints `... cell (0, 0): -300 m, DEEP_OCEAN` (a cube corner). The second prints a height between -320 and 600 with a biome. The same command gives the same answer every time in that world.

- [ ] **Step 4: Commit**

```bash
git add mod/src
git commit -m "feat(mod): /worldengine terrain debug command"
```

---

### Task 11: Push, CI on every OS, docs

**Files:**
- Modify: `docs/ROADMAP.md`, `docs/superpowers/plans/2026-11-20-m1-overview.md`

- [ ] **Step 1: Push and watch CI**

Run: `git push`
Expected: every job of the `CI` workflow is green.
- **The core jobs are the determinism check.** `projection_is_bit_identical_on_every_platform` and `terrain_is_bit_identical_on_every_platform` now run on Linux x64, Linux arm64, Windows and macOS arm64.
- If one of them fails on one platform only, **don't change the constant**. Find the maths that differs there (any remaining `f64` transcendental call, or a fused multiply-add) and fix the code.
- The `mod` and `mod tests` jobs run `NativeTerrainTest` on JDK 25 on all three operating systems.

- [ ] **Step 2: Mark M1c done in the docs.**
- In `docs/superpowers/plans/2026-11-20-m1-overview.md`, in the Risks table, append to the end of the mitigation cell of the "Cross-platform float determinism" row: ` **Done in M1c:** libm projection, hash-only noise, golden hashes checked on four platforms in CI.`
- In `docs/ROADMAP.md`, M1 section, change the bullet that starts `- `we-planet` v0:` so it reads:

```markdown
- `we-planet` v0: deterministic terrain height, climate and biomes as functions on the unit sphere, keyed by seed. **Done in M1c** ([plan](superpowers/plans/2026-11-20-m1c-planet-terrain.md)), with bit-identical results on every platform and a thread-safe batched FFI call for chunk generation.
```

- [ ] **Step 3: Commit and push**

```bash
git add docs/
git commit -m "docs: M1c done"
git push
```

---

> **Post-review note (2026-11-20):** M1c was executed with subagents. A Rust and a Java quality review followed, each with fixes and a re-review. The repository, not this plan, is now the source of truth for these files.
> - Rust (`c506d3a`, `264e5e2`):
>   - Guaranteed height bounds `MIN_HEIGHT`/`MAX_HEIGHT` (−400..700 m), clamped.
>   - `glam` and `libm` pinned to exact versions.
>   - A subtropical dry belt, applied only where it is hot, so every seed has deserts and temperate forests (Desert 6.5–10% and Forest 4.3–7% of land on seeds 1–8).
>   - The corner-ocean blend widened to 4–12°.
>   - `TERRAIN_VERSION = 1`.
>   - `we_terrain_columns` rejects cells > `i32::MAX`.
>   - `TERRAIN_GOLDEN` re-pinned to `0x7d35_6930_66e2_9f28` (the model changed before any world shipped). `PROJECTION_GOLDEN` is unchanged.
> - Java (`3d05285`):
>   - Validation before allocation, with `MAX_COLUMNS`.
>   - `Error`s are rethrown, not wrapped.
>   - `TerrainColumns` gained validation, value equality and `MIN_HEIGHT`/`MAX_HEIGHT`.
>   - A barrier-gated concurrency test with distinct regions per thread.

## Self-review against the M1c goals

| Goal (M1 overview + M1a final review) | Task |
|---|---|
| Terrain height and climate bands as functions on the sphere, keyed by seed | 4–6 |
| Stateless, thread-safe, batched FFI that fills chunk columns | 7, 8 |
| Bit-identical on every platform (`libm` projection, hash-only noise, golden hashes in CI) | 1, 4–6, 11 |
| Terrain continuous across seams; deep ocean at cube corners for M1e | 6 |
| A single source of `cells` (`PlanetLayout` from `CubeSphere`) | 3 |
| Validation at the FFI boundary, no debug-assert paths (`cell_of`) | 3, 8 |
| `Face` and `Biome` numbering frozen under `WE_ABI_VERSION` | 2, 6, 8 |
| Usable from Java world-generation threads | 9 |
| Manual check in game | 10 |

**Deliberately not here:**
- Mapping biomes to Minecraft biomes, and the chunk generator itself (M1d).
- Seam overlap bands (M1e).
- Craters and regolith detail for the moon (M5).
- Weather and evolving terrain (M6).
