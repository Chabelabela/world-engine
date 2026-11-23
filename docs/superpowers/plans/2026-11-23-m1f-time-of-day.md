# M1f Per-Planet Time of Day Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The sun rises and sets with the planet's rotation ([M1 overview](2026-11-20-m1-overview.md), ROADMAP §5 M1, [ADR 0001](../../adr/0001-frames-and-handoff.md) "M1f"):
- **Local time.** The world's day clock keeps the time at the home planet's reference meridian: the centre of face E1, where the world spawns. Elsewhere the sun is ahead by the longitude's share of a day. When it is noon at spawn, the sun is setting on E2, it is midnight on E3, and it is rising on E0.
- **The sky turns about the planet's axis.** Minecraft draws the sun, moon and stars as an observer on the equator sees them. The sky is turned to the camera's latitude and grid, so over the North face's edge with E1 (45° north) the noon sun stands 45° high due south, and the stars circle a pole 45° up in the north.
- **Each planet keeps its own day.** The moon turns once per orbit, as tidally locked moons do: its day lasts eight Minecraft days.
- **Time-driven gameplay follows local time** wherever Minecraft samples it at a position: sky colour and light on the client, the sun's angle for daylight detectors, undead burning, bees, villager schedules, eyeblossoms and the rest of the day timeline.

**Architecture:**
- **Rust (`we-frames`, `we-ffi`):** `CubeSphere::local_sky` gives a surface point's longitude, latitude and *tilt*: the rotation from Minecraft's equatorial sky into the point's grid axes. The planet spins about its body +Y axis (the North face's normal), and the sun lies in its equatorial plane (axial tilt and orbits come in M3). `we_local_sky` exposes it (ABI v5).
- **Java, pure (`planet`):** `PlanetSpec.dayTicks`, and `LocalTime`, which turns the world clock and a longitude into local day ticks.
- **Minecraft, both sides (`level`, `mixin`):** 26.3 drives the day through *timelines*, data-driven tracks of environment attributes sampled from a world clock. In the universe level:
  - its timelines read `UniverseTime.LocalClocks`, whose clocks give the local time where an attribute is being sampled;
  - each timeline attribute also gets a positional layer that changes nothing, so Minecraft samples it per position;
  - three mixins wire it in: the level's timeline layers, the position being sampled, and skipping a track's per-tick cache for local samples.
  - Values asked for without a position keep the world clock, which is the reference meridian's time.
- **Minecraft, client (`client`, `mixin.client`):** `SkyRendererMixin` turns the sun, moon, stars and sunrise glow by the camera's tilt.
- **`/worldengine where`** reports the local time and the place's latitude and longitude.
- **A client GameTest** creates a World Engine world in a real client and checks where the sky puts the noon sun. CI runs it under a virtual display and keeps its screenshots.

**Tech Stack:** Rust (glam, libm) · Java 25 FFM · Minecraft 26.3 + Fabric API 0.166.2 (GameTest API v1, client GameTest API v1) · Mixin with MixinExtras 0.5 (Fabric Loader 0.20.1) · JUnit 5

