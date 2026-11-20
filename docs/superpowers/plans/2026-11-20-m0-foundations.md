# M0 Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Rust simulation core and a Minecraft 26.3 Fabric mod work together on every platform. The mod loads the core through Java's Foreign Function & Memory API (FFM), and `/worldengine bodies` prints the moon's live orbital position.

**Architecture:** A Cargo workspace in `core/` holds `we-orbit` (Kepler orbits and the celestial hierarchy) and `we-ffi` (a C ABI, built as the `worldengine_core` dynamic library). A Gradle/Loom project in `mod/` builds that library, bundles it into the jar under `natives/<os>-<arch>/`, extracts it at runtime, and binds it with FFM. CI builds the library for 5 targets and assembles one jar that contains all of them. See [ROADMAP.md](../../ROADMAP.md) §4 for the bigger picture.

**Tech Stack:** Rust (stable, edition 2024) with `glam` 0.34 · Java 25 FFM (`java.lang.foreign`) · Minecraft 26.3 · Fabric Loader 0.20.1 · Fabric API 0.166.2+26.3 · Fabric Loom 1.19.4 (`net.fabricmc.fabric-loom`, no mappings) · Gradle 9.x · JUnit 5 · GitHub Actions

**What has been checked before execution (2026-11-20):**
- ✅ All Rust code below: `cargo test` (18 tests), `cargo clippy -D warnings` and `cargo fmt --check` all pass. The release library exports all 7 `we_*` symbols.
- ✅ The Java bridge (`NativePlatform`, `NativeLibraryLoader`, `NativeCore`) was run against the built library. It passed every assertion in the JUnit tests below. The run used JDK 21 with `--enable-preview`, the only difference being `allocateArray` in place of Java 25's `allocate(layout, count)`.
- ⚠️ **Not run yet:** the Gradle/Loom build, `WorldEngineMod` and the in-game steps. Java 25 wasn't available in the planning environment. They follow Fabric's 26.x documentation. If the [Fabric template generator](https://fabricmc.net/develop/template/) output for 26.3 differs from Task 5, **the template wins**: adjust and note it in the commit.

---

## Prerequisites

- Rust stable ≥ 1.85 (`rustup`), plus the platform C toolchain: MSVC Build Tools on Windows, Xcode Command Line Tools on macOS, `build-essential` on Linux.
- JDK 25 (e.g. Temurin 25) as the Gradle JVM.
- Any Gradle ≥ 8 on your PATH, used once to generate the wrapper in Task 6.
- IntelliJ IDEA 2025.3 or newer, if you use an IDE. Fabric says older versions mishandle 26.x mixins.

## File map

| Path | Responsibility |
|---|---|
| `.gitignore` | Ignore build output from Cargo, Gradle and CI |
| `core/Cargo.toml`, `core/rust-toolchain.toml` | Workspace, shared dependency versions, toolchain pin |
| `core/crates/we-orbit/src/lib.rs` | Crate root, frame convention docs |
| `core/crates/we-orbit/src/kepler.rs` | Kepler solver, `KeplerOrbit` state vectors (Y-up) |
| `core/crates/we-orbit/src/system.rs` | `CelestialSystem`: body hierarchy plus the demo home + moon |
| `core/crates/we-ffi/src/lib.rs` | The C ABI: opaque `WeUniverse` handle, status codes, panic guard |
| `mod/settings.gradle`, `mod/gradle.properties`, `mod/build.gradle` | Loom build; cargo integration; native bundling; test wiring |
| `mod/src/main/resources/fabric.mod.json` | Mod metadata |
| `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java` | Entrypoint: load the core, server lifecycle, `/worldengine bodies` |
| `mod/src/main/java/.../nativebridge/NativePlatform.java` | OS/arch → library file name and resource path |
| `mod/src/main/java/.../nativebridge/NativeCoreException.java` | Single exception type for bridge failures |
| `mod/src/main/java/.../nativebridge/NativeLibraryLoader.java` | Dev override, or extract the bundled library to a content-hash cache |
| `mod/src/main/java/.../nativebridge/NativeCore.java` | FFM downcall handles that mirror `we-ffi`, plus the `Universe` handle |
| `mod/src/test/java/.../nativebridge/*Test.java` | JUnit tests for the three bridge classes |
| `.github/workflows/ci.yml` | 5-target native matrix → one jar containing every native library |

(`...` = `io/github/chabelabela/worldengine`)

---

### Task 1: Repository skeleton and Rust workspace

**Files:**
- Create: `.gitignore`, `core/Cargo.toml`, `core/rust-toolchain.toml`
- Create: `core/crates/we-orbit/Cargo.toml`, `core/crates/we-orbit/src/lib.rs`
- Create: `core/crates/we-ffi/Cargo.toml`, `core/crates/we-ffi/src/lib.rs`

- [ ] **Step 1: Create `.gitignore`**

```text
# Rust
/core/target/

# Gradle / Loom
/mod/.gradle/
/mod/build/
/mod/run/
/mod/.idea/
*.iml

# CI scratch (natives gathered from the build matrix)
/natives-download/
/natives-all/

# OS
.DS_Store
```

- [ ] **Step 2: Create `core/Cargo.toml`**

```toml
[workspace]
resolver = "3"
members = ["crates/we-orbit", "crates/we-ffi"]

[workspace.package]
version = "0.1.0"
edition = "2024"
rust-version = "1.85"

[workspace.dependencies]
glam = "0.34"
we-orbit = { path = "crates/we-orbit" }

[profile.release]
debug = "line-tables-only"
```

- [ ] **Step 3: Create `core/rust-toolchain.toml`**

```toml
[toolchain]
channel = "stable"
components = ["rustfmt", "clippy"]
```

- [ ] **Step 4: Create `core/crates/we-orbit/Cargo.toml`**

```toml
[package]
name = "we-orbit"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
description = "Keplerian orbits and the celestial body hierarchy for World Engine"

[dependencies]
glam.workspace = true
```

- [ ] **Step 5: Create `core/crates/we-orbit/src/lib.rs` with only its crate docs**

```rust
//! Keplerian orbits and the celestial body hierarchy.
//!
//! Frame convention (shared by the whole core): right-handed, **Y-up**, metres,
//! seconds — the same axes as Minecraft. Orbits with zero inclination lie in
//! the XZ plane and their angular momentum points along +Y.
```

- [ ] **Step 6: Create `core/crates/we-ffi/Cargo.toml`**

```toml
[package]
name = "we-ffi"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
description = "C ABI of the World Engine core, consumed by the Fabric mod via Java FFM"

[lib]
name = "worldengine_core"
crate-type = ["cdylib"]

[dependencies]
we-orbit.workspace = true
```

- [ ] **Step 7: Create `core/crates/we-ffi/src/lib.rs` with only its crate docs**

```rust
//! C ABI of the World Engine core.
//!
//! Every function here is mirrored by `NativeCore.java` in the mod. Rules:
//! - Bump `WE_ABI_VERSION` on any signature or semantic change.
//! - Never let a panic cross the boundary: wrap work in `guard`.
//! - Return `i32` status codes (`WE_OK` or a negative `WE_ERR_*`).
```

- [ ] **Step 8: Build the empty workspace**

Run: `cd core && cargo build --workspace`
Expected: `Finished` with no errors. `core/Cargo.lock` now exists.

- [ ] **Step 9: Commit**

```bash
git add .gitignore core/
git commit -m "chore: add Rust workspace skeleton (we-orbit, we-ffi)"
```

---

### Task 2: Kepler orbits (`we-orbit::kepler`)

**Files:**
- Create: `core/crates/we-orbit/src/kepler.rs`
- Modify: `core/crates/we-orbit/src/lib.rs`

- [ ] **Step 1: Export the module from `lib.rs`.** Append the following below the crate docs:

```rust

pub mod kepler;

pub use kepler::{KeplerOrbit, solve_kepler};
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-orbit/src/kepler.rs` containing only this test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    const MU: f64 = 1.0672e9; // home planet, see CelestialSystem::demo

    fn rel_err(a: f64, b: f64) -> f64 {
        ((a - b) / b).abs()
    }

    #[test]
    fn kepler_solver_satisfies_equation() {
        for &e in &[0.0, 0.1, 0.5, 0.9, 0.99] {
            for i in 0..64 {
                let m = i as f64 * TAU / 64.0;
                let ecc = solve_kepler(m, e);
                let residual = (ecc - e * ecc.sin() - m.rem_euclid(TAU)).abs();
                assert!(residual < 1e-12, "e={e} M={m} residual={residual}");
            }
        }
    }

    #[test]
    fn circular_orbit_keeps_radius_and_speed() {
        let orbit = KeplerOrbit::circular(13_430.0);
        let expected_speed = (MU / 13_430.0).sqrt();
        for i in 0..20 {
            let (pos, vel) = orbit.state_at(MU, i as f64 * 17.3);
            assert!(rel_err(pos.length(), 13_430.0) < 1e-12);
            assert!(rel_err(vel.length(), expected_speed) < 1e-12);
        }
    }

    #[test]
    fn orbit_returns_to_start_after_one_period() {
        let orbit = KeplerOrbit {
            eccentricity: 0.3,
            inclination: 0.4,
            longitude_of_ascending_node: 1.1,
            argument_of_periapsis: 2.0,
            mean_anomaly_at_epoch: 0.7,
            ..KeplerOrbit::circular(60_000.0)
        };
        let period = orbit.period(MU);
        let start = orbit.position_at(MU, 123.0);
        let after = orbit.position_at(MU, 123.0 + period);
        assert!((after - start).length() < 1e-6 * 60_000.0);
    }

    #[test]
    fn eccentric_orbit_obeys_vis_viva() {
        let orbit = KeplerOrbit {
            eccentricity: 0.6,
            ..KeplerOrbit::circular(40_000.0)
        };
        for i in 0..50 {
            let (pos, vel) = orbit.state_at(MU, i as f64 * 97.0);
            let r = pos.length();
            let expected_v2 = MU * (2.0 / r - 1.0 / 40_000.0);
            assert!(rel_err(vel.length_squared(), expected_v2) < 1e-10);
        }
    }

    #[test]
    fn periapsis_and_apoapsis_distances() {
        let orbit = KeplerOrbit {
            eccentricity: 0.25,
            ..KeplerOrbit::circular(20_000.0)
        };
        let half_period = orbit.period(MU) / 2.0;
        assert!(rel_err(orbit.position_at(MU, 0.0).length(), 20_000.0 * 0.75) < 1e-12);
        assert!(rel_err(orbit.position_at(MU, half_period).length(), 20_000.0 * 1.25) < 1e-12);
    }

    #[test]
    fn equatorial_orbit_lies_in_xz_plane_and_turns_about_plus_y() {
        let orbit = KeplerOrbit::circular(13_430.0);
        let (pos, vel) = orbit.state_at(MU, 42.0);
        assert!(pos.y.abs() < 1e-9);
        let h = pos.cross(vel).normalize();
        assert!((h - DVec3::Y).length() < 1e-12, "angular momentum {h:?}");
    }

    #[test]
    fn polar_orbit_angular_momentum_is_horizontal() {
        let orbit = KeplerOrbit {
            inclination: PI / 2.0,
            ..KeplerOrbit::circular(13_430.0)
        };
        let (pos, vel) = orbit.state_at(MU, 10.0);
        let h = pos.cross(vel).normalize();
        assert!(h.y.abs() < 1e-12, "angular momentum {h:?}");
    }
}
```

- [ ] **Step 3: Run the tests to confirm they fail**

Run: `cd core && cargo test -p we-orbit`
Expected: FAIL to compile, e.g. `error[E0432]: unresolved imports kepler::KeplerOrbit, kepler::solve_kepler` and `cannot find function solve_kepler`.

- [ ] **Step 4: Write the implementation.** Insert this at the top of `kepler.rs`, above `#[cfg(test)]`:

```rust
use glam::{DQuat, DVec3};
use std::f64::consts::TAU;

/// Solve Kepler's equation `M = E - e·sin(E)` for the eccentric anomaly `E`.
///
/// Valid for elliptic orbits (`0 <= e < 1`). Newton–Raphson, converges to
/// machine precision in a handful of iterations.
pub fn solve_kepler(mean_anomaly: f64, eccentricity: f64) -> f64 {
    debug_assert!((0.0..1.0).contains(&eccentricity), "elliptic orbits only");
    let m = mean_anomaly.rem_euclid(TAU);
    let mut e_anom = if eccentricity < 0.8 {
        m
    } else {
        std::f64::consts::PI
    };
    for _ in 0..50 {
        let f = e_anom - eccentricity * e_anom.sin() - m;
        let f_prime = 1.0 - eccentricity * e_anom.cos();
        let step = f / f_prime;
        e_anom -= step;
        if step.abs() < 1e-15 {
            break;
        }
    }
    e_anom
}

/// Classical orbital elements of a closed (elliptic) orbit around a parent body.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct KeplerOrbit {
    /// Semi-major axis, metres.
    pub semi_major_axis: f64,
    /// Eccentricity, `0 <= e < 1`.
    pub eccentricity: f64,
    /// Inclination, radians.
    pub inclination: f64,
    /// Longitude of the ascending node, radians.
    pub longitude_of_ascending_node: f64,
    /// Argument of periapsis, radians.
    pub argument_of_periapsis: f64,
    /// Mean anomaly at `epoch`, radians.
    pub mean_anomaly_at_epoch: f64,
    /// Reference time for `mean_anomaly_at_epoch`, seconds.
    pub epoch: f64,
}

impl KeplerOrbit {
    /// A circular, equatorial orbit of the given radius, at periapsis at t = 0.
    pub fn circular(radius: f64) -> Self {
        Self {
            semi_major_axis: radius,
            eccentricity: 0.0,
            inclination: 0.0,
            longitude_of_ascending_node: 0.0,
            argument_of_periapsis: 0.0,
            mean_anomaly_at_epoch: 0.0,
            epoch: 0.0,
        }
    }

    /// Mean motion `n = sqrt(mu / a^3)`, radians per second.
    pub fn mean_motion(&self, mu: f64) -> f64 {
        (mu / self.semi_major_axis.powi(3)).sqrt()
    }

    /// Orbital period, seconds.
    pub fn period(&self, mu: f64) -> f64 {
        TAU / self.mean_motion(mu)
    }

    /// Position and velocity relative to the parent body at time `t` (seconds).
    /// `mu` is the parent's standard gravitational parameter (m^3/s^2).
    pub fn state_at(&self, mu: f64, t: f64) -> (DVec3, DVec3) {
        let a = self.semi_major_axis;
        let e = self.eccentricity;
        let n = self.mean_motion(mu);
        let mean_anomaly = self.mean_anomaly_at_epoch + n * (t - self.epoch);
        let ecc_anomaly = solve_kepler(mean_anomaly, e);
        let (sin_e, cos_e) = ecc_anomaly.sin_cos();
        let b = a * (1.0 - e * e).sqrt();
        let e_dot = n / (1.0 - e * cos_e);

        // Perifocal frame in classical z-up form: x toward periapsis,
        // y 90 degrees ahead in the direction of motion.
        let pos_pf = DVec3::new(a * (cos_e - e), b * sin_e, 0.0);
        let vel_pf = DVec3::new(-a * sin_e * e_dot, b * cos_e * e_dot, 0.0);

        let rot = DQuat::from_rotation_z(self.longitude_of_ascending_node)
            * DQuat::from_rotation_x(self.inclination)
            * DQuat::from_rotation_z(self.argument_of_periapsis);
        (zup_to_yup(rot * pos_pf), zup_to_yup(rot * vel_pf))
    }

    /// Position relative to the parent body at time `t`.
    pub fn position_at(&self, mu: f64, t: f64) -> DVec3 {
        self.state_at(mu, t).0
    }
}

/// Rotate a vector from the classical astrodynamics z-up frame into the
/// engine's Y-up frame (a proper rotation of -90 degrees about X).
fn zup_to_yup(v: DVec3) -> DVec3 {
    DVec3::new(v.x, v.z, -v.y)
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-orbit`
Expected: `test result: ok. 7 passed`

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-orbit
git commit -m "feat(core): Kepler solver and orbital state vectors in Y-up frame"
```

---

### Task 3: Celestial hierarchy and demo system (`we-orbit::system`)

**Files:**
- Create: `core/crates/we-orbit/src/system.rs`
- Modify: `core/crates/we-orbit/src/lib.rs`

- [ ] **Step 1: Export the module.** Replace `core/crates/we-orbit/src/lib.rs` with:

```rust
//! Keplerian orbits and the celestial body hierarchy.
//!
//! Frame convention (shared by the whole core): right-handed, **Y-up**, metres,
//! seconds — the same axes as Minecraft. Orbits with zero inclination lie in
//! the XZ plane and their angular momentum points along +Y.

