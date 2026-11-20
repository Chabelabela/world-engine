# M1a Foundations and Frames Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clear the M0 review's entry tasks and add `we-frames`, the cube-sphere and frame math that every later M1 phase builds on.

**Architecture:**
- `we-orbit` gets a type-safe `BodyId(u32)`, a single `Link` holding a body's parent and orbit together, and `Result`-returning constructors. `we-ffi` uses them and is also built as an `rlib`, so a Rust integration test can drive the C ABI exactly as Java does.
- The new pure-Rust crate `we-frames` defines:
  - the six cube faces and their seams, with an exact integer fold across edges
  - the equal-angle projection onto the sphere
  - positions and velocities between a face's block grid and the planet's body frame
  - planet spin (body ↔ inertial)
  - the surface ↔ orbit handoff
  - where the faces sit in a Minecraft level
- On the Java side, a missing native library now disables the mod with a clear log line instead of crashing the game, and stale native caches are pruned.

**Tech Stack:** Rust stable (edition 2024), `glam` 0.34, `proptest` 1.12 (new dev-dependency) · Java 25 FFM · Minecraft 26.3 + Fabric (unchanged)

**What has been checked before execution (2026-11-20):**
- ✅ **Rust:** all code below was built in a scratch worktree of commit `1ccd39a`.
  - `cargo test --workspace` passes: `we-orbit` 20, `we-frames` 27, `we-ffi` 12 unit + 1 integration.
  - `cargo clippy -D warnings` and `cargo fmt --check` are clean.
  - The cdylib still exports all 7 `we_*` symbols.
- ✅ **Real numbers behind the key tests:**
  - Blocks across a seam are 0.82–1.0 m apart on the sphere. A mirrored mapping would be 12.8 km.
  - Distortion is exactly at the equal-angle limits: area 0.707–1.0, aspect ≤ √2, angles 60–120°.
  - The handoff round trip is accurate to 2×10⁻¹² m and 3×10⁻¹⁴ m/s.
- ✅ **Java bridge changes** (`NativeCore.load` wrapping, `pruneStaleCaches`) were run on JDK 21 preview FFM against the built library. All checks passed.
- ⚠️ **Not run locally:** the `WorldEngineMod` change and the JUnit files. They need JDK 25 and Minecraft, so CI verifies them in Task 11.

**Environment note:** if JDK 25 isn't installed (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 11). Don't download JDKs. The Rust steps always run locally.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `core/Cargo.toml` | Modify | Add the `we-frames` member and a shared `proptest` version |
| `core/crates/we-orbit/src/system.rs` | Replace | `BodyId`, `Link`, `SystemError`, `CelestialSystem` with `Result`/`Option` APIs |
| `core/crates/we-orbit/src/lib.rs` | Modify | Re-export the new types |
| `core/crates/we-ffi/Cargo.toml` | Modify | `crate-type = ["cdylib", "rlib"]` |
| `core/crates/we-ffi/src/lib.rs` | Modify | Look bodies up through `BodyId` and `Option` |
| `core/crates/we-ffi/tests/abi.rs` | Create | Drive the C ABI through the rlib, the way Java does |
| `core/crates/we-frames/Cargo.toml`, `src/lib.rs` | Create | Crate root and its docs |
| `core/crates/we-frames/src/face.rs` | Create | `Face`, `Edge`, `QuarterTurns`, neighbours, the exact fold |
| `core/crates/we-frames/src/cube_sphere.rs` | Create | `CubeSphere` projection, `SurfacePoint`, Jacobian, `fold` |
| `core/crates/we-frames/src/layout.rs` | Create | `PlanetLayout`: faces ↔ Minecraft level coordinates |
| `core/crates/we-frames/src/spin.rs` | Create | `Spin`: rotating body frame ↔ inertial frame |
| `core/crates/we-frames/src/frame.rs` | Create | `PlanetFrame`: surface ↔ inertial (the handoff) |
| `mod/src/main/java/.../nativebridge/NativeCore.java` | Modify | Wrap library-load failures in `NativeCoreException` |
| `mod/src/main/java/.../nativebridge/NativeLibraryLoader.java` | Modify | `pruneStaleCaches` |
| `mod/src/main/java/.../WorldEngineMod.java` | Replace | Degrade gracefully without the core; prune caches |
| `mod/src/test/java/.../nativebridge/NativeCoreTest.java` | Modify | Load-failure test |
| `mod/src/test/java/.../nativebridge/NativeLibraryLoaderTest.java` | Replace | Pruning tests |
| `docs/ROADMAP.md` | Modify | Tick off the entry tasks |

(`...` = `io/github/chabelabela/worldengine`)

---

### Task 1: Type-safe body ids and fallible construction (`we-orbit`)

**Files:**
- Replace: `core/crates/we-orbit/src/system.rs`
- Modify: `core/crates/we-orbit/src/lib.rs` (last line)

- [ ] **Step 1: Write the failing tests.** In `system.rs`, replace the whole `#[cfg(test)] mod tests { ... }` block with:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    const HOME: BodyId = BodyId(0);
    const MOON: BodyId = BodyId(1);

    #[test]
    fn demo_has_home_and_moon() {
        let system = CelestialSystem::demo();
        assert_eq!(system.len(), 2);
        assert_eq!(system.body(HOME).unwrap().name, "home");
        let moon = system.body(MOON).unwrap();
        assert_eq!(moon.name, "moon");
        assert_eq!(moon.link.unwrap().parent, HOME);
    }

    #[test]
    fn root_stays_at_origin() {
        let system = CelestialSystem::demo();
        assert_eq!(system.position_at(HOME, 12_345.0), Some(DVec3::ZERO));
    }

    #[test]
    fn moon_stays_60_km_from_home() {
        let system = CelestialSystem::demo();
        for i in 0..10 {
            let d = system.position_at(MOON, i as f64 * 300.0).unwrap().length();
            assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
        }
    }

    #[test]
    fn moon_period_is_about_47_minutes() {
        let system = CelestialSystem::demo();
        let orbit = system.body(MOON).unwrap().link.unwrap().orbit;
        let period = orbit.period(system.body(HOME).unwrap().mu);
        assert!((2_800.0..2_850.0).contains(&period), "period {period}");
    }

    #[test]
    fn unknown_ids_have_no_body_or_position() {
        let system = CelestialSystem::demo();
        assert!(system.body(BodyId(7)).is_none());
        assert!(system.position_at(BodyId(7), 0.0).is_none());
    }

    #[test]
    fn three_level_hierarchy_sums_positions() {
        let mut system = CelestialSystem::new();
        let star = system.add_root("star", 1.0e12, 50_000.0).unwrap();
        let planet = system
            .add_orbiting(
                "planet",
                1.0e9,
                10_000.0,
                star,
                KeplerOrbit::circular(1.0e6),
            )
            .unwrap();
        let moon = system
            .add_orbiting(
                "moon",
                1.0e7,
                2_000.0,
                planet,
                KeplerOrbit::circular(40_000.0),
            )
            .unwrap();
        let t = 1_234.0;
        let expected = KeplerOrbit::circular(1.0e6).position_at(1.0e12, t)
            + KeplerOrbit::circular(40_000.0).position_at(1.0e9, t);
        assert!((system.position_at(moon, t).unwrap() - expected).length() < 1e-6);
    }

    #[test]
    fn rejects_bad_construction() {
        let mut system = CelestialSystem::new();
        let circular = KeplerOrbit::circular(10.0);
        assert_eq!(
            system.add_orbiting("early", 1.0, 1.0, HOME, circular),
            Err(SystemError::NoRoot)
        );
        assert!(matches!(
            system.add_root("bad", -1.0, 1.0),
            Err(SystemError::InvalidBody { .. })
        ));
        let home = system.add_root("home", 1.0, 1.0).unwrap();
        assert_eq!(
            system.add_root("second", 1.0, 1.0),
            Err(SystemError::RootAlreadyExists)
        );
        assert_eq!(
            system.add_orbiting("lost", 1.0, 1.0, BodyId(7), circular),
            Err(SystemError::UnknownParent(BodyId(7)))
        );
        let eccentric = KeplerOrbit {
            eccentricity: 1.5,
            ..circular
        };
        assert_eq!(
            system.add_orbiting("bad", 1.0, 1.0, home, eccentric),
            Err(SystemError::InvalidOrbit {
                name: "bad".to_owned()
            })
        );
        assert_eq!(system.len(), 1, "failed adds must not change the system");
    }

    #[test]
    fn errors_have_readable_messages() {
        assert_eq!(
            SystemError::UnknownParent(BodyId(7)).to_string(),
            "unknown parent body #7"
        );
        assert_eq!(
            SystemError::RootAlreadyExists.to_string(),
            "a system has exactly one root body"
        );
    }
}
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd core && cargo test -p we-orbit`
Expected: FAIL to compile, with errors such as `cannot find type SystemError` and `expected usize, found BodyId`.

- [ ] **Step 3: Write the implementation.** Replace everything above `#[cfg(test)]` in `system.rs` with:

```rust
use crate::KeplerOrbit;
use glam::DVec3;
use std::fmt;

/// Identifies a body inside one [`CelestialSystem`]. Ids follow insertion
/// order, so a parent's id is always lower than its children's.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct BodyId(pub u32);

impl BodyId {
    pub fn index(self) -> usize {
        self.0 as usize
    }
}

impl fmt::Display for BodyId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "#{}", self.0)
    }
}

/// How a non-root body moves: on a Keplerian orbit around its parent.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Link {
    pub parent: BodyId,
    pub orbit: KeplerOrbit,
}

/// A star, planet or moon.
#[derive(Clone, Debug, PartialEq)]
pub struct Body {
    pub name: String,
    /// Standard gravitational parameter `G·M`, m^3/s^2.
    pub mu: f64,
    /// Mean radius, metres (1 block = 1 metre).
    pub radius: f64,
    /// `None` only for the root body, which sits at the origin.
    pub link: Option<Link>,
}

/// Why a body could not be added to a [`CelestialSystem`].
#[derive(Clone, Debug, PartialEq)]
pub enum SystemError {
    RootAlreadyExists,
    NoRoot,
    UnknownParent(BodyId),
    InvalidBody { name: String, mu: f64, radius: f64 },
    InvalidOrbit { name: String },
    TooManyBodies,
}

impl fmt::Display for SystemError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::RootAlreadyExists => write!(f, "a system has exactly one root body"),
            Self::NoRoot => write!(f, "add the root body first"),
            Self::UnknownParent(id) => write!(f, "unknown parent body {id}"),
            Self::InvalidBody { name, mu, radius } => {
                write!(f, "invalid body {name}: mu={mu}, radius={radius}")
            }
            Self::InvalidOrbit { name } => write!(f, "invalid orbit for {name}"),
            Self::TooManyBodies => write!(f, "too many bodies"),
        }
    }
}

impl std::error::Error for SystemError {}

/// A hierarchy of bodies on Keplerian rails (patched-conics style).
#[derive(Clone, Debug, Default)]
pub struct CelestialSystem {
    bodies: Vec<Body>,
}

impl CelestialSystem {
    pub fn new() -> Self {
        Self::default()
    }

    /// The reference scale from docs/ROADMAP.md: a home planet whose cube-sphere
    /// faces are 16,384 blocks wide (radius ~= 10,430 m, 1 g) and a 1.62 m/s^2
    /// moon on a circular 60 km orbit.
    pub fn demo() -> Self {
        let mut system = Self::new();
        let home_radius = 10_430.0;
        let home = system
            .add_root("home", 9.81 * home_radius * home_radius, home_radius)
            .expect("demo home is valid");
        let moon_radius = 2_600.0;
        system
            .add_orbiting(
                "moon",
                1.62 * moon_radius * moon_radius,
                moon_radius,
                home,
                KeplerOrbit::circular(60_000.0),
            )
            .expect("demo moon is valid");
        system
    }

    fn check_body(name: &str, mu: f64, radius: f64) -> Result<(), SystemError> {
        if mu.is_finite() && mu > 0.0 && radius.is_finite() && radius > 0.0 {
            Ok(())
        } else {
            Err(SystemError::InvalidBody {
                name: name.to_owned(),
                mu,
                radius,
            })
        }
    }

    pub fn add_root(&mut self, name: &str, mu: f64, radius: f64) -> Result<BodyId, SystemError> {
        if !self.bodies.is_empty() {
            return Err(SystemError::RootAlreadyExists);
        }
        Self::check_body(name, mu, radius)?;
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            link: None,
        })
    }

    pub fn add_orbiting(
        &mut self,
        name: &str,
        mu: f64,
        radius: f64,
        parent: BodyId,
        orbit: KeplerOrbit,
    ) -> Result<BodyId, SystemError> {
        if self.bodies.is_empty() {
            return Err(SystemError::NoRoot);
        }
        if parent.index() >= self.bodies.len() {
            return Err(SystemError::UnknownParent(parent));
        }
        Self::check_body(name, mu, radius)?;
        if !orbit.is_valid() {
            return Err(SystemError::InvalidOrbit {
                name: name.to_owned(),
            });
        }
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            link: Some(Link { parent, orbit }),
        })
    }

    fn push(&mut self, body: Body) -> Result<BodyId, SystemError> {
        let id = u32::try_from(self.bodies.len()).map_err(|_| SystemError::TooManyBodies)?;
        self.bodies.push(body);
        Ok(BodyId(id))
    }

    pub fn len(&self) -> usize {
        self.bodies.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bodies.is_empty()
    }

    pub fn body(&self, id: BodyId) -> Option<&Body> {
        self.bodies.get(id.index())
    }

    /// Position of `id` relative to the root body at time `t`, or `None` for
    /// an unknown id.
    pub fn position_at(&self, id: BodyId, t: f64) -> Option<DVec3> {
        let mut body = self.body(id)?;
        let mut position = DVec3::ZERO;
        while let Some(link) = body.link {
            let parent = &self.bodies[link.parent.index()];
            position += link.orbit.position_at(parent.mu, t);
            body = parent;
        }
        Some(position)
    }
}
```

Then change the last line of `core/crates/we-orbit/src/lib.rs` to:

```rust
pub use system::{Body, BodyId, CelestialSystem, Link, SystemError};
```

- [ ] **Step 4: Run the we-orbit tests**