**What has been checked before execution (2026-11-23):** every file in this plan was built and run from a scratch worktree of `db66e46`.
- ✅ **Rust:** `cargo fmt`, `clippy -D warnings` and the whole workspace's tests, including 6 new `we-frames` sky tests. Among them: at points spread over all six faces, corners included, the tilted sky's pole lies along the planet's spin axis, and its noon sun over the equator at that longitude.
- ✅ **Pure Java:** the 59 `worldengine.planet` JUnit tests pass on JDK 21, with the new `LocalTimeTest`.
- ✅ **Minecraft side, in CI** ([mc-api run #17](https://github.com/Chabelabela/world-engine/actions/runs/45172498127); a temporary workflow, removed afterwards): the whole mod compiled against Minecraft 26.3, and `./gradlew build` passed. That includes the native `NativeLocalSkyTest` and all 24 GameTests, among them 4 new ones on Minecraft's own day timeline:
  - noon on E1, midnight on E3, and sunset on E2 mirroring sunrise on E0;
  - more sky light by day than by night;
  - the world clock where no position is given or in empty space;
  - a band on its twin's time;
  - the moon on its slower day.
- ✅ **In a real client, in CI** (the same run): the client GameTest creates a World Engine world under a virtual display. Minecraft can't open OpenGL there, so it falls back to its Vulkan backend on Mesa's software driver. The test checks that the camera's sky puts the noon sun straight up at spawn and 45° high due south at 45° north. Its screenshots show the same: noon at spawn, sunrise due east, and noon and midnight at 45° north.
- ✅ **The plan reproduces that code:** replaying this plan's steps onto a clean `db66e46` gives the same files, byte for byte, as the tree CI built and tested.
- ⚠️ **Not checked:** time-driven gameplay in a real world (undead burning, villagers, daylight detectors), and how the sky looks while moving across a seam. See Task 7's manual checks.

**Post-execution note (2026-11-23):** executed with Haiku implementers and Sonnet reviewers. Every task's files matched the CI-verified reference byte for byte, and CI on the executed plan was green, client GameTest included ([run #66](https://github.com/Chabelabela/world-engine/actions/runs/45173700471)). The code reviews then found the problems below, fixed in four follow-up commits (`c15f850`, `6d7be64`, `f4aefc3`, `adee309`). So **the repository, not these code blocks, is authoritative**:
- **A band's sky is turned into the band's grid.** `LocalSkies` gave a band its twin's tilt, which is in the twin's grid axes. Where a seam turns, the sun and stars therefore stood 90° or 180° round about the vertical against the terrain, and jumped at the band's edge. The twin's tilt is now turned back by the band's quarter turns. A new GameTest, `theSkyCarriesOnAcrossATurnedSeam`, checks the sky is continuous where North meets its band three quarter turns round.
- **The client GameTest checks the drawn sun.** It used to check only the camera's tilt. Now it also finds the sun's white disc in three screenshots and checks where it is. At 45° north, a sky that wasn't tilted, or was tilted the wrong way, has no sun in view. The test also clears the weather first.
- **Local time never runs backwards.** Places west of the reference meridian used to wrap to the end of the first day before the world's first sunrise. Now they count one day ahead, so local time is monotonic, and longitudes −π and π give the same time.
- **A value asked for without a position always reads the world clock**, even if it is asked for in the middle of a positional sample on the same thread, so the level's per-tick cache can't keep a local value.
- **Smaller fixes:**
  - the longitude is in (−π, π] as documented: E3's centre was −π, and is now +π;
  - `LocalSkies` returns no place for a position that isn't finite;
  - the tilt is worked out at the start of `extractRenderState`;
  - CI retries `apt-get` and runs the client GameTest even if `build` fails (`--continue`);
  - new core tests: the pole stands at the latitude's height everywhere, it is 45° on North's edge with E1, and points just beyond the faces are included;
  - new local-time tests, including a moon day across a world-day boundary and the date line;
  - Javadoc touches.

**Environment note:** without JDK 25 (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 7). Don't download JDKs. The pure planet tests run on any JDK ≥ 21 with the JUnit console launcher.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `core/crates/we-frames/src/sky.rs` | Create | `LocalSky`: longitude, latitude and tilt of a surface point |
| `core/crates/we-frames/src/lib.rs` | Modify | Export `LocalSky` |
| `core/crates/we-ffi/src/sky.rs` | Create | `we_local_sky` |
| `core/crates/we-ffi/src/lib.rs` | Modify | ABI v5 |
| `core/crates/we-ffi/tests/abi.rs` | Modify | Call `we_local_sky` in the Java call sequence |
| `mod/src/main/java/…/nativebridge/LocalSky.java` | Create | The bridge's record |
| `mod/src/main/java/…/nativebridge/NativeCore.java` | Modify | `localSky`, ABI v5 |
| `mod/src/main/java/…/planet/PlanetSpec.java` | Modify (whole file) | `dayTicks` |
| `mod/src/main/java/…/planet/Planets.java` | Modify | Day lengths: home 20 minutes, moon 8 Minecraft days |
| `mod/src/main/java/…/planet/LocalTime.java` | Create | World clock + longitude → local day ticks; clock format |
| `mod/src/main/java/…/level/LocalSkies.java` | Create | Level position → planet and `LocalSky` (bands use their twin) |
| `mod/src/main/java/…/level/UniverseTime.java` | Create | Local clocks and the universe level's timeline layers |
| `mod/src/main/java/…/mixin/EnvironmentAttributeSystemMixin.java` | Create | Universe timelines on local time |
| `mod/src/main/java/…/mixin/EnvironmentAttributeValueSamplerMixin.java` | Create | Which position is being sampled |
| `mod/src/main/java/…/mixin/AttributeTrackSamplerMixin.java` | Create | Local samples skip the per-tick cache |
| `mod/src/main/java/…/client/UniverseSky.java` | Create | The camera's sky tilt |
| `mod/src/main/java/…/mixin/client/SkyRendererMixin.java` | Create | Draws the sky tilted |
| `mod/src/main/resources/worldengine.mixins.json` | Modify (whole file) | The new mixins |
| `mod/src/main/java/…/WorldEngineCommands.java` | Modify | Local time in `/worldengine where` |
| `mod/src/test/java/…/planet/LocalTimeTest.java` | Create | Local time |
| `mod/src/test/java/…/nativebridge/NativeLocalSkyTest.java` | Create | `we_local_sky` through the bridge |
| `mod/src/gametest/java/…/gametest/TimeGameTests.java` | Create | Local time on Minecraft's day timeline |
| `mod/src/gametest/resources/fabric.mod.json` | Modify | Register `TimeGameTests` and `SkyClientGameTest` |
| `mod/src/gametest/java/…/gametest/client/SkyClientGameTest.java` | Create | The sky in a real client |
| `mod/build.gradle` | Modify | Client GameTests on |
| `.github/workflows/ci.yml` | Modify | The `mod` job runs the client GameTest under a virtual display |

(`…` = `io/github/chabelabela/worldengine`.)

---

### Task 1: The sky over a point (`we-frames`)

The tilt is built from three frames at the point, all in the planet's body frame:
- **Grid axes:** up is the sphere's normal; the horizontal axes follow the grid's x and z lines. Away from a face's centre those lines meet at 60–120°, so the two axes split the difference.
- **Geographic axes:** north points along the surface toward the spin axis.
- **The lift:** Minecraft's sky has its celestial pole on the northern horizon (−z) and turns the sun about the z axis, as seen from the equator. Turning it about the east axis by the latitude raises the pole to the right height.

`tilt = grid⁻¹ · geographic · lift`. At E1's centre all three agree, so spawn sees Minecraft's own sky.

**Files:**
- Create: `core/crates/we-frames/src/sky.rs`
- Modify: `core/crates/we-frames/src/lib.rs`

- [ ] **Step 1: Write the module, tests included.**

```rust
//! The sky over a point on a spinning cube-sphere planet (M1f).

use crate::cube_sphere::{CubeSphere, SurfacePoint};
use crate::face::Face;
use glam::{DMat3, DVec3};
use std::f64::consts::FRAC_1_SQRT_2;

/// How a surface point sees the sky of its planet.
///
/// The planet spins about its body +Y axis (the North face's normal), which
/// turns the surface east, and its sun lies in the equatorial plane (axial
/// tilt and the planet's orbit come in M3). So the sun rises due east and sets
/// due west everywhere, and local time depends on longitude alone.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LocalSky {
    /// Radians east of the centre of face E1, in (−π, π]. Local time there is
    /// ahead of E1's by `longitude / 2π` of a day.
    pub longitude: f64,
    /// Radians north of the equator, in [−π/2, π/2].
    pub latitude: f64,
    /// Turns the sky as seen from the equator, by an observer whose x is
    /// east, y up and z south, into the sky over this point, in the point's
    /// grid axes (x along the grid's x, y up, z along the grid's z). The
    /// equatorial sky is Minecraft's own: the celestial pole on the northern
    /// horizon (−z), the sun turning about the z axis. A rotation.
    pub tilt: DMat3,
}

impl CubeSphere {
    /// The sky over grid position (`x`, `z`) of `face`. Any finite position
    /// works; beyond the face's edges the grid's own frame is extended.
    pub fn local_sky(self, face: Face, x: f64, z: f64) -> LocalSky {
        let [grid_x, up, grid_z] = self.grid_frame(face, x, z);
        // Geographic axes: north points along the surface toward the spin
        // axis. At a pole every way is south, so take the grid's north there.
        let toward_pole = DVec3::Y - up * up.y;
        let north = if toward_pole.length_squared() > 1e-24 {
            toward_pole.normalize()
        } else {
            -grid_z
        };
        let south = -north;
        let east = up.cross(south);
        // Equatorial sky → geographic: lift the pole from the northern horizon
        // to the latitude's height. Then geographic axes → grid axes.
        let latitude = libm::asin(up.y.clamp(-1.0, 1.0));
        let lift = DMat3::from_rotation_x(latitude);
        let geographic = DMat3::from_cols(east, up, south);
        let grid = DMat3::from_cols(grid_x, up, grid_z).transpose();
        LocalSky {
            longitude: libm::atan2(-up.x, -up.z),
            latitude,
            tilt: grid * geographic * lift,
        }
    }

    /// Orthonormal axes (body frame) along the grid's x, up, and along the
    /// grid's z at (`x`, `z`) of `face`. Grid lines meet at 60–120° away from a
    /// face's centre; splitting the difference keeps both horizontal axes as
    /// close to the grid as they can be.
    fn grid_frame(self, face: Face, x: f64, z: f64) -> [DVec3; 3] {
        let [along_x, up, along_z] = self.jacobian(SurfacePoint { face, x, y: 0.0, z });
        let (gx, gz) = (along_x.normalize(), along_z.normalize());
        let (b, c) = ((gx + gz).normalize(), (gx - gz).normalize());
        [(b + c) * FRAC_1_SQRT_2, up, (b - c) * FRAC_1_SQRT_2]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::{FRAC_PI_2, PI};

    const N: u32 = 16_384;

    fn sky(face: Face, x: f64, z: f64) -> LocalSky {
        CubeSphere::new(N).local_sky(face, x, z)
    }

    fn assert_close(a: DVec3, b: DVec3, label: &str) {
        assert!((a - b).length() < 1e-9, "{label}: {a} vs {b}");
    }

    /// Points spread over every face, corners and edges included.
    fn samples() -> Vec<(Face, f64, f64)> {
        let n = f64::from(N);
        let mut points = Vec::new();
        for face in Face::ALL {
            for x in [0.5, 1_000.0, n / 2.0, 12_345.6, n - 0.5] {
                for z in [0.5, 3_000.0, n / 2.0, n - 0.5] {
                    points.push((face, x, z));
                }
            }
        }
        points
    }

    #[test]
    fn spawn_sees_minecraft_s_own_sky() {
        let s = sky(Face::E1, 8_192.0, 8_192.0);
        assert!(s.longitude.abs() < 1e-12);
        assert!(s.latitude.abs() < 1e-12);
        assert!(
            (s.tilt - DMat3::IDENTITY).abs_diff_eq(DMat3::ZERO, 1e-12),
            "{:?}",
            s.tilt
        );
    }

    #[test]
    fn longitude_grows_east_around_the_equator() {
        let half = f64::from(N) / 2.0;
        assert!((sky(Face::E2, half, half).longitude - FRAC_PI_2).abs() < 1e-12);
        assert!((sky(Face::E3, half, half).longitude.abs() - PI).abs() < 1e-12);
        assert!((sky(Face::E0, half, half).longitude + FRAC_PI_2).abs() < 1e-12);
        // Every equatorial face centre has its grid aligned with east and south.
        for face in [Face::E0, Face::E2, Face::E3] {
            let s = sky(face, half, half);
            assert!(
                (s.tilt - DMat3::IDENTITY).abs_diff_eq(DMat3::ZERO, 1e-12),
                "{face:?}"
            );
        }
    }

    #[test]
    fn the_celestial_pole_is_over_the_north_pole() {
        let half = f64::from(N) / 2.0;
        let s = sky(Face::North, half, half);
        assert!((s.latitude - FRAC_PI_2).abs() < 1e-12);
        assert_close(s.tilt * DVec3::NEG_Z, DVec3::Y, "north pole");
        let s = sky(Face::South, half, half);
        assert!((s.latitude + FRAC_PI_2).abs() < 1e-12);
        assert_close(s.tilt * DVec3::Z, DVec3::Y, "south pole");
    }

    #[test]
    fn tilt_is_a_rotation_everywhere() {
        for (face, x, z) in samples() {
            let t = sky(face, x, z).tilt;
            let label = format!("{face:?} {x} {z}");
            assert!((t.determinant() - 1.0).abs() < 1e-9, "{label}");
            assert!(
                (t * t.transpose()).abs_diff_eq(DMat3::IDENTITY, 1e-9),
                "{label}"
            );
        }
    }

    #[test]
    fn grid_frame_follows_the_grid() {
        let sphere = CubeSphere::new(N);
        for (face, x, z) in samples() {
            let [gx, up, gz] = sphere.grid_frame(face, x, z);
            let [along_x, _, along_z] = sphere.jacobian(SurfacePoint { face, x, y: 0.0, z });
            let label = format!("{face:?} {x} {z}");
            assert!(
                (DMat3::from_cols(gx, up, gz).determinant() - 1.0).abs() < 1e-9,
                "{label}"
            );
            // Grid lines meet at 60–120°, so each axis is within 15° of its line.
            assert!(
                gx.dot(along_x.normalize()) > 15f64.to_radians().cos() - 1e-12,
                "{label}"
            );
            assert!(
                gz.dot(along_z.normalize()) > 15f64.to_radians().cos() - 1e-12,
                "{label}"
            );
        }
    }

    #[test]
    fn the_sky_turns_about_the_spin_axis_everywhere() {
        // In the body frame, the equatorial sky's pole (−z) must lie along the
        // planet's spin axis (+Y), and its noon sun (+y) over the equator at
        // this longitude.
        let sphere = CubeSphere::new(N);
        for (face, x, z) in samples() {
            let s = sphere.local_sky(face, x, z);
            let [gx, up, gz] = sphere.grid_frame(face, x, z);
            let body = |v: DVec3| gx * v.x + up * v.y + gz * v.z;
            let label = format!("{face:?} {x} {z}");
            assert_close(body(s.tilt * DVec3::NEG_Z), DVec3::Y, &label);
            let equator = DVec3::new(up.x, 0.0, up.z);
            if equator.length() > 1e-6 {
                assert_close(body(s.tilt * DVec3::Y), equator.normalize(), &label);
            }
        }
    }
}
```

- [ ] **Step 2: Export it.** In `core/crates/we-frames/src/lib.rs`, add `mod sky;` after `mod layout;`, and `pub use sky::LocalSky;` after `pub use layout::PlanetLayout;`.

- [ ] **Step 3: Run the tests.**

Run: `cd core && cargo test -p we-frames sky`
Expected: 6 passed.

- [ ] **Step 4: Commit.**

```bash
git add core/crates/we-frames
git commit -m "M1f: the sky over a point of a spinning cube-sphere planet"
```

---

### Task 2: `we_local_sky` and the bridge (ABI v5)

**Files:**
- Create: `core/crates/we-ffi/src/sky.rs`, `mod/src/main/java/…/nativebridge/LocalSky.java`, `mod/src/test/java/…/nativebridge/NativeLocalSkyTest.java`
- Modify: `core/crates/we-ffi/src/lib.rs`, `core/crates/we-ffi/tests/abi.rs`, `mod/src/main/java/…/nativebridge/NativeCore.java`

- [ ] **Step 1: The C function, tests included.**

```rust
//! Local sky: where a point on a planet sees the sun and stars (M1f).

use crate::{WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use we_frames::{CubeSphere, Face};

/// The sky over grid point (`x`, `z`) of `face` on a planet with `cells`
/// blocks per face edge (`we_frames::CubeSphere::local_sky`).
///
/// Writes 11 doubles to `out`: the longitude (radians east of face E1's
/// centre, in (−π, π]), the latitude (radians), and the 3×3 tilt from the
/// equatorial sky to this point's grid axes, column by column (x, y, z
/// columns of three each, as JOML's `Matrix3f` constructor takes them).
///
/// Returns `WE_OK`, `WE_ERR_NULL` for a null `out`, or `WE_ERR_INVALID` for a
/// bad `cells` or face index or a non-finite position. Stateless and
/// thread-safe.
///
/// # Safety
/// `out` must be null or valid for writing 11 `f64`s. It needn't be aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_local_sky(cells: u32, face: u32, x: f64, z: f64, out: *mut f64) -> i32 {
    if out.is_null() {
        return WE_ERR_NULL;
    }
    let (Some(sphere), Some(face)) = (
        CubeSphere::try_new(cells),
        u8::try_from(face).ok().and_then(Face::from_index),
    ) else {
        return WE_ERR_INVALID;
    };
    if !(x.is_finite() && z.is_finite()) {
        return WE_ERR_INVALID;
    }
    guard(|| {
        let sky = sphere.local_sky(face, x, z);
        let mut result = [0.0; 11];
        result[0] = sky.longitude;
        result[1] = sky.latitude;
        result[2..].copy_from_slice(&sky.tilt.to_cols_array());
        for (i, v) in result.into_iter().enumerate() {
            // SAFETY: the caller guarantees `out` is valid for 11 f64 writes
            // (`i < 11`); `write_unaligned` doesn't need it to be aligned.
            unsafe { out.add(i).write_unaligned(v) };
        }
        WE_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const N: u32 = 16_384;

    fn sky(face: u32, x: f64, z: f64) -> (i32, [f64; 11]) {
        let mut out = [f64::NAN; 11];
        let status = unsafe { we_local_sky(N, face, x, z, out.as_mut_ptr()) };
        (status, out)
    }

    #[test]
    fn matches_we_frames_everywhere() {
        let sphere = CubeSphere::new(N);
        for face in Face::ALL {
            for (x, z) in [
                (0.5, 0.5),
                (8_192.0, 8_192.0),
                (100.25, 16_000.75),
                (-10.5, 777.0),
            ] {
                let expected = sphere.local_sky(face, x, z);
                let (status, out) = sky(u32::from(face.index()), x, z);
                assert_eq!(status, WE_OK);
                assert_eq!(out[0], expected.longitude);
                assert_eq!(out[1], expected.latitude);
                assert_eq!(out[2..], expected.tilt.to_cols_array());
            }
        }
    }

    #[test]
    fn spawn_sees_the_vanilla_sky() {
        let (status, out) = sky(1, 8_192.0, 8_192.0);
        assert_eq!(status, WE_OK);
        let identity = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0];
        for (got, want) in out[2..].iter().zip(identity) {
            assert!((got - want).abs() < 1e-12, "{out:?}");
        }
    }

    #[test]
    fn rejects_bad_input() {
        let mut out = [0.0; 11];
        let p = out.as_mut_ptr();
        assert_eq!(
            unsafe { we_local_sky(16_383, 0, 1.0, 1.0, p) },
            WE_ERR_INVALID
        );
        assert_eq!(unsafe { we_local_sky(N, 6, 1.0, 1.0, p) }, WE_ERR_INVALID);
        assert_eq!(
            unsafe { we_local_sky(N, 0, f64::NAN, 1.0, p) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_local_sky(N, 0, 1.0, f64::INFINITY, p) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_local_sky(N, 0, 1.0, 1.0, std::ptr::null_mut()) },
            WE_ERR_NULL
        );
    }
}
```

- [ ] **Step 2: Wire it in, ABI v5.** In `core/crates/we-ffi/src/lib.rs`:
  - in the crate docs, change "`we_terrain_columns` and `we_seam_cross` take no handle and keep no state:" to "`we_terrain_columns`, `we_seam_cross` and `we_local_sky` take no handle and keep no state:";
  - add `mod sky;` after `mod seams;`, and `pub use sky::we_local_sky;` after the `pub use seams::…;` line;
  - change `pub const WE_ABI_VERSION: u32 = 4;` to `5`, and in the test `reports_abi_version`, `assert_eq!(we_abi_version(), 4);` to `5`.

  In `core/crates/we-ffi/tests/abi.rs`, after `assert_eq!(crossed[0], 0.0, "E3's east edge wraps onto E0");`, add:

```rust
    let mut sky = [0.0f64; 11];
    assert_eq!(
        unsafe { we_local_sky(16_384, 1, 8_192.0, 8_192.0, sky.as_mut_ptr()) },
        WE_OK
    );
    assert_eq!(sky[0], 0.0, "spawn is longitude 0");
```

- [ ] **Step 3: Run the Rust checks.**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets --locked -- -D warnings && cargo test --workspace --locked`
Expected: all pass.

- [ ] **Step 4: The bridge's record.**

```java
package io.github.chabelabela.worldengine.nativebridge;

/**
 * How a point on a planet sees the sky ({@code we_local_sky}): its {@code longitude} (radians east of face E1's
 * centre, in (-π, π]), its {@code latitude} (radians), and the {@code tilt} that turns Minecraft's sky, as seen from
 * the equator, into the sky over the point, in the point's grid axes: a 3x3 rotation, column by column (the x, y and
 * z columns, three numbers each), the order JOML's {@code Matrix3f} constructor takes.
 */
public record LocalSky(double longitude, double latitude, double[] tilt) {
    /** {@code tilt} times ({@code x}, {@code y}, {@code z}). */
    public double[] apply(double x, double y, double z) {
        return new double[] {
            tilt[0] * x + tilt[3] * y + tilt[6] * z,
            tilt[1] * x + tilt[4] * y + tilt[7] * z,
            tilt[2] * x + tilt[5] * y + tilt[8] * z,
        };
    }
}
```

- [ ] **Step 5: `NativeCore`.** In `mod/src/main/java/…/nativebridge/NativeCore.java`:
  - `EXPECTED_ABI_VERSION = 4` becomes `5`;
  - add `import java.util.Arrays;` after `import java.nio.file.Path;`;
  - add `private final MethodHandle localSkyFn;` after `private final MethodHandle seamCrossFn;`;
  - at the end of the constructor, after the `seamCrossFn = …;` statement, add:

```java
        localSkyFn = linker.downcallHandle(
                find(lib, "we_local_sky"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE, ADDRESS));
```

  - and before the Javadoc of `newDemoUniverse`, add:

```java
    /**
     * The sky over grid point ({@code x}, {@code z}) of {@code face} (0-5) on a planet with {@code cells} blocks per
     * face edge: its longitude and latitude, and the tilt that turns Minecraft's equatorial sky into the sky there.
     *
     * <p>Stateless and thread-safe, like {@link #terrainColumns}.
     */
    public LocalSky localSky(int cells, int face, double x, double z) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_DOUBLE, 11);
            check((int) localSkyFn.invokeExact(cells, face, x, z, out), "we_local_sky");
            double[] r = out.toArray(JAVA_DOUBLE);
            return new LocalSky(r[0], r[1], Arrays.copyOfRange(r, 2, 11));
        } catch (Throwable t) {
            throw wrap(t);
        }
    }