pub mod kepler;
pub mod system;

pub use kepler::{KeplerOrbit, solve_kepler};
pub use system::{Body, BodyId, CelestialSystem};
```

- [ ] **Step 2: Write the failing tests.** Create `core/crates/we-orbit/src/system.rs` containing only:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn demo_has_home_and_moon() {
        let system = CelestialSystem::demo();
        assert_eq!(system.len(), 2);
        assert_eq!(system.body(0).name, "home");
        assert_eq!(system.body(1).name, "moon");
        assert_eq!(system.body(1).parent, Some(0));
    }

    #[test]
    fn root_stays_at_origin() {
        let system = CelestialSystem::demo();
        assert_eq!(system.position_at(0, 12_345.0), DVec3::ZERO);
    }

    #[test]
    fn moon_stays_60_km_from_home() {
        let system = CelestialSystem::demo();
        for i in 0..10 {
            let d = system.position_at(1, i as f64 * 300.0).length();
            assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
        }
    }

    #[test]
    fn moon_period_is_about_47_minutes() {
        let system = CelestialSystem::demo();
        let period = system.body(1).orbit.unwrap().period(system.body(0).mu);
        assert!((2_800.0..2_850.0).contains(&period), "period {period}");
    }

    #[test]
    #[should_panic(expected = "unknown parent body")]
    fn rejects_unknown_parent() {
        let mut system = CelestialSystem::new();
        system.add_root("star", 1.0, 1.0);
        system.add_orbiting("lost", 1.0, 1.0, 7, KeplerOrbit::circular(10.0));
    }
}
```