Run: `cd core && cargo test -p we-orbit`
Expected: `test result: ok. 20 passed`. (`we-ffi` doesn't compile yet; Task 2 fixes it.)

- [ ] **Step 5: Commit**

```bash
git add core/crates/we-orbit
git commit -m "refactor(core): BodyId newtype, Link, and fallible CelestialSystem construction"
```

---

### Task 2: `we-ffi` on the new API, plus an ABI integration test

**Files:**
- Modify: `core/crates/we-ffi/Cargo.toml`, `core/crates/we-ffi/src/lib.rs`
- Create: `core/crates/we-ffi/tests/abi.rs`

- [ ] **Step 1: Write the failing integration test.** Create `core/crates/we-ffi/tests/abi.rs`:

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
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd core && cargo test -p we-ffi --test abi`
Expected: FAIL to compile. The crate is not an rlib yet, so the test cannot `use worldengine_core`, and `src/lib.rs` still uses the `usize` ids that Task 1 replaced.

- [ ] **Step 3: Build the crate as an rlib too.** In `core/crates/we-ffi/Cargo.toml`, set:

```toml
crate-type = ["cdylib", "rlib"]
```

- [ ] **Step 4: Adopt `BodyId` in the C ABI.** In `core/crates/we-ffi/src/lib.rs`, change `use we_orbit::CelestialSystem;` to `use we_orbit::{BodyId, CelestialSystem};`. Then replace the function `we_universe_body_position` with:

```rust
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_position(
    universe: *const WeUniverse,
    index: u32,
    out_xyz: *mut f64,
) -> i32 {
    let Some(universe) = (unsafe { universe.as_ref() }) else {
        return WE_ERR_NULL;
    };
    if out_xyz.is_null() {
        return WE_ERR_NULL;
    }
    guard(|| {
        let Some(p) = universe.system.position_at(BodyId(index), universe.time) else {
            return WE_ERR_BAD_INDEX;
        };
        for (i, v) in [p.x, p.y, p.z].into_iter().enumerate() {
            unsafe { out_xyz.add(i).write_unaligned(v) };
        }
        WE_OK
    })
}
```

and the function `we_universe_body_name` with:

```rust
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_name(
    universe: *const WeUniverse,
    index: u32,
    buf: *mut u8,
    buf_len: u32,
) -> i32 {
    let Some(universe) = (unsafe { universe.as_ref() }) else {
        return WE_ERR_NULL;
    };
    let Some(body) = universe.system.body(BodyId(index)) else {
        return WE_ERR_BAD_INDEX;
    };
    if buf.is_null() && buf_len > 0 {
        return WE_ERR_NULL;
    }
    guard(|| {
        let name = body.name.as_bytes();
        let n = name.len().min(buf_len as usize);
        if n > 0 {
            unsafe { std::ptr::copy_nonoverlapping(name.as_ptr(), buf, n) };
        }
        i32::try_from(name.len()).unwrap_or(i32::MAX)
    })
}
```

(Keep the doc comments above both functions unchanged. The ABI is unchanged too: the index is still a `u32`, and an unknown index still returns `WE_ERR_BAD_INDEX`.)

- [ ] **Step 5: Run the workspace checks**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
Expected: clean. `worldengine_core` reports 12 unit tests passed and 1 `abi` test passed; `we_orbit` reports 20 passed.

- [ ] **Step 6: Check the C exports survived**

Run: `cd core && cargo build --release -p we-ffi && nm -D --defined-only target/release/libworldengine_core.so | grep -c ' T we_'`
Expected: `7`. (On macOS: `nm -gU target/release/libworldengine_core.dylib | grep -c _we_`.)

- [ ] **Step 7: Commit**

```bash
git add core/crates/we-ffi
git commit -m "refactor(core): use BodyId in the C ABI; add rlib target and ABI integration test"
```

---

### Task 3: `we-frames` crate and the cube's faces

**Files:**
- Modify: `core/Cargo.toml`
- Create: `core/crates/we-frames/Cargo.toml`, `core/crates/we-frames/src/lib.rs`, `core/crates/we-frames/src/face.rs`

- [ ] **Step 1: Register the crate.** Replace `core/Cargo.toml` with:

```toml
[workspace]
resolver = "3"
members = ["crates/we-orbit", "crates/we-frames", "crates/we-ffi"]

[workspace.package]
version = "0.1.0"
edition = "2024"
rust-version = "1.85"

[workspace.dependencies]
glam = "0.34"
proptest = "1.12"
we-orbit = { path = "crates/we-orbit" }
we-frames = { path = "crates/we-frames" }

[profile.release]
# Keep the default panic = "unwind": we-ffi's guard relies on catching panics.
debug = "line-tables-only"
```

- [ ] **Step 2: Create `core/crates/we-frames/Cargo.toml`**

```toml
[package]
name = "we-frames"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
description = "Cube-sphere planets, face grids, seams and frame transforms for World Engine"

[dependencies]
glam.workspace = true

[dev-dependencies]
proptest.workspace = true
```

- [ ] **Step 3: Create `core/crates/we-frames/src/lib.rs` (stage 1)**

```rust
//! Cube-sphere planets and the frames World Engine moves things between.
//!
//! - A planet is a cube whose six faces are square block grids ([`Face`]),
//!   projected onto a sphere with the equal-angle mapping ([`CubeSphere`]).
//! - Gameplay happens in the *surface frame*: one face's flat block grid,
//!   gravity along −Y, vanilla rules intact. Its axes (east, up, south) are a
//!   proper rotation of Minecraft's (X, Y, Z), so nothing is ever mirrored.
//! - The *body frame* is planet-centred and spins with the planet; the
//!   *inertial frame* is planet-centred and does not ([`Spin`], [`PlanetFrame`]).
//! - Walking off a face edge continues on the neighbouring face as if the
//!   cube were unfolded flat across that edge ([`CubeSphere::fold`]).
//! - [`PlanetLayout`] places the six faces in one Minecraft level.
//!
//! Units and axes match the rest of the core: metres, seconds, right-handed,
//! Y-up. Grid x grows east, grid z grows south (Minecraft's convention).

mod face;

pub use face::{Edge, Face, QuarterTurns};
```

- [ ] **Step 4: Write the failing tests.** Create `core/crates/we-frames/src/face.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    const EDGES: [Edge; 4] = [Edge::West, Edge::East, Edge::North, Edge::South];

    #[test]
    fn bases_are_orthonormal_and_oriented_like_minecraft() {
        for face in Face::ALL {
            let (n, e, s) = (face.normal(), face.east(), face.south());
            for v in [n, e, s] {
                assert!((v.length() - 1.0).abs() < 1e-15);
            }
            assert!(n.dot(e).abs() < 1e-15 && n.dot(s).abs() < 1e-15 && e.dot(s).abs() < 1e-15);
            // Minecraft: X (east) x Y (up) = Z (south).
            assert!((e.cross(n) - s).length() < 1e-15, "{face:?}");
        }
    }

    #[test]
    fn equatorial_east_is_prograde() {
        // The planet spins about +Y, so surface velocity points along Y x r.
        for face in [Face::E0, Face::E1, Face::E2, Face::E3] {
            assert_eq!(DVec3::Y.cross(face.normal()), face.east(), "{face:?}");
        }
    }

    #[test]
    fn faces_are_found_from_their_normals_and_directions() {
        for face in Face::ALL {
            assert_eq!(Face::from_normal(face.normal()), face);
            let tilted = face.normal() + 0.3 * face.east() - 0.2 * face.south();
            assert_eq!(Face::of_direction(tilted), face);
        }
    }

    #[test]
    fn neighbors_are_symmetric_and_distinct() {
        for face in Face::ALL {
            let mut seen = Vec::new();
            for edge in EDGES {
                let (next, arrived) = face.neighbor(edge);
                assert_eq!(next.neighbor(arrived), (face, edge), "{face:?} {edge:?}");
                assert_ne!(next, face);
                assert_ne!(
                    next.normal(),
                    -face.normal(),
                    "opposite face is never adjacent"
                );
                seen.push(next);
            }
            seen.sort_by_key(|f| *f as u8);
            seen.dedup();
            assert_eq!(seen.len(), 4, "{face:?} must touch four different faces");
        }
    }

    #[test]
    fn layout_edges_continue_straight_on() {
        // The edges that are contiguous in PlanetLayout's cross-shaped net.
        let straight = [
            (Face::E0, Edge::East, Face::E1, Edge::West),
            (Face::E1, Edge::East, Face::E2, Edge::West),
            (Face::E2, Edge::East, Face::E3, Edge::West),
            (Face::E3, Edge::East, Face::E0, Edge::West),
            (Face::E1, Edge::North, Face::North, Edge::South),
            (Face::E1, Edge::South, Face::South, Edge::North),
        ];
        for (face, edge, next, arrived) in straight {
            assert_eq!(face.neighbor(edge), (next, arrived));
            let (px, pz) = match edge {
                Edge::East => (1.25, 0.4),
                Edge::North => (0.4, -1.25),
                Edge::South => (0.4, 1.25),
                Edge::West => unreachable!(),
            };
            let (_, _, _, turns) = face.fold_centered(px, pz, 1.0).unwrap();
            assert_eq!(turns, QuarterTurns::NONE, "{face:?} {edge:?}");
        }
    }

    #[test]
    fn fold_is_none_inside_and_beyond_corners() {
        assert!(Face::E0.fold_centered(0.3, -0.9, 1.0).is_none());
        assert!(
            Face::E0.fold_centered(1.0, 1.0, 1.0).is_none(),
            "the edge itself is inside"
        );
        assert!(Face::E0.fold_centered(1.2, 1.3, 1.0).is_none());
    }

    #[test]
    fn quarter_turns_compose_and_invert() {
        for k in 0..4 {
            let q = QuarterTurns(k);
            let (x, z) = q.apply(0.3, -0.7);
            let (bx, bz) = q.inverse().apply(x, z);
            assert!((bx - 0.3).abs() < 1e-15 && (bz + 0.7).abs() < 1e-15);
        }
        assert_eq!(
            QuarterTurns(1).apply(1.0, 0.0),
            (-0.0, 1.0),
            "east turns south"
        );
        assert_eq!(
            QuarterTurns(1).rotate_yaw(-90.0),
            0.0,
            "facing east turns to facing south"
        );
    }
}
```

- [ ] **Step 5: Run them to confirm they fail**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `cannot find type Face`, `cannot find type Edge` and `cannot find type QuarterTurns` in `face.rs`.

- [ ] **Step 6: Write the implementation.** Insert this at the top of `face.rs`, above `#[cfg(test)]`. The face table is the heart of M1: each face's (east, up, south) must be a proper rotation of Minecraft's (X, Y, Z), and the equatorial faces must run east (prograde).