```

- [ ] **Step 6: The bridge test** (needs the native library: Gradle only).

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code we_local_sky} through the bridge. */
class NativeLocalSkyTest {
    private static final int N = 16_384;
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    @Test
    void spawnSeesMinecraftsOwnSky() {
        LocalSky sky = core.localSky(N, 1, N / 2.0, N / 2.0);
        assertEquals(0.0, sky.longitude(), 1e-12);
        assertEquals(0.0, sky.latitude(), 1e-12);
        assertArrayEquals(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, sky.tilt(), 1e-12);
    }

    @Test
    void eastIsAhead() {
        assertEquals(Math.PI / 2, core.localSky(N, 2, N / 2.0, N / 2.0).longitude(), 1e-12);
        assertEquals(-Math.PI / 2, core.localSky(N, 0, N / 2.0, N / 2.0).longitude(), 1e-12);
    }

    @Test
    void overTheNorthPoleTheCelestialPoleIsOverhead() {
        LocalSky sky = core.localSky(N, 4, N / 2.0, N / 2.0);
        assertEquals(Math.PI / 2, sky.latitude(), 1e-12);
        // Minecraft's pole is on the northern horizon (-z); here it is straight up.
        assertArrayEquals(new double[] {0, 1, 0}, sky.apply(0, 0, -1), 1e-12);
    }

    @Test
    void badInputThrows() {
        assertThrows(NativeCoreException.class, () -> core.localSky(N, 6, 1.0, 1.0));
        assertThrows(NativeCoreException.class, () -> core.localSky(N - 1, 0, 1.0, 1.0));
        assertThrows(NativeCoreException.class, () -> core.localSky(N, 0, Double.NaN, 1.0));
    }
}
```