- [ ] **Step 3: Run the tests to confirm they fail**

Run: `cd core && cargo test -p we-orbit`
Expected: FAIL to compile with `cannot find type CelestialSystem` (and similar).

- [ ] **Step 4: Write the implementation.** Insert this at the top of `system.rs`, above `#[cfg(test)]`:

```rust
use crate::KeplerOrbit;
use glam::DVec3;

/// Index of a body inside a [`CelestialSystem`].
pub type BodyId = usize;

/// A star, planet or moon.
#[derive(Clone, Debug, PartialEq)]
pub struct Body {
    pub name: String,
    /// Standard gravitational parameter `G·M`, m^3/s^2.
    pub mu: f64,
    /// Mean radius, metres (1 block = 1 metre).
    pub radius: f64,
    /// `None` for the root body, which sits at the origin.
    pub parent: Option<BodyId>,
    /// `None` for the root body.
    pub orbit: Option<KeplerOrbit>,
}

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
        let home = system.add_root("home", 9.81 * home_radius * home_radius, home_radius);
        let moon_radius = 2_600.0;
        system.add_orbiting(
            "moon",
            1.62 * moon_radius * moon_radius,
            moon_radius,
            home,
            KeplerOrbit::circular(60_000.0),
        );
        system
    }

    pub fn add_root(&mut self, name: &str, mu: f64, radius: f64) -> BodyId {
        assert!(self.bodies.is_empty(), "a system has exactly one root body");
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            parent: None,
            orbit: None,
        })
    }

    pub fn add_orbiting(
        &mut self,
        name: &str,
        mu: f64,
        radius: f64,
        parent: BodyId,
        orbit: KeplerOrbit,
    ) -> BodyId {
        assert!(parent < self.bodies.len(), "unknown parent body {parent}");
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            parent: Some(parent),
            orbit: Some(orbit),
        })
    }

    fn push(&mut self, body: Body) -> BodyId {
        self.bodies.push(body);
        self.bodies.len() - 1
    }

    pub fn len(&self) -> usize {
        self.bodies.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bodies.is_empty()
    }

    pub fn body(&self, id: BodyId) -> &Body {
        &self.bodies[id]
    }

    /// Position of `id` relative to the root body at time `t`.
    pub fn position_at(&self, id: BodyId, t: f64) -> DVec3 {
        let body = &self.bodies[id];
        match (body.parent, body.orbit) {
            (Some(parent), Some(orbit)) => {
                self.position_at(parent, t) + orbit.position_at(self.bodies[parent].mu, t)
            }
            _ => DVec3::ZERO,
        }
    }
}
```

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `cd core && cargo test -p we-orbit`
Expected: `test result: ok. 12 passed`

- [ ] **Step 6: Commit**

```bash
git add core/crates/we-orbit
git commit -m "feat(core): celestial hierarchy with demo home planet and moon"
```

---

### Task 4: The C ABI (`we-ffi`)

**Files:**
- Modify: `core/crates/we-ffi/src/lib.rs`

- [ ] **Step 1: Write the failing tests.** Append this test module below the crate docs in `core/crates/we-ffi/src/lib.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    struct Handle(*mut WeUniverse);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe { we_universe_free(self.0) }
        }
    }

    fn demo() -> Handle {
        let h = we_universe_new_demo();
        assert!(!h.is_null());
        Handle(h)
    }

    #[test]
    fn reports_abi_version() {
        assert_eq!(we_abi_version(), 1);
    }

    #[test]
    fn demo_universe_has_two_bodies() {
        let u = demo();
        assert_eq!(unsafe { we_universe_body_count(u.0) }, 2);
    }

    #[test]
    fn moon_position_is_60_km_from_origin() {
        let u = demo();
        let mut out = [0.0f64; 3];
        unsafe {
            assert_eq!(we_universe_set_time(u.0, 500.0), WE_OK);
            assert_eq!(we_universe_body_position(u.0, 1, out.as_mut_ptr()), WE_OK);
        }
        let d = (out[0] * out[0] + out[1] * out[1] + out[2] * out[2]).sqrt();
        assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
    }

    #[test]
    fn reports_body_names() {
        let u = demo();
        let mut buf = [0u8; 16];
        let len = unsafe { we_universe_body_name(u.0, 1, buf.as_mut_ptr(), buf.len() as u32) };
        assert_eq!(len, 4);
        assert_eq!(&buf[..4], b"moon");
    }

    #[test]
    fn truncates_names_to_buffer() {
        let u = demo();
        let mut buf = [0u8; 2];
        let len = unsafe { we_universe_body_name(u.0, 0, buf.as_mut_ptr(), 2) };
        assert_eq!(len, 4);
        assert_eq!(&buf, b"ho");
    }

    #[test]
    fn rejects_bad_index_and_nulls() {
        let u = demo();
        let mut out = [0.0f64; 3];
        unsafe {
            assert_eq!(
                we_universe_body_position(u.0, 9, out.as_mut_ptr()),
                WE_ERR_BAD_INDEX
            );
            assert_eq!(
                we_universe_body_position(std::ptr::null(), 0, out.as_mut_ptr()),
                WE_ERR_NULL
            );
            assert_eq!(
                we_universe_body_position(u.0, 0, std::ptr::null_mut()),
                WE_ERR_NULL
            );
            assert_eq!(we_universe_set_time(std::ptr::null_mut(), 1.0), WE_ERR_NULL);
            assert_eq!(we_universe_body_count(std::ptr::null()), 0);
        }
    }
}
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `cd core && cargo test -p we-ffi`
Expected: FAIL to compile with `cannot find type WeUniverse`, `cannot find function we_abi_version` and similar.

- [ ] **Step 3: Write the implementation.** Insert this between the crate docs and `#[cfg(test)]`:

```rust
use std::panic::{AssertUnwindSafe, catch_unwind};
use we_orbit::CelestialSystem;

pub const WE_ABI_VERSION: u32 = 1;
pub const WE_OK: i32 = 0;
pub const WE_ERR_NULL: i32 = -1;
pub const WE_ERR_BAD_INDEX: i32 = -2;
pub const WE_ERR_PANIC: i32 = -99;

/// Opaque handle owned by the Java side.
pub struct WeUniverse {
    system: CelestialSystem,
    time: f64,
}

fn guard(f: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(WE_ERR_PANIC)
}

#[unsafe(no_mangle)]
pub extern "C" fn we_abi_version() -> u32 {
    WE_ABI_VERSION
}

/// Create the demo universe (home planet + moon). Returns null on failure.
/// Free with [`we_universe_free`].
#[unsafe(no_mangle)]
pub extern "C" fn we_universe_new_demo() -> *mut WeUniverse {
    catch_unwind(|| {
        Box::into_raw(Box::new(WeUniverse {
            system: CelestialSystem::demo(),
            time: 0.0,
        }))
    })
    .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`,
/// and must not be used after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_free(universe: *mut WeUniverse) {
    if !universe.is_null() {
        drop(unsafe { Box::from_raw(universe) });
    }
}

/// Set the simulation time in seconds.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_set_time(universe: *mut WeUniverse, seconds: f64) -> i32 {
    let Some(universe) = (unsafe { universe.as_mut() }) else {
        return WE_ERR_NULL;
    };
    universe.time = seconds;
    WE_OK
}

/// Number of celestial bodies; 0 for a null handle.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_universe_body_count(universe: *const WeUniverse) -> u32 {
    unsafe { universe.as_ref() }.map_or(0, |u| u.system.len() as u32)
}

/// Write the position (metres, Y-up, relative to the root body) of body
/// `index` at the current time into `out_xyz[0..3]`.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`;
/// `out_xyz` must be null or valid for writing 3 doubles.
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
    let index = index as usize;
    if index >= universe.system.len() {
        return WE_ERR_BAD_INDEX;
    }
    guard(|| {
        let p = universe.system.position_at(index, universe.time);
        let out = unsafe { std::slice::from_raw_parts_mut(out_xyz, 3) };
        out.copy_from_slice(&[p.x, p.y, p.z]);
        WE_OK
    })
}