```rust
use glam::DVec3;

/// One of the six faces of a cube-sphere planet. The equatorial faces
/// `E0..E3` run eastward (prograde), starting at +X. `North` is +Y (the spin
/// axis) and `South` is −Y.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Face {
    E0,
    E1,
    E2,
    E3,
    North,
    South,
}

/// A side of a face's block grid. Grid x grows east and grid z grows south,
/// so `West` is x = 0 and `North` is z = 0.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Edge {
    West,
    East,
    North,
    South,
}

/// A rotation of the block grid by whole quarter turns, clockwise seen from
/// above (east turns into south), like Minecraft's `Rotation.CLOCKWISE_90`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct QuarterTurns(pub u8);

impl QuarterTurns {
    pub const NONE: Self = Self(0);

    /// Rotate a grid direction (dx east, dz south).
    pub fn apply(self, dx: f64, dz: f64) -> (f64, f64) {
        match self.0 % 4 {
            0 => (dx, dz),
            1 => (-dz, dx),
            2 => (-dx, -dz),
            _ => (dz, -dx),
        }
    }

    /// Rotate a Minecraft yaw in degrees (0 = south, 90 = west, -90 = east).
    pub fn rotate_yaw(self, yaw: f32) -> f32 {
        yaw + 90.0 * f32::from(self.0 % 4)
    }

    pub fn inverse(self) -> Self {
        Self((4 - self.0 % 4) % 4)
    }

    /// The rotation that turns grid east (1, 0) into `image`, an axis-aligned
    /// unit vector.
    fn from_east_image(image: (f64, f64)) -> Self {
        match (image.0.round() as i8, image.1.round() as i8) {
            (1, 0) => Self(0),
            (0, 1) => Self(1),
            (-1, 0) => Self(2),
            (0, -1) => Self(3),
            other => unreachable!("not an axis direction: {other:?}"),
        }
    }
}

impl Face {
    pub const ALL: [Face; 6] = [
        Face::E0,
        Face::E1,
        Face::E2,
        Face::E3,
        Face::North,
        Face::South,
    ];

    /// Outward normal in the planet body frame.
    pub fn normal(self) -> DVec3 {
        match self {
            Face::E0 => DVec3::X,
            Face::E1 => DVec3::NEG_Z,
            Face::E2 => DVec3::NEG_X,
            Face::E3 => DVec3::Z,
            Face::North => DVec3::Y,
            Face::South => DVec3::NEG_Y,
        }
    }

    /// Direction of increasing grid x (east on the equatorial faces).
    pub fn east(self) -> DVec3 {
        match self {
            Face::E0 => DVec3::NEG_Z,
            Face::E1 => DVec3::NEG_X,
            Face::E2 => DVec3::Z,
            Face::E3 => DVec3::X,
            Face::North | Face::South => DVec3::NEG_X,
        }
    }

    /// Direction of increasing grid z (south on the equatorial faces).
    pub fn south(self) -> DVec3 {
        match self {
            Face::E0 | Face::E1 | Face::E2 | Face::E3 => DVec3::NEG_Y,
            Face::North => DVec3::NEG_Z,
            Face::South => DVec3::Z,
        }
    }

    /// The face whose normal is the axis `normal` (exactly ±X, ±Y or ±Z).
    pub fn from_normal(normal: DVec3) -> Face {
        Face::ALL
            .into_iter()
            .find(|f| f.normal().distance_squared(normal) < 1e-12)
            .unwrap_or_else(|| panic!("not a face normal: {normal:?}"))
    }

    /// The face a direction points through: the axis with the largest
    /// absolute component.
    pub fn of_direction(dir: DVec3) -> Face {
        let a = dir.abs();
        let axis = if a.x >= a.y && a.x >= a.z {
            DVec3::X * dir.x.signum()
        } else if a.y >= a.z {
            DVec3::Y * dir.y.signum()
        } else {
            DVec3::Z * dir.z.signum()
        };
        Face::from_normal(axis)
    }

    /// The face across `edge`, and the edge of that face you arrive through.
    pub fn neighbor(self, edge: Edge) -> (Face, Edge) {
        let (px, pz) = match edge {
            Edge::West => (-1.5, 0.0),
            Edge::East => (1.5, 0.0),
            Edge::North => (0.0, -1.5),
            Edge::South => (0.0, 1.5),
        };
        let (face, x, z, _) = self.fold_centered(px, pz, 1.0).expect("beyond one edge");
        let arrived = if x <= -0.5 {
            Edge::West
        } else if x >= 0.5 {
            Edge::East
        } else if z <= -0.5 {
            Edge::North
        } else {
            Edge::South
        };
        (face, arrived)
    }

    /// Fold a point given in centred grid coordinates (`px`, `pz` measured
    /// from the face centre, face half-width `half`) across the single edge it
    /// lies beyond, onto the neighbouring face, as if the cube's surface were
    /// unfolded flat across that edge. Returns the neighbour, the centred
    /// coordinates there, and the rotation that carries grid directions across.
    /// `None` if the point is inside the face or beyond a corner.
    pub(crate) fn fold_centered(
        self,
        px: f64,
        pz: f64,
        half: f64,
    ) -> Option<(Face, f64, f64, QuarterTurns)> {
        let beyond_x = px.abs() > half;
        let beyond_z = pz.abs() > half;
        if beyond_x == beyond_z {
            return None;
        }
        let (n, e, s) = (self.normal(), self.east(), self.south());
        // Point on the surface of a cube of half-size `half`, folded over the
        // crossed edge, plus the image of grid east (d/dpx) on that surface.
        let (point, next_normal, east_image) = if beyond_x {
            let sign = px.signum();
            (
                (2.0 * half - px.abs()) * n + sign * half * e + pz * s,
                sign * e,
                -sign * n,
            )
        } else {
            let sign = pz.signum();
            (
                (2.0 * half - pz.abs()) * n + px * e + sign * half * s,
                sign * s,
                e,
            )
        };
        let next = Face::from_normal(next_normal);
        let (ne, ns) = (next.east(), next.south());
        let turns = QuarterTurns::from_east_image((east_image.dot(ne), east_image.dot(ns)));
        Some((next, point.dot(ne), point.dot(ns), turns))
    }
}
```

- [ ] **Step 7: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-frames`
Expected: `test result: ok. 7 passed`

- [ ] **Step 8: Commit**

```bash
git add core/Cargo.toml core/Cargo.lock core/crates/we-frames
git commit -m "feat(frames): cube faces, seams and quarter-turn rotations"
```

---

### Task 4: Equal-angle cube-sphere (`CubeSphere`)

**Files:**
- Create: `core/crates/we-frames/src/cube_sphere.rs`
- Modify: `core/crates/we-frames/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `core/crates/we-frames/src/lib.rs` with (stage 2):