Run: `cd mod && ./gradlew test --tests '*NativeLocalSkyTest'`
Expected: 4 passed.

- [ ] **Step 7: Commit.**

```bash
git add core mod/src/main/java/io/github/chabelabela/worldengine/nativebridge mod/src/test/java/io/github/chabelabela/worldengine/nativebridge
git commit -m "M1f: we_local_sky through the bridge (ABI v5)"
```

---

### Task 3: Local time

**Files:**
- Create: `mod/src/test/java/…/planet/LocalTimeTest.java`, `mod/src/main/java/…/planet/LocalTime.java`
- Modify (whole file): `mod/src/main/java/…/planet/PlanetSpec.java`
- Modify: `mod/src/main/java/…/planet/Planets.java`

- [ ] **Step 1: The test.**

```java
package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import org.junit.jupiter.api.Test;

class LocalTimeTest {
    private static final int DAY = LocalTime.MINECRAFT_DAY;

    @Test
    void theReferenceMeridianKeepsTheWorldClock() {
        assertEquals(6_000, LocalTime.localTicks(6_000, 0.0, DAY));
        assertEquals(1_234_567, LocalTime.localTicks(1_234_567, 0.0, DAY));
    }

    @Test
    void theSunIsAheadToTheEast() {
        // A quarter turn east sees sunset when the reference meridian sees noon; a quarter turn west, sunrise.
        assertEquals(12_000, LocalTime.localTicks(6_000, Math.PI / 2, DAY));
        assertEquals(0, LocalTime.localTicks(6_000, -Math.PI / 2, DAY));
        // Half a turn away it is midnight.
        assertEquals(18_000, LocalTime.localTicks(6_000, Math.PI, DAY));
        assertEquals(18_000, LocalTime.localTicks(6_000, -Math.PI, DAY));
    }

    @Test
    void itIsNeverNegative() {
        assertEquals(DAY - 6_000, LocalTime.localTicks(0, -Math.PI / 2, DAY));
    }

    @Test
    void aSlowerPlanetKeepsItsOwnDay() {
        // The moon's day is eight Minecraft days: eight world days make one moon day.
        int moonDay = Planets.MOON.dayTicks();
        assertEquals(8 * DAY, moonDay);
        assertEquals(DAY, LocalTime.localTicks(8 * DAY, 0.0, moonDay));
        assertEquals(750, LocalTime.localTicks(6_000, 0.0, moonDay));
        assertEquals(750 + 6_000, LocalTime.localTicks(6_000, Math.PI / 2, moonDay));
    }

    @Test
    void formatsAsAClock() {
        assertEquals("06:00", LocalTime.format(0));
        assertEquals("12:00", LocalTime.format(6_000));
        assertEquals("00:00", LocalTime.format(18_000));
        assertEquals("05:59", LocalTime.format(23_999));
        assertEquals("18:30", LocalTime.format(12_500 + 24_000 * 7));
    }

    @Test
    void aDayMustLastSomeTicks() {
        assertThrows(IllegalArgumentException.class, () -> new PlanetSpec("x", 1L, PlanetKind.BARREN,
                new PlanetLayout(0, 0, 4_096), 0));
    }
}
```

- [ ] **Step 2: Run it to see it fail.**