/// Copy body `index`'s UTF-8 name (no terminator) into `buf`, truncated to
/// `buf_len` bytes. Returns the full name length in bytes, or a `WE_ERR_*`.
///
/// # Safety
/// `universe` must be null or a live pointer from `we_universe_new_demo`;
/// `buf` must be valid for writing `buf_len` bytes (may be null if `buf_len` is 0).
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
    let index = index as usize;
    if index >= universe.system.len() {
        return WE_ERR_BAD_INDEX;
    }
    if buf.is_null() && buf_len > 0 {
        return WE_ERR_NULL;
    }
    guard(|| {
        let name = universe.system.body(index).name.as_bytes();
        let n = name.len().min(buf_len as usize);
        if n > 0 {
            unsafe { std::ptr::copy_nonoverlapping(name.as_ptr(), buf, n) };
        }
        name.len() as i32
    })
}
```

- [ ] **Step 4: Run the full workspace checks**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
Expected: no formatting diff, no clippy warnings, `test result: ok. 6 passed` for `worldengine_core` and `ok. 12 passed` for `we_orbit`.

- [ ] **Step 5: Build the release library and check its exports**

Run (Linux): `cd core && cargo build --release -p we-ffi && nm -D --defined-only target/release/libworldengine_core.so | grep ' T we_'`
Run (macOS): `cd core && cargo build --release -p we-ffi && nm -gU target/release/libworldengine_core.dylib | grep _we_`
Expected: 7 symbols: `we_abi_version`, `we_universe_body_count`, `we_universe_body_name`, `we_universe_body_position`, `we_universe_free`, `we_universe_new_demo`, `we_universe_set_time`.

- [ ] **Step 6: Commit**

```bash
git add core/
git commit -m "feat(core): C ABI with panic guard, status codes and demo universe handle"
```

---

### Task 5: Fabric mod scaffold

**Files:**
- Create: `mod/settings.gradle`, `mod/gradle.properties`, `mod/build.gradle`
- Create: `mod/gradlew`, `mod/gradlew.bat`, `mod/gradle/wrapper/*` (generated)
- Create: `mod/src/main/resources/fabric.mod.json`
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java` (temporary version)

- [ ] **Step 1: Create `mod/settings.gradle`**

```groovy
pluginManagement {
    repositories {
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = 'world-engine'
```

- [ ] **Step 2: Generate the Gradle wrapper.** Run this *before* `build.gradle` exists, so an older Gradle doesn't try to apply Loom:

Run: `cd mod && gradle wrapper --gradle-version 9.9.2 && git update-index --add --chmod=+x gradlew`
Expected: `gradlew`, `gradlew.bat` and `gradle/wrapper/` are created. (Gradle 9.9.2 was current on 2026-11-20. Fabric requires ≥ 9.4 for 26.x. Alternatively, copy these files from the Fabric template generator.)

- [ ] **Step 3: Create `mod/gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

# Versions current on 2026-11-20. Check https://fabricmc.net/develop/ before bumping.
minecraft_version=26.3
loader_version=0.20.1
loom_version=1.19.4
fabric_api_version=0.166.2+26.3
junit_version=5.13.4

mod_version=0.1.0
maven_group=io.github.chabelabela.worldengine
archives_base_name=world-engine
```

- [ ] **Step 4: Create `mod/build.gradle`**

```groovy
plugins {
    // Minecraft 26.1+ is unobfuscated: use the non-remapping Loom plugin, no mappings.
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
}

version = project.mod_version
group = project.maven_group

base {
    archivesName = project.archives_base_name
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"

    testImplementation platform("org.junit:junit-bom:${project.junit_version}")
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 25
}

java {
    withSourcesJar()
}

// ---------------------------------------------------------------------------
// Rust core: build it, bundle it as natives/<os>-<arch>/<lib> inside the jar.
// Must match NativePlatform.java.
// ---------------------------------------------------------------------------
def osName = System.getProperty('os.name').toLowerCase(Locale.ROOT)
def osArch = System.getProperty('os.arch').toLowerCase(Locale.ROOT)
def hostOs = osName.startsWith('windows') ? 'windows' : (osName.startsWith('mac') ? 'macos' : 'linux')
def hostArch = (osArch in ['amd64', 'x86_64']) ? 'x86_64' : ((osArch in ['aarch64', 'arm64']) ? 'aarch64' : osArch)
def hostKey = "${hostOs}-${hostArch}"
def hostLibName = [windows: 'worldengine_core.dll', macos: 'libworldengine_core.dylib'].get(hostOs, 'libworldengine_core.so')
def hostLibrary = file("../core/target/release/${hostLibName}")

def cargoBuild = tasks.register('cargoBuild', Exec) {
    description = 'Builds the Rust core (we-ffi) for the host platform.'
    workingDir = file('../core')
    commandLine 'cargo', 'build', '--release', '-p', 'we-ffi'
    inputs.dir(file('../core/crates'))
    inputs.files(file('../core/Cargo.toml'), file('../core/Cargo.lock'))
    outputs.file(hostLibrary)
}

def nativeStaging = layout.buildDirectory.dir('native-staging')
def stageHostNative = tasks.register('stageHostNative', Copy) {
    dependsOn cargoBuild
    from(hostLibrary)
    into(nativeStaging.map { it.dir("natives/${hostKey}") })
}

// CI passes -PnativesDir=<dir containing natives/<key>/<lib> for every platform>.
def nativesDir = providers.gradleProperty('nativesDir')

processResources {
    inputs.property 'version', project.version
    filesMatching('fabric.mod.json') {
        expand 'version': project.version
    }
    if (nativesDir.present) {
        from(nativesDir.get())
    } else {
        dependsOn stageHostNative
        from(nativeStaging)
    }
}

test {
    useJUnitPlatform()
    dependsOn cargoBuild
    jvmArgs '--enable-native-access=ALL-UNNAMED'
    systemProperty 'worldengine.native.path', hostLibrary.absolutePath
}

loom {
    runs {
        configureEach {
            // Use the freshly built library in dev instead of the extracted copy.
            vmArg "-Dworldengine.native.path=${hostLibrary.absolutePath}"
            vmArg '--enable-native-access=ALL-UNNAMED'
        }
    }
}

tasks.matching { it.name in ['runClient', 'runServer'] }.configureEach {
    dependsOn cargoBuild
}
```

- [ ] **Step 5: Create `mod/src/main/resources/fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "worldengine",
  "version": "${version}",
  "name": "World Engine",
  "description": "A physically simulated universe for Minecraft.",
  "environment": "*",
  "entrypoints": {
    "main": [
      "io.github.chabelabela.worldengine.WorldEngineMod"
    ]
  },
  "depends": {
    "fabricloader": ">=0.20.1",
    "minecraft": "~26.3",
    "java": ">=25",
    "fabric-api": "*"
  }
}
```

- [ ] **Step 6: Create a temporary entrypoint.** Task 9 replaces it.

```java
package io.github.chabelabela.worldengine;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldEngineMod implements ModInitializer {
    public static final String MOD_ID = "worldengine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("World Engine loading");
    }
}
```

- [ ] **Step 7: Build**

Run: `cd mod && ./gradlew build`
Expected: `BUILD SUCCESSFUL`. `cargoBuild` runs, and `build/libs/world-engine-0.1.0.jar` contains `natives/<your-os>-<arch>/<lib>`. Check with: `unzip -l build/libs/world-engine-0.1.0.jar | grep natives/`. The `test` task reports `NO-SOURCE`.

- [ ] **Step 8: Launch the game**

Run: `cd mod && ./gradlew runClient`
Expected: Minecraft 26.3 starts. The log contains `World Engine loading`, and the Mods screen lists *World Engine*. Close the game.

- [ ] **Step 9: Commit**

```bash
git add mod/
git commit -m "chore(mod): Fabric 26.3 scaffold with cargo build and native bundling"
```

---

### Task 6: Platform detection (`NativePlatform`)

**Files:**
- Create: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativePlatformTest.java`
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCoreException.java`
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativePlatform.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NativePlatformTest {
    @Test
    void mapsLinuxAmd64() {
        NativePlatform p = NativePlatform.detect("Linux", "amd64");
        assertEquals("linux-x86_64", p.key());
        assertEquals("libworldengine_core.so", p.libraryFileName());
    }

    @Test
    void mapsWindows() {
        assertEquals("worldengine_core.dll", NativePlatform.detect("Windows 11", "amd64").libraryFileName());
    }

    @Test
    void mapsAppleSilicon() {
        assertEquals("/natives/macos-aarch64/libworldengine_core.dylib",
                NativePlatform.detect("Mac OS X", "aarch64").resourcePath());
    }

    @Test
    void rejectsUnsupportedPlatforms() {
        assertThrows(NativeCoreException.class, () -> NativePlatform.detect("SunOS", "sparcv9"));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mod && ./gradlew test --tests '*NativePlatformTest'`
Expected: FAIL with `compileTestJava` errors: `cannot find symbol: class NativePlatform`.

- [ ] **Step 3: Create `NativeCoreException.java`**

```java
package io.github.chabelabela.worldengine.nativebridge;

/** Any failure loading or calling the Rust core. */
public final class NativeCoreException extends RuntimeException {
    public NativeCoreException(String message) {
        super(message);
    }