```rust
//! Cube-sphere planets and the frames World Engine moves things between.
//!
//! - A planet is a cube whose six faces are square block grids ([`Face`]),
//!   projected onto a sphere with the equal-angle mapping ([`CubeSphere`]).
//! - Gameplay happens in the *surface frame*: one face's flat block grid,
//!   gravity along −Y, vanilla rules intact. Its axes (east, up, south) are a
//!   proper rotation of Minecraft's (X, Y, Z), so nothing is ever mirrored.
//! - The *body frame* is planet-centred and spins with the planet; the
//!   *inertial frame* is planet-centred and does not ([`Spin`], [`PlanetFrame`]).
//! - Walking off a face edge continues on the neighbouring face as if the
//!   cube were unfolded flat across that edge ([`CubeSphere::fold`]).
//! - [`PlanetLayout`] places the six faces in one Minecraft level.
//!
//! Units and axes match the rest of the core: metres, seconds, right-handed,
//! Y-up. Grid x grows east, grid z grows south (Minecraft's convention).

mod cube_sphere;
mod face;

pub use cube_sphere::{CubeSphere, Folded, SurfacePoint};
pub use face::{Edge, Face, QuarterTurns};
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-frames/src/cube_sphere.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use std::f64::consts::SQRT_2;

    const N: u32 = 16_384;

    fn sphere() -> CubeSphere {
        CubeSphere::new(N)
    }

    fn face_strategy() -> impl Strategy<Value = Face> {
        prop::sample::select(Face::ALL.to_vec())
    }

    fn point(face: Face, x: f64, y: f64, z: f64) -> SurfacePoint {
        SurfacePoint { face, x, y, z }
    }

    #[test]
    fn radius_makes_equator_blocks_one_metre() {
        let s = sphere();
        assert!((s.radius() - 10_430.378_350_470_453).abs() < 1e-9);
        let row = f64::from(N) / 2.0;
        for x in [0.5, 1_000.5, 8_191.5, 16_382.5] {
            let a = s.direction(Face::E0, x, row);
            let b = s.direction(Face::E0, x + 1.0, row);
            let arc = a.angle_between(b) * s.radius();
            assert!((arc - 1.0).abs() < 1e-6, "x={x} arc={arc}");
        }
    }

    #[test]
    fn face_centres_point_along_normals() {
        let s = sphere();
        let c = f64::from(N) / 2.0;
        for face in Face::ALL {
            assert!((s.direction(face, c, c) - face.normal()).length() < 1e-15);
        }
    }

    proptest! {
        #[test]
        fn grid_direction_round_trip(
            face in face_strategy(),
            x in 0.01f64..16_383.99,
            z in 0.01f64..16_383.99,
        ) {
            let s = sphere();
            let (f, gx, gz) = s.grid_of(s.direction(face, x, z));
            prop_assert_eq!(f, face);
            prop_assert!((gx - x).abs() < 1e-7 && (gz - z).abs() < 1e-7);
        }

        #[test]
        fn body_position_round_trip(
            face in face_strategy(),
            x in 0.01f64..16_383.99,
            y in -2_000.0f64..60_000.0,
            z in 0.01f64..16_383.99,
        ) {
            let s = sphere();
            let p = point(face, x, y, z);
            let q = s.to_surface(s.to_body(p));
            prop_assert_eq!(q.face, face);
            prop_assert!((s.to_body(q) - s.to_body(p)).length() < 1e-6);
        }

        #[test]
        fn velocity_round_trip(
            face in face_strategy(),
            x in 0.01f64..16_383.99,
            y in -100.0f64..3_000.0,
            z in 0.01f64..16_383.99,
            vx in -300.0f64..300.0,
            vy in -300.0f64..300.0,
            vz in -300.0f64..300.0,
        ) {
            let s = sphere();
            let p = point(face, x, y, z);
            let v = DVec3::new(vx, vy, vz);
            let back = s.velocity_to_surface(p, s.velocity_to_body(p, v));
            prop_assert!((back - v).length() < 1e-9);
        }
    }

    #[test]
    fn jacobian_matches_finite_differences() {
        let s = sphere();
        for (face, x, y, z) in [
            (Face::E0, 8_192.0, 0.0, 8_192.0),
            (Face::E2, 100.0, 250.0, 16_000.0),
            (Face::North, 15_000.0, 1_500.0, 300.0),
            (Face::South, 4_000.5, -50.0, 12_345.25),
        ] {
            let p = point(face, x, y, z);
            let jac = s.jacobian(p);
            let h = 1e-3;
            let steps = [DVec3::X, DVec3::Y, DVec3::Z];
            for (column, step) in jac.iter().zip(steps) {
                let plus = point(face, x + h * step.x, y + h * step.y, z + h * step.z);
                let minus = point(face, x - h * step.x, y - h * step.y, z - h * step.z);
                let numeric = (s.to_body(plus) - s.to_body(minus)) / (2.0 * h);
                assert!(
                    (numeric - *column).length() < 1e-6 * column.length(),
                    "{face:?} {step:?}: {numeric:?} vs {column:?}"
                );
            }
        }
    }

    #[test]
    fn distortion_stays_within_equal_angle_bounds() {
        // Cell area relative to the face centre stays in [1/√2, 1]; a cell's
        // aspect ratio stays within √2; grid lines meet at 60–120 degrees.
        let s = sphere();
        let c = f64::from(N) / 2.0;
        let [cx, _, cz] = s.jacobian(point(Face::E0, c, 0.0, c));
        let centre_area = cx.cross(cz).length();
        let steps = 64;
        for i in 0..=steps {
            for j in 0..=steps {
                let x = f64::from(N) * f64::from(i) / f64::from(steps);
                let z = f64::from(N) * f64::from(j) / f64::from(steps);
                let [jx, _, jz] = s.jacobian(point(Face::E0, x, 0.0, z));
                let area = jx.cross(jz).length() / centre_area;
                assert!(
                    (1.0 / SQRT_2 - 1e-9..=1.0 + 1e-9).contains(&area),
                    "area {area} at {x},{z}"
                );
                let aspect = jx.length() / jz.length();
                assert!(
                    (1.0 / SQRT_2 - 1e-9..=SQRT_2 + 1e-9).contains(&aspect),
                    "aspect {aspect}"
                );
                let angle = jx.angle_between(jz).to_degrees();
                assert!(
                    (60.0 - 1e-6..=120.0 + 1e-6).contains(&angle),
                    "angle {angle}"
                );
            }
        }
    }

    #[test]
    fn fold_maps_cell_centres_to_cell_centres() {
        let s = sphere();
        let n = f64::from(N);
        for face in Face::ALL {
            for (x, z) in [
                (n + 0.5, 10.5),
                (-3.5, 9_000.5),
                (77.5, n + 2.5),
                (16_000.5, -0.5),
            ] {
                let folded = s.fold(point(face, x, 12.0, z)).expect("beyond one edge");
                let q = folded.point;
                assert_eq!(q.y, 12.0);
                assert_eq!((q.x - 0.5).fract(), 0.0, "{face:?} {x},{z} -> {q:?}");
                assert_eq!((q.z - 0.5).fract(), 0.0, "{face:?} {x},{z} -> {q:?}");
                assert!((0.0..n).contains(&q.x) && (0.0..n).contains(&q.z));
            }
        }
    }

    #[test]
    fn fold_continues_smoothly_on_the_sphere() {
        // The first cell beyond an edge lands next to the last cell inside it.
        let s = sphere();
        let n = f64::from(N);
        for face in Face::ALL {
            for along in [0.5, 1_234.5, 8_191.5, n - 0.5] {
                for (inside, beyond) in [
                    ((n - 0.5, along), (n + 0.5, along)),
                    ((0.5, along), (-0.5, along)),
                    ((along, n - 0.5), (along, n + 0.5)),
                    ((along, 0.5), (along, -0.5)),
                ] {
                    let a = s.direction(face, inside.0, inside.1);
                    let q = s.fold(point(face, beyond.0, 0.0, beyond.1)).unwrap().point;
                    let b = s.direction(q.face, q.x, q.z);
                    let gap = a.angle_between(b) * s.radius();
                    assert!(
                        (0.5..=1.5).contains(&gap),
                        "{face:?} {beyond:?}: gap {gap} m"
                    );
                }
            }
        }
    }

    #[test]
    fn folding_back_returns_to_the_start() {
        let s = sphere();
        let n = f64::from(N);
        for face in Face::ALL {
            for (x, z, out) in [
                (n + 7.5, 100.5, (1.0, 0.0)),
                (-7.5, 100.5, (-1.0, 0.0)),
                (100.5, n + 7.5, (0.0, 1.0)),
                (100.5, -7.5, (0.0, -1.0)),
            ] {
                let there = s.fold(point(face, x, 0.0, z)).unwrap();
                // Continue in the same direction, now 15 blocks back across the seam.
                let (dx, dz) = there.turns.apply(out.0, out.1);
                let back_beyond = point(
                    there.point.face,
                    there.point.x - 15.0 * dx,
                    0.0,
                    there.point.z - 15.0 * dz,
                );
                let back = s.fold(back_beyond).expect("beyond the seam again");
                assert_eq!(back.point.face, face);
                assert!((back.point.x - (x - 15.0 * out.0)).abs() < 1e-9);
                assert!((back.point.z - (z - 15.0 * out.1)).abs() < 1e-9);
                assert_eq!(back.turns, there.turns.inverse());
            }
        }
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `cannot find type CubeSphere`, `SurfacePoint` and `Folded`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `cube_sphere.rs`, above `#[cfg(test)]`:

```rust
use crate::face::{Face, QuarterTurns};
use glam::{DMat3, DVec3};
use std::f64::consts::{FRAC_PI_4, PI};

/// A point in a face's surface frame: `x` and `z` in blocks from the face's
/// north-west corner (0..cells), `y` in metres above the datum sphere.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SurfacePoint {
    pub face: Face,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

/// A point carried across a face edge by [`CubeSphere::fold`].
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Folded {
    pub point: SurfacePoint,
    /// Rotation that carries grid directions (velocities, yaw, block facing)
    /// from the old face to the new one.
    pub turns: QuarterTurns,
}

/// A planet whose six faces are `cells` x `cells` block grids, projected onto
/// a sphere with the equal-angle (tangent-adjusted) cube map: grid lines are
/// evenly spaced in angle, so cell area varies by at most a factor of √2.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CubeSphere {
    cells: u32,
    radius: f64,
}

impl CubeSphere {
    /// One block spans exactly one metre along the equator, so the datum
    /// radius is `2 * cells / π` (16,384 cells gives about 10,430 m).
    pub fn new(cells: u32) -> Self {
        assert!(
            cells >= 2 && cells % 2 == 0,
            "cells must be even and at least 2"
        );
        Self {
            cells,
            radius: 2.0 * f64::from(cells) / PI,
        }
    }

    pub fn cells(self) -> u32 {
        self.cells
    }

    /// Radius of the datum sphere (altitude 0), metres.
    pub fn radius(self) -> f64 {
        self.radius
    }

    fn half(self) -> f64 {
        f64::from(self.cells) / 2.0
    }

    /// Grid position → (tan of the angle along east, along south).
    fn tangents(self, x: f64, z: f64) -> (f64, f64) {
        let a = x / self.half() - 1.0;
        let b = z / self.half() - 1.0;
        ((a * FRAC_PI_4).tan(), (b * FRAC_PI_4).tan())
    }

    /// Unit direction (body frame) of grid position (`x`, `z`) on `face`.
    pub fn direction(self, face: Face, x: f64, z: f64) -> DVec3 {
        let (ta, tb) = self.tangents(x, z);
        (face.normal() + ta * face.east() + tb * face.south()).normalize()
    }

    /// The face and grid position (`x`, `z`) a unit direction points at.
    pub fn grid_of(self, dir: DVec3) -> (Face, f64, f64) {
        let face = Face::of_direction(dir);
        let along = dir.dot(face.normal());
        let a = (dir.dot(face.east()) / along).atan() / FRAC_PI_4;
        let b = (dir.dot(face.south()) / along).atan() / FRAC_PI_4;
        (face, (a + 1.0) * self.half(), (b + 1.0) * self.half())
    }

    /// Body-frame position (metres from the planet centre) of a surface point.
    pub fn to_body(self, p: SurfacePoint) -> DVec3 {
        (self.radius + p.y) * self.direction(p.face, p.x, p.z)
    }

    /// The surface point at a body-frame position (inverse of [`Self::to_body`]).
    pub fn to_surface(self, position: DVec3) -> SurfacePoint {
        let r = position.length();
        let (face, x, z) = self.grid_of(position / r);
        SurfacePoint {
            face,
            x,
            y: r - self.radius,
            z,
        }
    }

    /// Columns ∂P/∂x, ∂P/∂y, ∂P/∂z of the Jacobian of [`Self::to_body`].
    pub fn jacobian(self, p: SurfacePoint) -> [DVec3; 3] {
        let (ta, tb) = self.tangents(p.x, p.z);
        let (n, e, s) = (p.face.normal(), p.face.east(), p.face.south());
        let c = n + ta * e + tb * s;
        let len = c.length();
        let d = c / len;
        let angle_per_block = FRAC_PI_4 / self.half();
        let dc_dx = e * (1.0 + ta * ta) * angle_per_block;
        let dc_dz = s * (1.0 + tb * tb) * angle_per_block;
        // d(c/|c|) = (I - d dᵀ) dc / |c|
        let scale = (self.radius + p.y) / len;
        let tangential = |v: DVec3| (v - d * d.dot(v)) * scale;
        [tangential(dc_dx), d, tangential(dc_dz)]
    }

    /// Body-frame velocity of a point moving at `grid_velocity`
    /// (dx/dt, dy/dt, dz/dt) in the surface frame.
    pub fn velocity_to_body(self, p: SurfacePoint, grid_velocity: DVec3) -> DVec3 {
        let [jx, jy, jz] = self.jacobian(p);
        jx * grid_velocity.x + jy * grid_velocity.y + jz * grid_velocity.z
    }

    /// Surface-frame velocity at `p` of a body-frame velocity (inverse of
    /// [`Self::velocity_to_body`]).
    pub fn velocity_to_surface(self, p: SurfacePoint, body_velocity: DVec3) -> DVec3 {
        let [jx, jy, jz] = self.jacobian(p);
        DMat3::from_cols(jx, jy, jz).inverse() * body_velocity
    }

    /// If `p` lies beyond exactly one edge of its face (by less than one face
    /// width), the same spot on the neighbouring face, as if the cube's
    /// surface were unfolded flat across that edge. Whole cells map to whole
    /// cells, so blocks line up one-to-one across seams. `None` if `p` is
    /// inside its face (edges included) or beyond a corner.
    pub fn fold(self, p: SurfacePoint) -> Option<Folded> {
        let h = self.half();
        let (face, x, z, turns) = p.face.fold_centered(p.x - h, p.z - h, h)?;
        Some(Folded {
            point: SurfacePoint {
                face,
                x: x + h,
                y: p.y,
                z: z + h,
            },
            turns,
        })
    }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-frames`