Run: `cd mod && ./gradlew test --tests '*LocalTimeTest'`
Expected: compilation fails (`LocalTime` and `dayTicks` don't exist).

- [ ] **Step 3: Day lengths.** Replace `PlanetSpec.java` with:

```java
package io.github.chabelabela.worldengine.planet;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import java.util.Objects;

/**
 * Everything the mod needs to know about one planet: its name (as in {@code we-orbit}), how its terrain seed
 * derives from the world seed, its kind, where its faces sit in the level, and how many ticks one of its days lasts.
 */
public record PlanetSpec(String name, long seedSalt, PlanetKind kind, PlanetLayout layout, int dayTicks) {
    public PlanetSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(layout, "layout");
        if (dayTicks <= 0) {
            throw new IllegalArgumentException("dayTicks must be positive: " + dayTicks);
        }
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

In `Planets.java`, replace the definitions of `HOME` and `MOON`, with their Javadoc, with:

```java
    /** ROADMAP §4.4: 16,384 blocks per face edge, radius 10,430 m. A day is Minecraft's: 20 minutes. */
    public static final PlanetSpec HOME = new PlanetSpec("home", 0x9E3779B97F4A7C15L, PlanetKind.TERRESTRIAL,
            new PlanetLayout(-24_576, -8_192, 16_384), LocalTime.MINECRAFT_DAY);
    /**
     * 4,096 blocks per face edge, radius 2,608 m. It turns once per orbit, as tidally locked moons do; a lunar month
     * is Minecraft's eight days, so a day on the moon lasts 160 minutes.
     */
    public static final PlanetSpec MOON = new PlanetSpec("moon", 0xD1B54A32D192ED03L, PlanetKind.BARREN,
            new PlanetLayout(100_000, -2_048, 4_096), 8 * LocalTime.MINECRAFT_DAY);
```

- [ ] **Step 4: Local time.**

```java
package io.github.chabelabela.worldengine.planet;

import java.util.Locale;

/**
 * Local time of day on a spinning planet (M1f). The world's day clock keeps the time at the home planet's
 * reference meridian, the centre of face E1, where the world spawns. Elsewhere the sun is ahead by the longitude's
 * share of a day, and on a planet whose day isn't Minecraft's the clock runs at that planet's pace.
 *
 * <p>Times are in Minecraft's day ticks: 0 is sunrise, 6,000 noon, 12,000 sunset and 18,000 midnight.
 */
public final class LocalTime {
    /** Ticks in a Minecraft day, and the period of its day timeline. */
    public static final int MINECRAFT_DAY = 24_000;

    private LocalTime() {
    }

    /**
     * The local time, in day ticks counted like the world clock, at {@code longitude} (radians east of the reference
     * meridian) on a planet whose day lasts {@code dayTicks}, when the world clock reads {@code clockTicks}. Never
     * negative: before the first local sunrise it counts from the end of the first day instead.
     */
    public static long localTicks(long clockTicks, double longitude, int dayTicks) {
        long scaled = Math.floorDiv(clockTicks, dayTicks) * MINECRAFT_DAY
                + Math.floorMod(clockTicks, dayTicks) * (long) MINECRAFT_DAY / dayTicks;
        long local = scaled + Math.round(longitude / (2 * Math.PI) * MINECRAFT_DAY);
        return local < 0 ? Math.floorMod(local, MINECRAFT_DAY) : local;
    }

    /** {@code ticks} as a 24-hour clock time, "HH:MM": day tick 0 is 06:00. */
    public static String format(long ticks) {
        long minutes = Math.floorMod(ticks + 6_000, MINECRAFT_DAY) * 1_440 / MINECRAFT_DAY;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
```

- [ ] **Step 5: Run the tests.**

Run: `cd mod && ./gradlew test --tests 'io.github.chabelabela.worldengine.planet.*'`
Expected: all pass (59 tests).

- [ ] **Step 6: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/planet mod/src/test/java/io/github/chabelabela/worldengine/planet
git commit -m "M1f: each planet's day, and local time from the world clock and longitude"
```

---

### Task 4: Local time in the universe level

How 26.3 samples a day-driven value, read from its bytecode:
- `EnvironmentAttributeSystem.addDynamicLayers(builder, level)` adds one `AttributeTrackSampler` per timeline track, a *time-based* layer that reads `clockManager.getInstance(clock).totalTicks()` and keeps one value per tick.
- A sampler is evaluated per position (`ValueSampler.computeValuePositional`) only if one of its layers is *positional*; otherwise every position gets the per-tick value.
- `getDimensionValue`, the value without a position, skips positional layers. `Level.updateSkyBrightness` uses it for the level's overall sky darkness.

So, in the universe level only:
- `EnvironmentAttributeSystemMixin` adds the timelines through `UniverseTime.addTimelineLayers`: on `LocalClocks`, plus a positional layer per timeline attribute that changes nothing;
- `EnvironmentAttributeValueSamplerMixin` records the position while a value is sampled there;
- `AttributeTrackSamplerMixin` skips the per-tick cache for those samples.

The GameTest level isn't a universe level, so the GameTests build the universe level's layers themselves with `UniverseTime.addTimelineLayers`, on a stopped clock.

**Files:**
- Create: `mod/src/main/java/…/level/LocalSkies.java`, `mod/src/main/java/…/level/UniverseTime.java`, the three mixins, `mod/src/gametest/java/…/gametest/TimeGameTests.java`
- Modify (whole file): `mod/src/main/resources/worldengine.mixins.json`
- Modify: `mod/src/gametest/resources/fabric.mod.json`

- [ ] **Step 1: The GameTests.**

```java
package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.level.UniverseTime;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;
import net.minecraft.world.timeline.Timelines;

/**
 * Local time of day (M1f), on Minecraft's own day timeline. The GameTest level isn't a universe level, so each test
 * builds the universe level's timeline layers itself ({@link UniverseTime#addTimelineLayers}, as the mixin does for
 * a universe level) on a stopped clock, and samples them at the home planet's net coordinates.
 */
public class TimeGameTests {
    private static final long NOON = 6_000;

    @GameTest
    public void theSunFollowsLongitude(GameTestHelper helper) {
        EnvironmentAttributeSystem day = dayAt(helper, NOON);
        // Noon at the reference meridian, E1's centre, where the world spawns.
        checkAngle(helper, sunAngle(day, centre(Planets.HOME, PlanetFace.E1)), 0, "noon on E1");
        // Half a turn round the planet it is midnight.
        checkAngle(helper, sunAngle(day, centre(Planets.HOME, PlanetFace.E3)), 180, "midnight on E3");
        // A quarter turn east the sun is setting, and a quarter turn west it is rising, symmetrically.
        float east = sunAngle(day, centre(Planets.HOME, PlanetFace.E2));
        float west = sunAngle(day, centre(Planets.HOME, PlanetFace.E0));
        check(helper, east > 0 && east < 180, "the sun is going down on E2: " + east);
        checkAngle(helper, east + west, 360, "sunset on E2 mirrors sunrise on E0");
        // Sky light follows: day on E1, night on E3.
        float dayLight = day.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, centre(Planets.HOME, PlanetFace.E1));
        float nightLight = day.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, centre(Planets.HOME, PlanetFace.E3));
        check(helper, dayLight > nightLight, "sky light " + dayLight + " by day, " + nightLight + " by night");
        helper.succeed();
    }

    @GameTest
    public void withoutAPlaceItIsTheWorldClock(GameTestHelper helper) {
        EnvironmentAttributeSystem day = dayAt(helper, NOON);
        checkAngle(helper, day.getDimensionValue(EnvironmentAttributes.SUN_ANGLE), 0, "the level's own value");
        checkAngle(helper, sunAngle(day, new Vec3(70_000, 100, 0)), 0, "empty space");
        helper.succeed();
    }

    @GameTest
    public void aBandHasItsTwinsTime(GameTestHelper helper) {
        EnvironmentAttributeSystem day = dayAt(helper, NOON + 1_234);
        PlanetLayout home = Planets.HOME.layout();
        int n = Planets.HOME.cells();
        double z = home.faceOriginZ(PlanetFace.E3) + 5_000.5;
        // E3's east band copies E0's west strip, unturned: 40 blocks into the band is 40 blocks into E0.
        float band = sunAngle(day, new Vec3(home.faceOriginX(PlanetFace.E3) + n + 40.5, 100, z));
        float twin = sunAngle(day, new Vec3(home.faceOriginX(PlanetFace.E0) + 40.5, 100, z));
        check(helper, band == twin, "band " + band + ", twin " + twin);
        helper.succeed();
    }

    @GameTest
    public void theMoonKeepsItsOwnSlowerDay(GameTestHelper helper) {
        // A moon day lasts eight world days: at world noon its spawn meridian has had an eighth of a morning.
        int eighth = (int) (NOON * Planets.HOME.dayTicks() / Planets.MOON.dayTicks());
        float moon = sunAngle(dayAt(helper, NOON), centre(Planets.MOON, PlanetFace.E1));
        float home = sunAngle(dayAt(helper, eighth), centre(Planets.HOME, PlanetFace.E1));
        checkAngle(helper, moon, home, "the moon at world noon is home at tick " + eighth);
        helper.succeed();
    }

    /** The universe level's day timeline alone, on a world clock stopped at {@code clockTicks}. */
    private static EnvironmentAttributeSystem dayAt(GameTestHelper helper, long clockTicks) {
        Holder<Timeline> day = helper.getLevel().registryAccess().lookupOrThrow(Registries.TIMELINE)
                .getOrThrow(Timelines.OVERWORLD_DAY);
        EnvironmentAttributeSystem.Builder builder = EnvironmentAttributeSystem.builder();
        UniverseTime.addTimelineLayers(builder, HolderSet.direct(day), clock -> new Stopped(clockTicks));
        return builder.build();
    }

    private record Stopped(long totalTicks) implements ClockInstance {
        @Override
        public float partialTick() {
            return 0.0F;
        }

        @Override
        public float rate() {
            return 1.0F;
        }

        @Override
        public boolean isPaused() {
            return true;
        }
    }

    private static Vec3 centre(PlanetSpec planet, PlanetFace face) {
        return new Vec3(planet.layout().faceOriginX(face) + planet.cells() / 2.0, 100,
                planet.layout().faceOriginZ(face) + planet.cells() / 2.0);
    }

    private static float sunAngle(EnvironmentAttributeSystem system, Vec3 position) {
        return system.getValue(EnvironmentAttributes.SUN_ANGLE, position);
    }

    private static void checkAngle(GameTestHelper helper, double degrees, double expected, String what) {
        check(helper, Math.abs(Math.IEEEremainder(degrees - expected, 360)) < 0.01,
                String.format(Locale.ROOT, "%s: sun angle %.3f, expected %.3f", what, degrees, expected));
    }

    private static void check(GameTestHelper helper, boolean condition, String what) {
        helper.assertTrue(condition, what);
    }
}
```

Register them: in `mod/src/gametest/resources/fabric.mod.json`, add `"io.github.chabelabela.worldengine.gametest.TimeGameTests"` after the `SeamMirrorGameTests` entry of `fabric-gametest` (with a comma after `SeamMirrorGameTests`).

- [ ] **Step 2: Places.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.nativebridge.LocalSky;
import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;

/**
 * The sky over a position in the universe level (M1f): which planet it is on, and how that point of the planet
 * sees the sky. In an overlap band it is the sky over the band's twin, the spot on the neighbouring face it copies.
 *
 * <p>Thread-safe. Each thread remembers its last block column, so asking about one place repeatedly costs one
 * native call.
 */
public final class LocalSkies {
    /** {@code planet}, and how one of its points sees the sky. */
    public record Place(PlanetSpec planet, LocalSky sky) {
    }

    private record Last(int x, int z, Place place) {
    }

    private static final ThreadLocal<Last> LAST = new ThreadLocal<>();

    private LocalSkies() {
    }

    /** The place at level position ({@code x}, {@code z}), or null in empty space or without the native core. */
    public static Place at(double x, double z) {
        NativeCore core = WorldEngineMod.core();
        if (core == null) {
            return null;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Last last = LAST.get();
        if (last != null && last.x() == bx && last.z() == bz) {
            return last.place();
        }
        Place place = lookUp(core, x, z, bx, bz);
        LAST.set(new Last(bx, bz, place));
        return place;
    }

    private static Place lookUp(NativeCore core, double x, double z, int bx, int bz) {
        PlanetHit hit = Planets.at(bx, bz);
        double px = x;
        double pz = z;
        if (hit == null) {
            SeamBand band = PlanetTerrain.bands().at(bx, bz);
            if (band == null) {
                return null;
            }
            double[] twin = band.toTwin(x, z);
            px = twin[0];
            pz = twin[1];
            hit = Planets.at((int) Math.floor(px), (int) Math.floor(pz));
            if (hit == null) {
                return null;
            }
        }
        PlanetSpec planet = hit.planet();
        PlanetFace face = hit.cell().face();
        LocalSky sky = core.localSky(planet.cells(), face.id(), px - planet.layout().faceOriginX(face),
                pz - planet.layout().faceOriginZ(face));
        return new Place(planet, sky);
    }
}
```

- [ ] **Step 3: Local clocks and the timeline layers.**

```java
package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.LocalTime;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;

/**
 * Local time of day in the universe level (M1f). Minecraft drives the day through timelines (sky colour, sky light,
 * sun and moon angles, monsters burning, villager schedules, ...) sampled from the world's day clock. In the
 * universe level each timeline instead reads the clock as it stands where it is sampled: {@link LocalTime} for the
 * planet and longitude there ({@link LocalSkies}).
 *
 * <p>How: the level's timeline layers get {@link LocalClocks}, and each timeline attribute also gets a positional
 * layer that changes nothing, so Minecraft samples it per position. While a value is sampled at a position (through
 * {@code EnvironmentAttributeValueSamplerMixin}) the clocks read that position's local time; values asked for
 * without a position, such as the level's overall sky darkness, keep the world clock: the reference meridian's time.
 */
public final class UniverseTime {
    /** The universe level's dimension type (data/worldengine/dimension_type/universe.json). */
    public static final ResourceKey<DimensionType> UNIVERSE =
            ResourceKey.create(Registries.DIMENSION_TYPE, WorldEngineMod.id("universe"));

    private static final ThreadLocal<Vec3> SAMPLED = new ThreadLocal<>();

    private UniverseTime() {
    }

    /** True if {@code level} is a World Engine universe level, on the server or the client. */
    public static boolean isUniverse(Level level) {
        return level.dimensionTypeRegistration().is(UNIVERSE);
    }

    /** Runs {@code body} as sampling environment attributes at {@code position}. */
    public static <T> T sampledAt(Vec3 position, Supplier<T> body) {
        Vec3 outer = SAMPLED.get();
        SAMPLED.set(position);
        try {
            return body.get();
        } finally {
            if (outer == null) {
                SAMPLED.remove();
            } else {
                SAMPLED.set(outer);
            }
        }
    }

    /** True while an attribute is being sampled at a position on this thread. */
    public static boolean sampling() {
        return SAMPLED.get() != null;
    }

    /** Adds {@code timelines} to {@code builder} as the universe level's: on local time, sampled per position. */
    public static void addTimelineLayers(EnvironmentAttributeSystem.Builder builder, HolderSet<Timeline> timelines,
            ClockManager worldClocks) {
        ClockManager local = new LocalClocks(worldClocks);
        for (Holder<Timeline> timeline : timelines) {
            builder.addTimelineLayer(timeline, local);
            for (EnvironmentAttribute<?> attribute : timeline.value().attributes()) {
                samplePerPosition(builder, attribute);
            }
        }
    }

    private static <V> void samplePerPosition(EnvironmentAttributeSystem.Builder builder,
            EnvironmentAttribute<V> attribute) {
        builder.addPositionalLayer(attribute, (value, position, interpolator) -> value);
    }

    /** Clocks that read the local time where an attribute is being sampled, or the world's time elsewhere. */
    public static final class LocalClocks implements ClockManager {
        private final ClockManager world;

        LocalClocks(ClockManager world) {
            this.world = world;
        }

        @Override
        public ClockInstance getInstance(Holder<WorldClock> clock) {
            ClockInstance instance = world.getInstance(clock);
            Vec3 position = SAMPLED.get();
            if (position == null) {
                return instance;
            }
            LocalSkies.Place place = LocalSkies.at(position.x, position.z);
            if (place == null) {
                return instance;
            }
            return new LocalClock(LocalTime.localTicks(instance.totalTicks(), place.sky().longitude(),
                    place.planet().dayTicks()), instance);
        }
    }

    private record LocalClock(long totalTicks, ClockInstance world) implements ClockInstance {
        @Override
        public float partialTick() {
            return world.partialTick();
        }

        @Override
        public float rate() {
            return world.rate();
        }

        @Override
        public boolean isPaused() {
            return world.isPaused();
        }
    }
}
```

- [ ] **Step 4: The mixins.**

```java
package io.github.chabelabela.worldengine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.chabelabela.worldengine.level.UniverseTime;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.Level;
import net.minecraft.world.timeline.Timeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The universe level's timelines run on local time ({@link UniverseTime}; M1f). */
@Mixin(EnvironmentAttributeSystem.class)
abstract class EnvironmentAttributeSystemMixin {
    @WrapOperation(method = "addDynamicLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/HolderSet;forEach(Ljava/util/function/Consumer;)V"))
    private static void worldengine$localTimelines(HolderSet<Timeline> timelines, Consumer<Holder<Timeline>> addEach,
            Operation<Void> original, @Local(argsOnly = true) EnvironmentAttributeSystem.Builder builder,
            @Local(argsOnly = true) Level level) {
        if (UniverseTime.isUniverse(level)) {
            UniverseTime.addTimelineLayers(builder, timelines, level.clockManager());
        } else {
            original.call(timelines, addEach);
        }
    }
}
```

```java
package io.github.chabelabela.worldengine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Tells the universe level's clocks where an attribute is being sampled ({@link UniverseTime}; M1f). */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
abstract class EnvironmentAttributeValueSamplerMixin {
    @WrapMethod(method = "computeValuePositional")
    private Object worldengine$sampledAt(Vec3 position, SpatialAttributeInterpolator interpolator,
            Operation<Object> original) {
        return UniverseTime.sampledAt(position, () -> original.call(position, interpolator));
    }
}
```

```java
package io.github.chabelabela.worldengine.mixin;

import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyframeTrackSampler;
import net.minecraft.world.attribute.modifier.AttributeModifier;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.timeline.AttributeTrackSampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A timeline track keeps one value per tick. On local time ({@link UniverseTime}; M1f) the value depends on where it
 * is sampled, so those samples skip the cache, and the cache keeps the world clock's value.
 */
@Mixin(AttributeTrackSampler.class)
abstract class AttributeTrackSamplerMixin {
    @Shadow @Final private Holder<WorldClock> clock;
    @Shadow @Final private AttributeModifier<Object, Object> modifier;
    @Shadow @Final private KeyframeTrackSampler<Object> argumentSampler;
    @Shadow @Final private ClockManager clockManager;

    @Inject(method = "applyTimeBased", at = @At("HEAD"), cancellable = true)
    private void worldengine$localTime(Object value, int tickId, CallbackInfoReturnable<Object> cir) {
        if (clockManager instanceof UniverseTime.LocalClocks && UniverseTime.sampling()) {
            cir.setReturnValue(modifier.apply(value, argumentSampler.sample(clockManager.getInstance(clock).totalTicks())));
        }
    }
}
```

Replace `mod/src/main/resources/worldengine.mixins.json` with:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.chabelabela.worldengine.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "AttributeTrackSamplerMixin",
    "EnvironmentAttributeSystemMixin",
    "EnvironmentAttributeValueSamplerMixin",
    "ServerLevelMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

- [ ] **Step 5: Run the tests.**

Run: `cd mod && ./gradlew build`
Expected: BUILD SUCCESSFUL, with "All 24 required tests passed" in the GameTest output.

- [ ] **Step 6: Commit.**

```bash
git add mod/src
git commit -m "M1f: the universe level's timelines run on local time"
```

---

### Task 5: The sky over the camera

`SkyRenderer.renderSunMoonAndStars` pushes a pose, turns it −90° about Y, and then turns it by each body's angle about X. `renderSunriseAndSunset` pushes a pose for the sunrise glow. Right after each first `pushPose`, `SkyRendererMixin` multiplies in the tilt for the camera's position, which it works out in `extractRenderState`, where the camera is at hand. The angles themselves come from the camera's attribute probe, which samples at the camera's position, so they are already on local time after Task 4.

**Files:**
- Create: `mod/src/main/java/…/client/UniverseSky.java`, `mod/src/main/java/…/mixin/client/SkyRendererMixin.java`, `mod/src/gametest/java/…/gametest/client/SkyClientGameTest.java`
- Modify (whole file): `mod/src/main/resources/worldengine.mixins.json`
- Modify: `mod/src/gametest/resources/fabric.mod.json`, `mod/build.gradle`, `.github/workflows/ci.yml`

- [ ] **Step 1: The tilt for a camera.**

```java
package io.github.chabelabela.worldengine.client;

import io.github.chabelabela.worldengine.level.LocalSkies;
import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The sky over the camera in the universe level (M1f). Minecraft draws the sun, moon and stars as an observer on the
 * equator sees them; {@link #tiltAt} turns that sky to the camera's latitude and grid, so the sky turns about the
 * planet's axis wherever the camera is.
 */
public final class UniverseSky {
    private UniverseSky() {
    }

    /** The tilt for a camera at {@code position} in {@code level}, or null where the sky is Minecraft's own. */
    public static Matrix4f tiltAt(Level level, Vec3 position) {
        if (!UniverseTime.isUniverse(level)) {
            return null;
        }
        LocalSkies.Place place = LocalSkies.at(position.x, position.z);
        if (place == null) {
            return null;
        }
        double[] t = place.sky().tilt();
        return new Matrix4f(
                (float) t[0], (float) t[1], (float) t[2], 0.0F,
                (float) t[3], (float) t[4], (float) t[5], 0.0F,
                (float) t[6], (float) t[7], (float) t[8], 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
    }
}
```

- [ ] **Step 2: The mixin.**

```java
package io.github.chabelabela.worldengine.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.chabelabela.worldengine.client.UniverseSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Matrix4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns the sun, moon, stars and sunrise glow to the camera's place on its planet ({@link UniverseSky}; M1f). */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
    @Unique
    private Matrix4f worldengine$tilt;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void worldengine$tiltForCamera(ClientLevel level, float partialTick, Camera camera, SkyRenderState state,
            CallbackInfo ci) {
        worldengine$tilt = UniverseSky.tiltAt(level, camera.position());
    }

    @Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void worldengine$tiltCelestialBodies(RenderPass pass, PoseStack poseStack, float sunAngle, float moonAngle,
            float starAngle, MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
        if (worldengine$tilt != null) {
            poseStack.mulPose(worldengine$tilt);
        }
    }

    @Inject(method = "renderSunriseAndSunset", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void worldengine$tiltSunriseGlow(RenderPass pass, PoseStack poseStack, float sunAngle, Vector4fc color,
            CallbackInfo ci) {
        if (worldengine$tilt != null) {
            poseStack.mulPose(worldengine$tilt);
        }
    }
}
```

Replace `mod/src/main/resources/worldengine.mixins.json` with:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.chabelabela.worldengine.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "AttributeTrackSamplerMixin",
    "EnvironmentAttributeSystemMixin",
    "EnvironmentAttributeValueSamplerMixin",
    "ServerLevelMixin"
  ],
  "client": [
    "client.SkyRendererMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

- [ ] **Step 3: A client GameTest.** A dedicated server never loads client mixins, so this test runs a real client. It creates a World Engine world and checks where the camera's sky puts the noon sun: straight up at spawn, 45° high due south at 45° north. It also takes screenshots for a person to look at.

```java
package io.github.chabelabela.worldengine.gametest.client;

import io.github.chabelabela.worldengine.client.UniverseSky;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The universe level's sky in a real client (M1f): a World Engine world is created, and at two places the test
 * checks where the tilted sky puts the noon sun and takes screenshots for a person to look at (CI uploads them).
 * Runs with {@code ./gradlew runClientGameTest}; it needs a display (CI uses a virtual one).
 */
public class SkyClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(creator -> creator.setWorldType(creator.getNormalPresetList().stream()
                        .filter(type -> type.preset() != null
                                && type.preset().is(Identifier.fromNamespaceAndPath("worldengine", "universe")))
                        .findFirst().orElseThrow()))
                .create()) {
            singleplayer.getServer().runCommand("gamemode spectator @a");
            // Spawn: Minecraft's own sky, the noon sun straight up.
            goTo(context, singleplayer, "time set 6000", "tp @a 0 400 0 0 -60");
            expectNoonSun(context, 0.0F, 1.0F, 0.0F, "at spawn");
            context.takeScreenshot("worldengine-spawn-noon-south-up");
            goTo(context, singleplayer, "time set 300", "tp @a 0 400 0 -90 -10");
            context.takeScreenshot("worldengine-spawn-sunrise-east");
            // Just over North's edge with E1, 45 degrees north: the noon sun stands 45 degrees high, due south.
            goTo(context, singleplayer, "time set 6000", "tp @a 0 400 -8300 0 -30");
            expectNoonSun(context, 0.0F, 0.71F, 0.71F, "45 degrees north");
            context.takeScreenshot("worldengine-north45-noon-south");
            goTo(context, singleplayer, "time set 18000", "tp @a 0 400 -8300 180 -40");
            context.takeScreenshot("worldengine-north45-midnight-north");
        }
    }

    private static void goTo(ClientGameTestContext context, TestSingleplayerContext singleplayer, String time,
            String teleport) {
        singleplayer.getServer().runCommand(time);
        singleplayer.getServer().runCommand(teleport);
        context.waitTicks(40);
    }

    /** Where the camera's sky puts the sun at local noon (Minecraft's zenith, tilted), within 0.02. */
    private static void expectNoonSun(ClientGameTestContext context, float x, float y, float z, String where) {
        Vector3f noon = context.computeOnClient(minecraft -> {
            Matrix4f tilt = UniverseSky.tiltAt(minecraft.level, minecraft.gameRenderer.mainCamera().position());
            return tilt == null ? null : tilt.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        });
        if (noon == null || Math.abs(noon.x - x) > 0.02F || Math.abs(noon.y - y) > 0.02F
                || Math.abs(noon.z - z) > 0.02F) {
            throw new AssertionError("noon sun " + where + ": " + noon + ", expected (" + x + ", " + y + ", " + z + ")");
        }
    }
}
```

Register it: in `mod/src/gametest/resources/fabric.mod.json`, after the `fabric-gametest` list's closing `]`, add a comma and:

```json
    "fabric-client-gametest": [
      "io.github.chabelabela.worldengine.gametest.client.SkyClientGameTest"
    ]
```

In `mod/build.gradle`, turn client GameTests on (`enableClientGameTests = false` becomes `true`), and build the core first for them too: `if (name in ['runClient', 'runServer', 'runGameTest']) {` becomes `if (name in ['runClient', 'runServer', 'runGameTest', 'runClientGameTest']) {`.

- [ ] **Step 4: CI runs it.** A GitHub runner has no display or GPU. Under `xvfb-run`, Minecraft can't open an OpenGL context, so it falls back to its Vulkan backend on Mesa's software driver (`mesa-vulkan-drivers`). In `.github/workflows/ci.yml`, job `mod`, replace the step "Build and test the mod (bundles all platforms)" with:

```yaml
      - name: Virtual display and software rendering, for the client GameTest
        run: sudo apt-get update -q && sudo apt-get install -y -q xvfb libgl1-mesa-dri mesa-vulkan-drivers
      - name: Build and test the mod, client GameTest included (bundles all platforms)
        working-directory: mod
        run: xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew build runClientGameTest -PnativesDir=../natives-all
      - name: Client GameTest screenshots
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: client-screenshots
          path: mod/build/run/clientGameTest/screenshots/
          if-no-files-found: warn
```

- [ ] **Step 5: Run it.** Run: `cd mod && ./gradlew runClientGameTest` (opens a game window). Expected: the test passes; the screenshots land in `mod/build/run/clientGameTest/screenshots/`.

- [ ] **Step 6: Commit.**

```bash
git add mod .github/workflows/ci.yml
git commit -m "M1f: the sun, moon and stars turn about the planet's axis over the camera"
```

---

### Task 6: Local time in `/worldengine where`

**Files:**
- Modify: `mod/src/main/java/…/WorldEngineCommands.java`

- [ ] **Step 1:** Add `import io.github.chabelabela.worldengine.level.LocalSkies;` before the `PlanetTerrain` import, and `import io.github.chabelabela.worldengine.planet.LocalTime;` after the `FaceCell` import. In `where`:
  - in the band's reply, add `%s` at the end of the format string and `localTime(source, position)` as the last argument, so the call reads:

```java
                reply(source, String.format(Locale.ROOT,
                        "(%d, %d): %s, the band beyond %s's %s edge, %.0f blocks deep, copying %s at (%d, %d)%s",
                        x, z, band.planet().name(), band.face(), band.side().name().toLowerCase(Locale.ROOT),
                        Math.floor(band.depth(x + 0.5, z + 0.5)), band.twinFace(), twin[0], twin[1],
                        localTime(source, position)));
```

  - and replace the face's reply, the `return`, and the method's closing brace with:

```java
        reply(source, String.format(Locale.ROOT, "(%d, %d): %s, face %s, cell (%d, %d), %d m, %s%s%s",
                x, z, hit.planet().name(), cell.face(), cell.x(), cell.z(), column.height(0, 0), column.biome(0, 0),
                localTime(source, position), note));
        return Command.SINGLE_SUCCESS;
    }

    /** ", local time 14:30 at 12.3° N 45.6° E" for a position on a planet or in a band; "" elsewhere (M1f). */
    private static String localTime(CommandSourceStack source, Vec3 position) {
        LocalSkies.Place place = LocalSkies.at(position.x(), position.z());
        if (place == null) {
            return "";
        }
        long ticks = LocalTime.localTicks(source.getLevel().getDefaultClockTime(), place.sky().longitude(),
                place.planet().dayTicks());
        double latitude = Math.toDegrees(place.sky().latitude());
        double longitude = Math.toDegrees(place.sky().longitude());
        return String.format(Locale.ROOT, ", local time %s at %.1f° %s %.1f° %s", LocalTime.format(ticks),
                Math.abs(latitude), latitude >= 0 ? "N" : "S", Math.abs(longitude), longitude >= 0 ? "E" : "W");
    }
```

- [ ] **Step 2: Compile.** Run: `cd mod && ./gradlew compileJava`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/WorldEngineCommands.java
git commit -m "M1f: /worldengine where reports the local time"
```

---

### Task 7: Push, CI, docs

- [ ] **Step 1: Push and watch CI.** Every job must pass. The `mod` job runs the GameTests (24) and the client GameTest, and uploads its screenshots (artifact `client-screenshots`); `mod-tests` runs `NativeLocalSkyTest` on Windows and macOS.

- [ ] **Step 2: Docs.**
  - **`docs/ROADMAP.md` §5 M1:** in the time-of-day bullet, add "**done in M1f** ([plan](superpowers/plans/2026-11-23-m1f-time-of-day.md))". Add an M1f line to the M1 plans list, and update "Next steps": M1's code is complete, and its manual checks remain.
  - **`docs/superpowers/plans/2026-11-20-m1-overview.md`:** mark M1f done.
  - **`README.md`:** update the status line, and add this plan to the table.

- [ ] **Step 3: Manual checks** (owner, in a new World Engine world; creative is easiest):
  1. **Spawn:** at noon (`/time set noon`) the sun is overhead, as in vanilla. `/worldengine where` says local time 12:00 at 0.0° N 0.0° E.
  2. **East and west:** fly to E2 (x ≈ 16,000, z = 0). It is evening there: the sun is low in the west, and `/worldengine where` says about 18:00. On E3 (x ≈ 32,000) it is night, and on E0 (x ≈ −16,000) morning.
  3. **High latitude:** on North near its edge with E1 (x = 0, z ≈ −8,300), the noon sun stands about 45° high, due south. At midnight the stars turn about a point 45° up in the north. Near North's centre (z ≈ −16,400) the sun circles the horizon.
  4. **Light follows the sun where it is sampled:** at E1's noon (`/time set noon`), the sky over E3 is night-dark, and undead standing in the open on E3 don't burn. Monster spawning and beds still go by the night at spawn's meridian (Known limits).
  5. **The moon:** `/worldengine goto moon`. The sun moves eight times slower there.
  6. **Across a seam:** cross from E3 to E0 at night; the sky doesn't jump.

- [ ] **Step 4: Commit and push.**

```bash
git add README.md docs
git commit -m "M1f: docs"
git push
```

---

## Known limits (by design, for later)

- **The level's overall sky darkness keeps the reference meridian's time.** Minecraft keeps one sky darkness per level, from the day timeline without a position. Monster spawning's light check, `isBrightOutside` (used by some mob AI: foxes, endermen, drowned, wandering traders), phantoms, beds' "only when dark" rule and sleeping use it. So monsters spawn, and beds work, by the night at spawn's meridian. Making those local means patching each of them; that's for M3, with the real sun.
- **Sleeping skips to morning at the reference meridian.**
- **The sky turns back to Minecraft's own in empty space** between the planets, so it jumps when the camera leaves a planet's net (M4 draws the real sky there).
- **Places west of the reference meridian count one day ahead** (a date line opposite spawn): the time of day is right everywhere, but day-counted timelines (moon phases, the early-game patrol delay) are a day ahead there.
- **No axial tilt, no seasons, no orbit:** the sun sits in the equatorial plane, fixed among the stars. Days are the same length everywhere and all year (M3).
- **The moon in the sky is vanilla's**, opposite the sun, not the real moon (M4 draws the real bodies).
- **Fog's sunrise tint** assumes the sun rises due east in grid terms. On faces turned against the geographic grid it may tint the wrong side.
- **Clouds** keep drifting along grid x everywhere.
- **The sky turns a little as you step into a band near a cube corner.** A band's sky is its twin's, so that nothing changes at the crossing in the band's middle. But grid lines kink at seams, by nothing at an edge's midpoint and by up to 60° at a corner. So at a band's edge the sky turns by the kink: about 15° 4,000 blocks from a corner, and 25° 300 blocks from it. Blending the face's sky into the twin's across the band would hide it.
- **The ship frame isn't handled yet.** ADR 0001 wants the sky of a ship's surface-equivalent position. The shipyard plots are in empty space, so for now a ship's crew sees Minecraft's own sky on the world clock. M2 plugs the ship's position into `LocalSkies`.
- **Mixins on private parts of 26.3:** `AttributeTrackSamplerMixin` shadows four private fields, and the attribute mixins target a private inner class and private methods. They fail loudly at launch if Minecraft changes them (`defaultRequire: 1`), so re-check them on every Minecraft update.
- **Every timeline runs on local time**, including the moon-phase and early-game ones, so moon phases differ slightly by longitude.

## Self-review against the M1 goals and ADR 0001

| Requirement | Where |
|---|---|
| Sunrise and sunset follow planet rotation (M1 exit) | `LocalTime`, `UniverseTime` (Task 4, GameTests) and `SkyRendererMixin` (Task 5, client GameTest) |
| Per-planet time of day: sun direction from planet spin (ROADMAP §5 M1) | `we-frames` `LocalSky` (Task 1), `PlanetSpec.dayTicks` (Task 3) |
| Sky angle and lighting scoped to the universe level (ROADMAP) | All three attribute mixins act only on the universe level's layers; other dimensions keep vanilla's |
| Visual attributes come from the frame system, not the camera's raw level position (ADR, M1f) | On a planet, local time and tilt come from the planet and point; a ship's surface-equivalent position plugs into `LocalSkies` in M2 |