    public NativeCoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Create `NativePlatform.java`**

```java
package io.github.chabelabela.worldengine.nativebridge;

import java.util.Locale;

/** The OS/CPU pair that selects which bundled native library to load. */
public record NativePlatform(String os, String arch) {

    public static NativePlatform current() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    public static NativePlatform detect(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.startsWith("windows")) {
            os = "windows";
        } else if (name.startsWith("mac") || name.startsWith("darwin")) {
            os = "macos";
        } else if (name.startsWith("linux")) {
            os = "linux";
        } else {
            throw new NativeCoreException("Unsupported OS: " + osName);
        }
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new NativeCoreException("Unsupported CPU architecture: " + osArch);
        };
        return new NativePlatform(os, arch);
    }

    /** e.g. {@code linux-x86_64}; also the CI artifact and resource folder name. */
    public String key() {
        return os + "-" + arch;
    }

    public String libraryFileName() {
        return switch (os) {
            case "windows" -> "worldengine_core.dll";
            case "macos" -> "libworldengine_core.dylib";
            default -> "libworldengine_core.so";
        };
    }

    /** Classpath location of the bundled library inside the mod jar. */
    public String resourcePath() {
        return "/natives/" + key() + "/" + libraryFileName();
    }
}
```

- [ ] **Step 5: Run it to confirm it passes**

Run: `cd mod && ./gradlew test --tests '*NativePlatformTest'`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 6: Commit**

```bash
git add mod/src
git commit -m "feat(mod): native platform detection"
```

---

### Task 7: FFM bindings (`NativeCore`)

**Files:**
- Create: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeCoreTest.java`
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java`

- [ ] **Step 1: Write the failing test.** The Gradle `test` task sets `worldengine.native.path` to the freshly built library (see `build.gradle`).

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NativeCoreTest {
    /** Set by the Gradle test task to the freshly built library (see build.gradle). */
    private static final String LIBRARY_PROPERTY = "worldengine.native.path";

    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty(LIBRARY_PROPERTY);
        assertNotNull(path, "the Gradle test task must set " + LIBRARY_PROPERTY);
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void reportsAbiVersion() {
        assertEquals(NativeCore.EXPECTED_ABI_VERSION, core.abiVersion());
    }

    @Test
    void demoUniverseHasHomeAndMoon() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            assertEquals(2, u.bodyCount());
            assertEquals("home", u.bodyName(0));
            assertEquals("moon", u.bodyName(1));
        }
    }

    @Test
    void moonOrbitsAt60KmInTheXzPlane() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            u.setTime(500.0);
            double[] p = u.bodyPosition(1);
            assertEquals(60_000.0, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-6);
            assertEquals(0.0, p[1], 1e-9);
        }
    }

    @Test
    void rejectsBadIndex() {
        try (NativeCore.Universe u = core.newDemoUniverse()) {
            assertThrows(NativeCoreException.class, () -> u.bodyPosition(9));
        }
    }

    @Test
    void rejectsUseAfterClose() {
        NativeCore.Universe u = core.newDemoUniverse();
        u.close();
        u.close(); // idempotent
        assertThrows(IllegalStateException.class, u::bodyCount);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mod && ./gradlew test --tests '*NativeCoreTest'`
Expected: FAIL with `cannot find symbol: class NativeCore`.

- [ ] **Step 3: Write the implementation.** Every `FunctionDescriptor` must match the Rust signature in `we-ffi`. A mismatch is undefined behavior, not a compile error.

```java
package io.github.chabelabela.worldengine.nativebridge;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

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
    public static final int EXPECTED_ABI_VERSION = 1;

    private final MethodHandle abiVersionFn;
    private final MethodHandle newDemoFn;
    private final MethodHandle freeFn;
    private final MethodHandle setTimeFn;
    private final MethodHandle bodyCountFn;
    private final MethodHandle bodyPositionFn;
    private final MethodHandle bodyNameFn;

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
    }

    /** Load the library at {@code library} for the lifetime of the JVM and check its ABI version. */
    public static NativeCore load(Path library) {
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        NativeCore core = new NativeCore(lib);
        int abi = core.abiVersion();
        if (abi != EXPECTED_ABI_VERSION) {
            throw new NativeCoreException("World Engine core at " + library + " has ABI v" + abi
                    + ", but this mod needs v" + EXPECTED_ABI_VERSION);
        }
        return core;
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

    /** Owns one native universe. Not thread-safe: use from the server thread only. */
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

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd mod && ./gradlew test --tests '*NativeCoreTest'`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add mod/src
git commit -m "feat(mod): FFM bindings for the core C ABI"
```

---

### Task 8: Native library extraction (`NativeLibraryLoader`)

**Files:**
- Create: `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativeLibraryLoaderTest.java`
- Create: `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeLibraryLoader.java`

- [ ] **Step 1: Write the failing test.** `extractsBundledLibraryOnceAndLoadsIt` reads the copy that `processResources` bundled under `natives/<key>/`. It works because main resources are on the test classpath.

```java
package io.github.chabelabela.worldengine.nativebridge;

import static io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader.OVERRIDE_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void extractsBundledLibraryOnceAndLoadsIt(@TempDir Path cache) {
        Path first = NativeLibraryLoader.locate(cache);
        assertTrue(Files.isRegularFile(first));
        assertTrue(first.startsWith(cache));
        assertEquals(first, NativeLibraryLoader.locate(cache));
        assertEquals(NativeCore.EXPECTED_ABI_VERSION, NativeCore.load(first).abiVersion());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd mod && ./gradlew test --tests '*NativeLibraryLoaderTest'`
Expected: FAIL with `cannot find symbol: class NativeLibraryLoader`.

- [ ] **Step 3: Write the implementation**

```java
package io.github.chabelabela.worldengine.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
            if (!Files.exists(target)) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "extract", ".tmp");
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.deleteIfExists(tmp);
                    if (!Files.exists(target)) {
                        throw e; // a concurrent launch winning the race is fine; anything else is not
                    }
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not extract the World Engine native library", e);
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

- [ ] **Step 4: Run all tests**

Run: `cd mod && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, 11 tests passed. A JDK warning about a "restricted method" may appear. It is expected (ROADMAP risk #11) and harmless.

- [ ] **Step 5: Commit**

```bash
git add mod/src
git commit -m "feat(mod): extract bundled native library to a content-hash cache"
```

---

### Task 9: Wire the core into the game (`/worldengine bodies`)

**Files:**
- Modify: `mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineMod.java` (replace entirely)

- [ ] **Step 1: Replace the entrypoint**

```java
package io.github.chabelabela.worldengine;

import com.mojang.brigadier.Command;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeLibraryLoader;
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

    private static NativeCore core;
    private static NativeCore.Universe universe; // server thread only

    @Override
    public void onInitialize() {
        Path cache = FabricLoader.getInstance().getGameDir().resolve(".worldengine").resolve("natives");
        Path library = NativeLibraryLoader.locate(cache);
        core = NativeCore.load(library);
        LOGGER.info("World Engine core loaded: ABI v{} from {}", core.abiVersion(), library);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> universe = core.newDemoUniverse());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (universe != null) {
                universe.close();
                universe = null;
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("worldengine")
                        .then(Commands.literal("bodies").executes(context -> printBodies(context.getSource())))));
    }

    private static int printBodies(CommandSourceStack source) {
        if (universe == null) {
            source.sendFailure(Component.literal("World Engine universe is not running"));
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
Expected: `BUILD SUCCESSFUL`, 11 tests passed. If a Mojang-named API differs in 26.3 (`CommandSourceStack#sendSuccess`, `Level#getGameTime`), check the [Fabric commands page](https://docs.fabricmc.net/develop/commands/basics), fix it, and record the change in the commit message.

- [ ] **Step 3: Verify in the client**

Run: `cd mod && ./gradlew runClient`, create a new Creative world, and run `/worldengine bodies`.
Expected log line: `World Engine core loaded: ABI v1 from ...`. Expected chat output:
```
home: x=0.0 y=0.0 z=0.0 m, |r|=0.0 m
moon: x=… y=0.0 z=… m, |r|=60000.0 m
```
Wait about 10 s and run it again. The moon's x and z values change (by about 1.3 km), while `|r|` stays `60000.0` and `y` stays `0.0`.

- [ ] **Step 4: Verify on a dedicated server**

Run: `cd mod && ./gradlew runServer` (on first run, set `eula=true` in `mod/run/eula.txt` and run again). Then type `worldengine bodies` into the server console.
Expected: the same two lines as Step 3.

- [ ] **Step 5: Commit**

```bash
git add mod/src
git commit -m "feat(mod): load the core at startup and add /worldengine bodies"
```

---

### Task 10: CI for 5 platforms and the jar

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Create the workflow**

```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  core:
    name: core (${{ matrix.key }})
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        include:
          - { os: ubuntu-latest,    key: linux-x86_64,   target: x86_64-unknown-linux-gnu,  lib: libworldengine_core.so,    test: true }
          - { os: ubuntu-24.04-arm, key: linux-aarch64,  target: aarch64-unknown-linux-gnu, lib: libworldengine_core.so,    test: true }
          - { os: windows-latest,   key: windows-x86_64, target: x86_64-pc-windows-msvc,    lib: worldengine_core.dll,      test: true }
          - { os: macos-latest,     key: macos-aarch64,  target: aarch64-apple-darwin,      lib: libworldengine_core.dylib, test: true }
          - { os: macos-latest,     key: macos-x86_64,   target: x86_64-apple-darwin,       lib: libworldengine_core.dylib, test: false }
    defaults:
      run:
        working-directory: core
    steps:
      - uses: actions/checkout@v4
      - run: rustup target add ${{ matrix.target }}
      - name: Lint
        if: matrix.key == 'linux-x86_64'
        run: |
          cargo fmt --all --check
          cargo clippy --workspace --all-targets -- -D warnings
      - name: Test
        if: matrix.test
        run: cargo test --workspace
      - name: Build native library
        run: cargo build --release -p we-ffi --target ${{ matrix.target }}
      - uses: actions/upload-artifact@v4
        with:
          name: native-${{ matrix.key }}
          path: core/target/${{ matrix.target }}/release/${{ matrix.lib }}
          if-no-files-found: error

  mod:
    needs: core
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: actions/download-artifact@v4
        with:
          pattern: native-*
          path: natives-download
      - name: Arrange natives as natives/<key>/<lib>
        run: |
          for dir in natives-download/native-*; do
            key="${dir#natives-download/native-}"
            mkdir -p "natives-all/natives/$key"
            cp "$dir"/* "natives-all/natives/$key/"
          done
          find natives-all -type f
      - name: Build and test the mod (bundles all platforms)
        working-directory: mod
        run: ./gradlew build -PnativesDir=../natives-all
      - uses: actions/upload-artifact@v4
        with:
          name: world-engine-mod
          path: mod/build/libs/*.jar
```

- [ ] **Step 2: Push and watch the run**

Run: `git add .github && git commit -m "ci: build natives for 5 targets and assemble the mod jar" && git push`
Expected: 5 `core (…)` jobs and one `mod` job, all green.

- [ ] **Step 3: Check the jar contents**

Download the `world-engine-mod` artifact, then run: `unzip -l world-engine-0.1.0.jar | grep natives/`
Expected: 5 libraries:
```
natives/linux-aarch64/libworldengine_core.so
natives/linux-x86_64/libworldengine_core.so
natives/macos-aarch64/libworldengine_core.dylib
natives/macos-x86_64/libworldengine_core.dylib
natives/windows-x86_64/worldengine_core.dll
```

---

### Task 11: Milestone exit check and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Run the cross-platform smoke test (the M0 exit criterion).** On Windows, macOS and Linux, install Fabric Loader 0.20.1 for 26.3 and Fabric API 0.166.2+26.3. Add the CI-built `world-engine-0.1.0.jar` and run `/worldengine bodies` in a new world.
Expected: the output from Task 9 Step 3 on all three operating systems.

- [ ] **Step 2: Add a "Building" section to `README.md`**

````markdown
## Building

Prerequisites: Rust stable ≥ 1.85, JDK 25.

```bash
cd core && cargo test --workspace     # Rust core
cd mod  && ./gradlew build            # builds the core for this machine, runs all tests, makes the jar
cd mod  && ./gradlew runClient        # dev client using the freshly built core
```

`build/libs/world-engine-<version>.jar` bundles the core for the machine that built it. CI produces a jar that bundles all five platforms.
````

- [ ] **Step 3: Commit and push**

```bash
git add README.md
git commit -m "docs: build instructions"
git push
```

---

## Self-review against the M0 exit criteria (ROADMAP §5)

| Exit criterion | Covered by |
|---|---|
| Rust workspace with `we-orbit` (Kepler, hierarchy, demo system) | Tasks 1–3 |
| `we-ffi` C ABI that catches panics and returns status codes | Task 4 |
| Mod extracts the right native library and binds it with FFM | Tasks 6–8 |
| `/worldengine bodies` prints the live moon position | Task 9 |
| CI: natives for 5 targets plus one jar containing them all | Task 10 |
| Works on Windows, macOS and Linux from the CI jar | Task 11 |

**Deliberately not in M0:** world generation, frames and ships (M1/M2), and `cargo zigbuild` for an old-glibc baseline (before the first public release; ROADMAP §6).

**Next:** M1. Start with the frame-handoff spike and its design record (`docs/adr/0001-frames-and-handoff.md`), as ROADMAP §5 describes.