Expected: `test result: ok. 17 passed` (7 face + 10 cube_sphere, including 3 property tests of 256 cases each).

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-frames
git commit -m "feat(frames): equal-angle cube-sphere projection, Jacobian and seam fold"
```

---

### Task 5: Faces in a Minecraft level (`PlanetLayout`)

**Files:**
- Create: `core/crates/we-frames/src/layout.rs`
- Modify: `core/crates/we-frames/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `lib.rs` with (stage 3):

```rust
//! Cube-sphere planets and the frames World Engine moves things between.
//!
//! - A planet is a cube whose six faces are square block grids ([`Face`]),
//!   projected onto a sphere with the equal-angle mapping ([`CubeSphere`]).
//! - Gameplay happens in the *surface frame*: one face's flat block grid,
//!   gravity along −Y, vanilla rules intact. Its axes (east, up, south) are a
//!   proper rotation of Minecraft's (X, Y, Z), so nothing is ever mirrored.
//! - The *body frame* is planet-centred and spins with the planet; the
//!   *inertial frame* is planet-centred and does not ([`Spin`], [`PlanetFrame`]).
//! - Walking off a face edge continues on the neighbouring face as if the
//!   cube were unfolded flat across that edge ([`CubeSphere::fold`]).
//! - [`PlanetLayout`] places the six faces in one Minecraft level.
//!
//! Units and axes match the rest of the core: metres, seconds, right-handed,
//! Y-up. Grid x grows east, grid z grows south (Minecraft's convention).

mod cube_sphere;
mod face;
mod layout;

pub use cube_sphere::{CubeSphere, Folded, SurfacePoint};
pub use face::{Edge, Face, QuarterTurns};
pub use layout::PlanetLayout;
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-frames/src/layout.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::cube_sphere::{CubeSphere, SurfacePoint};
    use crate::face::QuarterTurns;

    const N: u32 = 16_384;

    fn layout() -> PlanetLayout {
        PlanetLayout {
            origin_x: -40_000,
            origin_z: 1_000_000,
            cells: N,
        }
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
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `cannot find type PlanetLayout`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `layout.rs`, above `#[cfg(test)]`:

````rust
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
    /// Level block x of E0's north-west corner.
    pub origin_x: i64,
    /// Level block z of E0's north-west corner.
    pub origin_z: i64,
    /// Blocks per face edge.
    pub cells: u32,
}

impl PlanetLayout {
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
````

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-frames`
Expected: `test result: ok. 22 passed`. `contiguous_edges_agree_with_fold` is the key test: wherever two faces touch in the level, stepping across must land exactly where the cube fold says.

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-frames
git commit -m "feat(frames): planet layout as a cross-shaped cube net in one level"
```

---

### Task 6: Planet spin (`Spin`)

**Files:**
- Create: `core/crates/we-frames/src/spin.rs`
- Modify: `core/crates/we-frames/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `lib.rs` with (stage 4):

```rust
//! Cube-sphere planets and the frames World Engine moves things between.
//!
//! - A planet is a cube whose six faces are square block grids ([`Face`]),
//!   projected onto a sphere with the equal-angle mapping ([`CubeSphere`]).
//! - Gameplay happens in the *surface frame*: one face's flat block grid,
//!   gravity along −Y, vanilla rules intact. Its axes (east, up, south) are a
//!   proper rotation of Minecraft's (X, Y, Z), so nothing is ever mirrored.
//! - The *body frame* is planet-centred and spins with the planet; the
//!   *inertial frame* is planet-centred and does not ([`Spin`], [`PlanetFrame`]).
//! - Walking off a face edge continues on the neighbouring face as if the
//!   cube were unfolded flat across that edge ([`CubeSphere::fold`]).
//! - [`PlanetLayout`] places the six faces in one Minecraft level.
//!
//! Units and axes match the rest of the core: metres, seconds, right-handed,
//! Y-up. Grid x grows east, grid z grows south (Minecraft's convention).

mod cube_sphere;
mod face;
mod layout;
mod spin;

pub use cube_sphere::{CubeSphere, Folded, SurfacePoint};
pub use face::{Edge, Face, QuarterTurns};
pub use layout::PlanetLayout;
pub use spin::Spin;
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-frames/src/spin.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    const DAY: f64 = 1_200.0;

    fn spin() -> Spin {
        Spin {
            sidereal_period: DAY,
            phase_at_epoch: 0.3,
        }
    }

    #[test]
    fn resting_surface_point_moves_east_at_omega_r() {
        let s = spin();
        let r = 10_430.0;
        let t = 321.0;
        let (_, v) = s.to_inertial(DVec3::new(r, 0.0, 0.0), DVec3::ZERO, t);
        let expected_speed = TAU / DAY * r;
        assert!((v.length() - expected_speed).abs() < 1e-9);
        // East at +X is -Z in the body frame (see Face::E0), rotated to time t.
        let east = s.rotation_at(t) * DVec3::NEG_Z;
        assert!((v.normalize() - east).length() < 1e-12);
    }

    #[test]
    fn round_trip_is_exact() {
        let s = spin();
        let (p, v) = (
            DVec3::new(1_000.0, -2_000.0, 9_000.0),
            DVec3::new(3.0, 40.0, -5.0),
        );
        for t in [0.0, 17.0, 5_000.5, 1.0e8] {
            let (ip, iv) = s.to_inertial(p, v, t);
            let (bp, bv) = s.to_body(ip, iv, t);
            assert!(
                (bp - p).length() < 1e-8 && (bv - v).length() < 1e-10,
                "t={t}"
            );
        }
    }

    #[test]
    fn one_sidereal_day_brings_a_point_back() {
        let s = spin();
        let p = DVec3::new(10_430.0, 5.0, 0.0);
        let (a, _) = s.to_inertial(p, DVec3::ZERO, 100.0);
        let (b, _) = s.to_inertial(p, DVec3::ZERO, 100.0 + DAY);
        assert!((a - b).length() < 1e-8);
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `cannot find type Spin`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `spin.rs`, above `#[cfg(test)]`:

```rust
use glam::{DQuat, DVec3};
use std::f64::consts::TAU;

/// A planet spinning about its body-frame +Y axis. The body frame (where the
/// surface grids live) turns with the planet; the inertial frame is the
/// planet-centred, non-rotating frame. Axial tilt and the planet's own orbit
/// are added on top of this in M3.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Spin {
    /// Seconds per full turn relative to the stars.
    pub sidereal_period: f64,
    /// Rotation angle (radians, about +Y) at t = 0.
    pub phase_at_epoch: f64,
}

impl Spin {
    pub fn angular_velocity(self) -> DVec3 {
        DVec3::Y * (TAU / self.sidereal_period)
    }

    /// Body-to-inertial rotation at time `t`.
    pub fn rotation_at(self, t: f64) -> DQuat {
        let turns = (t / self.sidereal_period).rem_euclid(1.0);
        DQuat::from_rotation_y(self.phase_at_epoch + TAU * turns)
    }

    /// Body-frame position and velocity → inertial position and velocity.
    pub fn to_inertial(self, position: DVec3, velocity: DVec3, t: f64) -> (DVec3, DVec3) {
        let rotation = self.rotation_at(t);
        (
            rotation * position,
            rotation * (velocity + self.angular_velocity().cross(position)),
        )
    }

    /// Inertial position and velocity → body-frame position and velocity.
    pub fn to_body(self, position: DVec3, velocity: DVec3, t: f64) -> (DVec3, DVec3) {
        let inverse = self.rotation_at(t).inverse();
        let body_position = inverse * position;
        (
            body_position,
            inverse * velocity - self.angular_velocity().cross(body_position),
        )
    }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-frames`
Expected: `test result: ok. 25 passed`

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-frames
git commit -m "feat(frames): planet spin between body and inertial frames"
```

---

### Task 7: The surface ↔ orbit handoff (`PlanetFrame`)

**Files:**
- Create: `core/crates/we-frames/src/frame.rs`
- Modify: `core/crates/we-frames/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `lib.rs` with its final version:

```rust
//! Cube-sphere planets and the frames World Engine moves things between.
//!
//! - A planet is a cube whose six faces are square block grids ([`Face`]),
//!   projected onto a sphere with the equal-angle mapping ([`CubeSphere`]).
//! - Gameplay happens in the *surface frame*: one face's flat block grid,
//!   gravity along −Y, vanilla rules intact. Its axes (east, up, south) are a
//!   proper rotation of Minecraft's (X, Y, Z), so nothing is ever mirrored.
//! - The *body frame* is planet-centred and spins with the planet; the
//!   *inertial frame* is planet-centred and does not ([`Spin`], [`PlanetFrame`]).
//! - Walking off a face edge continues on the neighbouring face as if the
//!   cube were unfolded flat across that edge ([`CubeSphere::fold`]).
//! - [`PlanetLayout`] places the six faces in one Minecraft level.
//!
//! Units and axes match the rest of the core: metres, seconds, right-handed,
//! Y-up. Grid x grows east, grid z grows south (Minecraft's convention).

mod cube_sphere;
mod face;
mod frame;
mod layout;
mod spin;

pub use cube_sphere::{CubeSphere, Folded, SurfacePoint};
pub use face::{Edge, Face, QuarterTurns};
pub use frame::PlanetFrame;
pub use layout::PlanetLayout;
pub use spin::Spin;
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-frames/src/frame.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::face::Face;
    use proptest::prelude::*;

    fn home() -> PlanetFrame {
        PlanetFrame {
            sphere: CubeSphere::new(16_384),
            spin: Spin {
                sidereal_period: 1_200.0,
                phase_at_epoch: 0.0,
            },
        }
    }

    proptest! {
        // ROADMAP §4.3: surface → orbit → surface must agree within 1 cm and 1 mm/s.
        #[test]
        fn handoff_round_trip_is_within_a_centimetre(
            face in prop::sample::select(Face::ALL.to_vec()),
            x in 0.01f64..16_383.99,
            y in 0.0f64..3_000.0,
            z in 0.01f64..16_383.99,
            vx in -200.0f64..200.0,
            vy in -200.0f64..200.0,
            vz in -200.0f64..200.0,
            t in 0.0f64..1.0e7,
        ) {
            let frame = home();
            let p = SurfacePoint { face, x, y, z };
            let v = DVec3::new(vx, vy, vz);
            let (position, velocity) = frame.surface_to_inertial(p, v, t);
            let (q, w) = frame.inertial_to_surface(position, velocity, t);
            prop_assert_eq!(q.face, face);
            let error = frame.sphere.to_body(q) - frame.sphere.to_body(p);
            prop_assert!(error.length() < 0.01, "position error {} m", error.length());
            prop_assert!((w - v).length() < 0.001, "velocity error {} m/s", (w - v).length());
        }
    }

    #[test]
    fn standing_still_on_the_equator_is_moving_in_orbit_terms() {
        let frame = home();
        let p = SurfacePoint {
            face: Face::E0,
            x: 8_192.0,
            y: 0.0,
            z: 8_192.0,
        };
        let (_, v) = frame.surface_to_inertial(p, DVec3::ZERO, 0.0);
        let expected = std::f64::consts::TAU / 1_200.0 * frame.sphere.radius();
        assert!(
            (v.length() - expected).abs() < 1e-9,
            "{} vs {expected}",
            v.length()
        );
    }
}
```

- [ ] **Step 3: Run them to confirm they fail**

Run: `cd core && cargo test -p we-frames`
Expected: FAIL to compile: `cannot find type PlanetFrame`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `frame.rs`, above `#[cfg(test)]`:

```rust
use crate::cube_sphere::{CubeSphere, SurfacePoint};
use crate::spin::Spin;
use glam::DVec3;

/// A spinning cube-sphere planet: converts states between a face's surface
/// frame and the planet-centred inertial frame. This is the math behind the
/// surface ↔ orbit handoff (ROADMAP §4.3).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PlanetFrame {
    pub sphere: CubeSphere,
    pub spin: Spin,
}

impl PlanetFrame {
    /// Surface position and grid velocity (blocks/s east and south, m/s up)
    /// → inertial position and velocity at time `t`.
    pub fn surface_to_inertial(
        self,
        p: SurfacePoint,
        grid_velocity: DVec3,
        t: f64,
    ) -> (DVec3, DVec3) {
        let position = self.sphere.to_body(p);
        let velocity = self.sphere.velocity_to_body(p, grid_velocity);
        self.spin.to_inertial(position, velocity, t)
    }

    /// Inertial position and velocity → surface position and grid velocity.
    pub fn inertial_to_surface(
        self,
        position: DVec3,
        velocity: DVec3,
        t: f64,
    ) -> (SurfacePoint, DVec3) {
        let (body_position, body_velocity) = self.spin.to_body(position, velocity, t);
        let p = self.sphere.to_surface(body_position);
        (p, self.sphere.velocity_to_surface(p, body_velocity))
    }
}
```

- [ ] **Step 5: Run the full workspace checks**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
Expected: clean. `we_frames` reports 27 passed, `we_orbit` 20, `worldengine_core` 12, and `abi` 1.

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-frames
git commit -m "feat(frames): surface-to-inertial handoff transform"
```

---

### Task 8: Wrap library-load failures (`NativeCore.load`)

**Files:**
- Modify: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeCoreTest.java`
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java`

- [ ] **Step 1: Write the failing test.** In `NativeCoreTest.java`, replace the imports block (from `import java.nio.file.Path;` through `import org.junit.jupiter.api.Test;`) with:

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
```

and add this test right before `rejectsUseAfterClose`:

```java
    @Test
    void wrapsLoadFailuresInNativeCoreException(@TempDir Path dir) throws IOException {
        Path notALibrary = Files.writeString(dir.resolve("not-a-library.bin"), "hello");
        assertThrows(NativeCoreException.class, () -> NativeCore.load(notALibrary));
    }
```

- [ ] **Step 2: Run it to confirm it fails** (needs JDK 25)

Run: `cd mod && ./gradlew test --tests '*NativeCoreTest'`
Expected: FAIL: `wrapsLoadFailuresInNativeCoreException` gets `IllegalArgumentException` instead of `NativeCoreException`.

- [ ] **Step 3: Write the implementation.** In `NativeCore.java`, replace the first line of the body of `load`:

```java
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
```

with:

```java
        SymbolLookup lib;
        try {
            lib = SymbolLookup.libraryLookup(library, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new NativeCoreException("Could not load the World Engine core from " + library, e);
        }
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd mod && ./gradlew test --tests '*NativeCoreTest'`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Commit**

```bash
git add mod/src
git commit -m "fix(mod): report library-load failures as NativeCoreException"
```

---

### Task 9: Prune stale native caches (`NativeLibraryLoader`)

**Files:**
- Replace: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeLibraryLoaderTest.java`
- Replace: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeLibraryLoader.java`

- [ ] **Step 1: Write the failing tests.** Replace `NativeLibraryLoaderTest.java` with:

```java
package io.github.chabelabela.worldengine.nativebridge;

import static io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader.OVERRIDE_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLibraryLoaderTest {
    private String savedOverride;

    @BeforeEach
    void clearOverride() {
        savedOverride = System.getProperty(OVERRIDE_PROPERTY);
        System.clearProperty(OVERRIDE_PROPERTY);
    }

    @AfterEach
    void restoreOverride() {
        if (savedOverride != null) {
            System.setProperty(OVERRIDE_PROPERTY, savedOverride);
        }
    }

    @Test
    void overridePropertyWins(@TempDir Path cache) {
        System.setProperty(OVERRIDE_PROPERTY, "/some/where/libworldengine_core.so");
        assertEquals(Path.of("/some/where/libworldengine_core.so"), NativeLibraryLoader.locate(cache));
    }

    @Test
    void extractsBundledLibraryOnceAndLoadsIt() {
        // Not a @TempDir: the loaded library stays mapped for the JVM's lifetime,
        // and Windows cannot delete a loaded DLL.
        Path cache = Path.of(System.getProperty("worldengine.test.cacheDir", "build/test-native-cache"));
        Path first = NativeLibraryLoader.locate(cache);
        assertTrue(Files.isRegularFile(first));
        assertTrue(first.startsWith(cache));
        assertEquals(first, NativeLibraryLoader.locate(cache));
        assertEquals(NativeCore.EXPECTED_ABI_VERSION, NativeCore.load(first).abiVersion());
    }

    @Test
    void prunesOtherCacheFoldersButKeepsTheCurrentOne(@TempDir Path cache) throws IOException {
        Path current = Files.createDirectories(cache.resolve("aaaa")).resolve("libworldengine_core.so");
        Files.writeString(current, "current");
        Path stale = Files.createDirectories(cache.resolve("bbbb"));
        Files.writeString(stale.resolve("libworldengine_core.so"), "old");
        NativeLibraryLoader.pruneStaleCaches(cache, current);
        assertTrue(Files.exists(current));
        assertFalse(Files.exists(stale));
    }

    @Test
    void pruneLeavesTheCacheAloneForLibrariesOutsideIt(@TempDir Path cache) throws IOException {
        Path stale = Files.createDirectories(cache.resolve("bbbb"));
        NativeLibraryLoader.pruneStaleCaches(cache, Path.of("/elsewhere/libworldengine_core.so"));
        assertTrue(Files.exists(stale));
    }
}
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd mod && ./gradlew test --tests '*NativeLibraryLoaderTest'`
Expected: FAIL to compile: `cannot find symbol: method pruneStaleCaches`.

- [ ] **Step 3: Write the implementation.** Replace `NativeLibraryLoader.java` with:

```java
package io.github.chabelabela.worldengine.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/** Finds the core library: a dev override path, or the copy bundled in the mod jar. */
public final class NativeLibraryLoader {
    /** Set to an absolute path of a freshly built library to bypass the bundled copy. */
    public static final String OVERRIDE_PROPERTY = "worldengine.native.path";

    private NativeLibraryLoader() {}

    /**
     * Returns a filesystem path to the core library for this platform. The bundled
     * copy is extracted to {@code cacheDir/<content-hash>/} once and reused.
     */
    public static Path locate(Path cacheDir) {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        NativePlatform platform = NativePlatform.current();
        String resource = platform.resourcePath();
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new NativeCoreException(
                        "No World Engine native library bundled for " + platform.key() + " (looked for " + resource + ")");
            }
            byte[] bytes = in.readAllBytes();
            Path dir = cacheDir.resolve(shortSha256(bytes));
            Path target = dir.resolve(platform.libraryFileName());
            if (!Files.exists(target) || Files.size(target) != bytes.length) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "extract", ".tmp");
                try {
                    Files.write(tmp, bytes);
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    if (!Files.exists(target)) {
                        throw e; // a concurrent launch winning the race is fine; anything else is not
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract the World Engine native library", e);
        }
    }

    /**
     * Best-effort removal of the cache folders other versions of the core left
     * behind: every folder in {@code cacheDir} except the one holding
     * {@code library}. Does nothing if {@code library} is not in the cache (the
     * dev override). Failures are ignored: on Windows another running game may
     * still hold an old DLL open, and it will be pruned next time.
     */
    public static void pruneStaleCaches(Path cacheDir, Path library) {
        Path keep = library.getParent();
        if (keep == null || !cacheDir.equals(keep.getParent())) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheDir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && !entry.equals(keep)) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException e) {
            // best effort
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best effort
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    private static String shortSha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `cd mod && ./gradlew test --tests '*NativeLibraryLoaderTest'`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add mod/src
git commit -m "feat(mod): prune native caches left by other core versions"
```

---

### Task 10: Run without the core instead of crashing (`WorldEngineMod`)

**Files:**
- Replace: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java`

- [ ] **Step 1: Replace the entrypoint.** If the platform has no bundled library (e.g. Windows arm64), or the library fails to load, the mod logs one clear error and stays inactive. `/worldengine bodies` then explains why instead of the game crashing at launch.

```java
package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
import java.io.UncheckedIOException;
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

    private static NativeCore core; // set once in onInitialize; null if the core is unavailable
    private static NativeCore.Universe universe; // server thread only

    @Override
    public void onInitialize() {
        core = loadCore();
        if (core != null) {
            ServerLifecycleEvents.SERVER_STARTED.register(server -> {
                if (universe != null) {
                    universe.close(); // only if a stop event was ever missed
                }
                universe = core.newDemoUniverse();
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
                        .then(Commands.literal("bodies").executes(context -> printBodies(context.getSource())))));
    }

    /** Loads the Rust core, or logs why it can't and returns null so the game still starts. */
    private static NativeCore loadCore() {
        Path cache = FabricLoader.getInstance().getGameDir().resolve(".worldengine").resolve("natives");
        try {
            Path library = NativeLibraryLoader.locate(cache);
            NativeCore loaded = NativeCore.load(library);
            NativeLibraryLoader.pruneStaleCaches(cache, library);
            LOGGER.info("World Engine core loaded: ABI v{} from {}", loaded.abiVersion(), library);
            return loaded;
        } catch (NativeCoreException | UncheckedIOException e) {
            LOGGER.error("World Engine core unavailable, so the mod stays inactive: {}", e.getMessage());
            return null;
        }
    }

    public static boolean isCoreAvailable() {
        return core != null;
    }

    private static int printBodies(CommandSourceStack source) {
        if (universe == null) {
            source.sendFailure(Component.literal(core == null
                    ? "World Engine core is unavailable on this platform (see the log)"
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
Expected: `BUILD SUCCESSFUL`, 15 tests passed (4 platform, 7 core, 4 loader).

- [ ] **Step 3: Check the failure path by hand** (optional, needs a desktop)

Run the client with a bad override, e.g. `-Dworldengine.native.path=/nonexistent/libworldengine_core.so` added to the run configuration's VM options.
Expected: the game reaches the title screen. The log contains `World Engine core unavailable, so the mod stays inactive: Could not load the World Engine core from /nonexistent/...`. In a world, `/worldengine bodies` replies `World Engine core is unavailable on this platform (see the log)`.

- [ ] **Step 4: Commit**

```bash
git add mod/src
git commit -m "feat(mod): stay inactive with a clear error when the native core is unavailable"
```

---

### Task 11: Push, CI, and close out the entry tasks

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: Push and watch CI**

Run: `git push`
Expected: every job of the `CI` workflow is green. That is `core (5 targets)`, `mod` (with the 5-natives jar check), and `mod tests (windows-latest, macos-latest)`. This is the first run of Tasks 8–10 on JDK 25. If a job fails, read its log, fix the cause in a new commit, and push again.

- [ ] **Step 2: Tick off the entry tasks.** In `docs/ROADMAP.md`, M1 section, replace the two bullet groups "Entry tasks carried over from the M0 code review" and "Entry tasks from the M0 final review" with:

```markdown
- **Entry tasks from the M0 reviews:** done in M1a (see `docs/superpowers/plans/2026-11-20-m1a-foundations-and-frames.md`). Remaining items have new homes:
  - Thread-safe batched chunk generation is designed into M1c.
  - The clock source and debug-command permissions move to M3, with `we-sim`.
  - The period reduction of `t − epoch` was dropped after analysis. It can't beat the precision of an f64 `t`; M3 decides the clock representation.
```

- [ ] **Step 3: Commit and push**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): M1a entry tasks done"
git push
```

---

> **Post-review note (2026-11-20):** M1a was executed with subagents. Two quality reviews followed, each with fixes and a re-review. The repository, not this plan, is now the source of truth for these files.
> - `4c433e6`, `we-frames` hardening:
>   - No panics on bad input: `of_direction` is a pure match, `to_surface` and `inertial_to_surface` return `Option`, and `fold` returns `None` beyond one face width or for non-finite input.
>   - Integer cell helpers for seam mirroring: `cell_of` (half-open [0, N)) and `fold_cell`.
>   - `QuarterTurns` is normalised and yaw is wrapped. `CubeSphere::try_new` added.
>   - 41 tests in total.
> - `e73b61d`: grid lines **kink at seams** everywhere except an edge's midpoint (up to about 60° near corners).
>   - `QuarterTurns` is exact for block facing, yaw and grid velocities.
>   - Physical velocities must cross through the body frame (`velocity_to_body` on the old face, then `velocity_to_surface` on the new one).
> - `1ef2e42`, Java hardening:
>   - Pruning can never disable the mod, and only deletes `[0-9a-f]{16}` folders, never following symlinks or junctions.
>   - A failing universe creation can't crash server start.
>   - Load failures are logged with their cause and platform.

## Self-review against the M1a goals

| Goal | Task |
|---|---|
| `BodyId(u32)` newtype; parent and orbit as one `Link`; `Result` constructors | 1 |
| `we-ffi` built as an rlib, with an integration test | 2 |
| Cube faces, prograde equator, Minecraft-handed bases, exact seam fold with quarter turns | 3, 4 |
| Equal-angle projection with tested distortion bounds; 1 block = 1 m on the equator | 4 |
| Faces ↔ Minecraft level coordinates that agree with the fold | 5 |
| Body ↔ inertial spin; surface ↔ inertial handoff within 1 cm and 1 mm/s | 6, 7 |
| Degrade gracefully without a native library; prune stale caches | 8, 9, 10 |
| Verified on JDK 25 across 3 operating systems | 11 |

**Deliberately not here:** terrain (M1c); the FFI surface for `we-frames`, which is added in M1c/M1d when Java first needs it, so its shape follows real use; overlap bands and corner handling (M1e).
