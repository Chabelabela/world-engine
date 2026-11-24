# M2a Physics Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use anthropic-skills:subagent-driven-development (recommended) or anthropic-skills:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the physics core that every later M2 phase drives ([M2 overview](2026-11-24-m2-overview.md), ROADMAP §5 M2):
- **Rigid bodies made of blocks.** A ship is built from its blocks: their positions, masses and collision shapes. Its mass, centre of mass and inertia are summed from them.
- **Terrain to collide with,** fed in a 16 × 16 × 16 section at a time.
- **Forces at points and torques,** in the ship's own frame, so a thruster keeps pushing along its nozzle as the ship turns, and an off-centre one makes it tumble.
- **Gravity per ship,** set by whoever knows which planet it is over.
- **A fixed 60 Hz step that the server never waits on:** three steps per server tick on a thread of its own. The server reads the states published after the previous tick.

Nothing in the game uses it yet: M2b adds ships to the world.

**Architecture:**
- **Rust, `we-physics` (new crate):** `World` wraps a Rapier 0.36 (`rapier3d-f64`) world.
  - **Terrain** is one `Voxels` collider for every full-cube block, edited bit by bit as sections change, plus one compound of boxes per section for slabs, stairs and the like. One collider, not one per section, because a ship sliding over separate section colliders catches on their edges (measured in the M2 overview's probe: a 2 m/s kick and 33° of tilt).
  - **A ship** is a dynamic body with one `Voxels` collider for its full blocks and one compound of boxes for the rest. The colliders are massless; the body carries the mass properties summed from the blocks.
  - **Gravity** is (0, −1, 0) world-wide, and each ship's gravity scale is its g in m/s².
- **Rust, `we-ffi`:** an opaque `WePhysics` handle and 13 batched functions over flat arrays (ABI v6). No call is per block.
- **Java, `nativebridge`:** `NativeCore.PhysicsWorld` (an `AutoCloseable` handle like `Universe`), and the records and builders it takes: `ShipBlocks`, `TerrainSection`, `ShipMotion`, `ShipState`, `ShipForce`, `ShipMass`.
- **Java, `physics` (new package):** `PhysicsThread` owns a `PhysicsWorld` on a daemon thread. The server thread queues commands and ticks, and reads the latest `PhysicsSnapshot`, never blocking. A failing command stops the world instead of crashing the server. `PhysicsThread.inline` runs everything on the caller, for deterministic tests.

**Tech Stack:** Rust (Rapier `rapier3d-f64` 0.36.1, pinned) · Java 25 FFM · JUnit 5

**What has been checked before execution (2026-11-24):** every file in this plan was built and run from a scratch worktree of `82ea7e2`.
- ✅ **Rust:** `cargo fmt`, `clippy -D warnings` and the whole workspace's tests. New: 7 mass-property tests, 15 behaviour tests and 5 FFI tests, plus the physics call sequence in `tests/abi.rs`. The behaviour tests keep the overview's probe measurements true:
  - a ship rests within 1 mm of the terrain and falls asleep;
  - pushed across three section edges, it never kicks up or tilts;
  - an off-centre force gives exactly the predicted spin;
  - forces and torques turn with the ship;
  - slabs collide as half blocks, on ships and in terrain;
  - ships stack;
  - removing the ground wakes a ship and drops it;
  - a ship at 300 m/s doesn't tunnel through one block of floor;
  - 20 idle ships all fall asleep;
  - gravity is per ship, and bad arguments are refused.
- ✅ **Java:** the 10 new JUnit tests (`NativePhysicsTest`, `PhysicsThreadTest`) passed on JDK 21 against the debug library, with FFM's JDK 21 names swapped in.
- ✅ **In CI, on every platform** ([mc-api run #19](https://github.com/Chabelabela/world-engine/actions/runs/45454604703); a temporary workflow that ran the CI workflow on the draft, removed afterwards):
  - `cargo test` on Linux (x86-64 and ARM), Windows and macOS (ARM), and release builds of the library for all 5 targets;
  - `./gradlew build` with JDK 25 on Linux, which includes all JUnit tests and the 25 GameTests, and the client GameTest;
  - `./gradlew test` on Windows and macOS.
- ✅ **The plan reproduces that code:** replaying this plan's steps onto a clean `82ea7e2` gives the same files, byte for byte, as the tree CI built and tested.
- ⚠️ **Not checked:** anything in a running game. Ships enter the world in M2b.

**Environment note:** without JDK 25 (as in Claude cloud sessions), skip the `./gradlew` steps and rely on CI (Task 6). Don't download JDKs.

---

## File map

| Path | Change | Responsibility |
|---|---|---|
| `core/Cargo.toml` | Modify | The new crate, Rapier pinned, MSRV 1.86 (Rapier's) |
| `core/Cargo.lock` | Modify | Rapier and its dependencies |
| `core/crates/we-physics/Cargo.toml` | Create | The crate |
| `core/crates/we-physics/src/lib.rs` | Create | Crate docs and exports; section constants |
| `core/crates/we-physics/src/blocks.rs` | Create | Blocks, their boxes, and mass properties |
| `core/crates/we-physics/src/world.rs` | Create | `World`: terrain sections, ships, controls, steps |
| `core/crates/we-physics/tests/behaviour.rs` | Create | How ships rest, slide, spin, stack and fall |
| `core/crates/we-ffi/Cargo.toml` | Modify | Depend on `we-physics` |
| `core/crates/we-ffi/src/physics.rs` | Create | The `we_physics_*` C functions |
| `core/crates/we-ffi/src/lib.rs` | Modify | Export them; ABI v6 |
| `core/crates/we-ffi/tests/abi.rs` | Modify | The physics call sequence |
| `mod/src/main/java/…/nativebridge/ShipMotion.java` | Create | Pose and velocities |
| `mod/src/main/java/…/nativebridge/ShipState.java` | Create | A ship after a step |
| `mod/src/main/java/…/nativebridge/ShipForce.java` | Create | A force at a point, in the ship's frame |
| `mod/src/main/java/…/nativebridge/ShipMass.java` | Create | Mass, centre of mass, inertia |
| `mod/src/main/java/…/nativebridge/ShipBlocks.java` | Create | Builder for a ship's blocks |
| `mod/src/main/java/…/nativebridge/TerrainSection.java` | Create | Builder for a terrain section |
| `mod/src/main/java/…/nativebridge/NativeCore.java` | Modify | `PhysicsWorld`; ABI v6 |
| `mod/src/main/java/…/physics/PhysicsSnapshot.java` | Create | Ship states after a tick |
| `mod/src/main/java/…/physics/PhysicsThread.java` | Create | The world on its own thread |
| `mod/src/test/java/…/nativebridge/NativePhysicsTest.java` | Create | The bridge |
| `mod/src/test/java/…/physics/PhysicsThreadTest.java` | Create | The thread |

(`…` = `io/github/chabelabela/worldengine`.)

---

### Task 1: The `we-physics` crate: blocks and mass properties

A ship's blocks sit on its own integer grid: block (x, y, z) fills [x, x + 1) × [y, y + 1) × [z, z + 1) of the ship's frame. A block is a full cube, a list of boxes in its own frame (a slab's one, a stair's two), or nothing to collide with (a torch). Its mass is spread evenly over its boxes, or over the whole cube if it is full or doesn't collide. A ship's mass properties are the sum over its blocks: parry's `MassProperties` adds them up, parallel-axis terms and products of inertia included.

**Files:**
- Modify: `core/Cargo.toml`, `core/Cargo.lock`
- Create: `core/crates/we-physics/Cargo.toml`, `core/crates/we-physics/src/lib.rs`, `core/crates/we-physics/src/blocks.rs`

- [ ] **Step 1: Add the crate to the workspace.** In `core/Cargo.toml`:
  - add `"crates/we-physics", ` before `"crates/we-ffi"` in `members`;
  - change `rust-version = "1.85"` to `rust-version = "1.86"` (Rapier 0.36 needs it);
  - under `[workspace.dependencies]`, after `proptest = "1.12"`, add:

```toml
# Pinned exactly: its voxel shape is new, and the physics tests pin its behaviour.
rapier3d-f64 = "=0.36.1"
```

  - and after `we-planet = { path = "crates/we-planet" }`, add `we-physics = { path = "crates/we-physics" }`.

- [ ] **Step 2: The crate's manifest.** Create `core/crates/we-physics/Cargo.toml`:

```toml
[package]
name = "we-physics"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
description = "Rigid bodies made of blocks for World Engine: ships and the terrain they touch"

[dependencies]
rapier3d-f64.workspace = true
```

- [ ] **Step 3: The crate root.** Create `core/crates/we-physics/src/lib.rs` with this content for now (Task 2 adds the world):

```rust
//! Rigid bodies made of blocks: ships, and the terrain they touch (M2a).
//!
//! A [`World`] holds terrain, fed to it one 16³ section at a time, and ships,
//! each a rigid body built from its blocks. It steps at a fixed
//! [`STEP_SECONDS`] with Rapier (`rapier3d-f64`).
//!
//! - **Terrain** is one voxel shape for all full-cube blocks, so a ship slides
//!   over section edges without catching on them. Blocks with smaller
//!   collision shapes (slabs, stairs, fences) are boxes, one compound shape
//!   per section.
//! - **A ship** is one voxel shape for its full-cube blocks, plus one compound
//!   of boxes for its other blocks. Its mass, centre of mass and inertia are
//!   summed from per-block masses, each spread evenly over the block's boxes.
//! - **Coordinates:** a ship's blocks sit on its own integer grid. Block
//!   (x, y, z) fills [x, x + 1) × [y, y + 1) × [z, z + 1) of the ship's local
//!   frame, and a ship's pose places that frame in the world. World
//!   coordinates are Minecraft's: metres, Y up.
//! - **Not thread-safe:** a `World` is used by one thread at a time. Results
//!   are reproducible on one platform but not bit-identical across platforms.

mod blocks;

pub use blocks::{
    BlockShape, Cuboid, MAX_BLOCK_BOXES, MAX_SHIP_BLOCKS, ShipBlock, ShipMass, mass_of,
};

/// Blocks along each edge of a terrain section.
pub const SECTION_SIZE: i32 = 16;
/// Blocks in a terrain section.
pub const SECTION_BLOCKS: usize = 4096;
/// 64-bit words in a section's full-block mask.
pub const SECTION_WORDS: usize = SECTION_BLOCKS / 64;
```

- [ ] **Step 4: Blocks and mass properties, tests included.** Create `core/crates/we-physics/src/blocks.rs`:

```rust
//! Blocks, their collision boxes, and the mass properties they add up to.

use rapier3d_f64::prelude::{MassProperties, Pose, Vector};

/// Most blocks one ship may have (ROADMAP risk 4: a v1 cap).
pub const MAX_SHIP_BLOCKS: usize = 32_768;
/// Most collision boxes one block may have.
pub const MAX_BLOCK_BOXES: usize = 16;

/// An axis-aligned box from `min` to `max`, in some local frame: a block's
/// (0..1 on each axis, a little beyond for fences and walls) or a terrain
/// section's (0..16).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Cuboid {
    pub min: [f64; 3],
    pub max: [f64; 3],
}

impl Cuboid {
    pub fn new(min: [f64; 3], max: [f64; 3]) -> Self {
        Self { min, max }
    }

    /// True if every coordinate is finite, `min < max` on every axis, and
    /// the box lies within `lo..=hi` on every axis.
    pub(crate) fn is_valid_within(&self, lo: f64, hi: f64) -> bool {
        (0..3).all(|i| {
            let (a, b) = (self.min[i], self.max[i]);
            a.is_finite() && b.is_finite() && a < b && a >= lo && b <= hi
        })
    }

    pub(crate) fn half_extents(&self) -> Vector {
        Vector::new(
            (self.max[0] - self.min[0]) / 2.0,
            (self.max[1] - self.min[1]) / 2.0,
            (self.max[2] - self.min[2]) / 2.0,
        )
    }

    pub(crate) fn center(&self) -> Vector {
        Vector::new(
            (self.min[0] + self.max[0]) / 2.0,
            (self.min[1] + self.max[1]) / 2.0,
            (self.min[2] + self.max[2]) / 2.0,
        )
    }

    fn volume(&self) -> f64 {
        let h = self.half_extents();
        8.0 * h.x * h.y * h.z
    }
}

/// How a block collides.
#[derive(Clone, Debug, PartialEq)]
pub enum BlockShape {
    /// A full cube.
    Full,
    /// Boxes in the block's frame, such as a slab's one or a stair's two.
    Boxes(Vec<Cuboid>),
    /// Nothing to collide with (a torch, a flower). Its mass still counts.
    Empty,
}

/// One block of a ship: where it is on the ship's grid, its mass in
/// kilograms, and how it collides.
#[derive(Clone, Debug, PartialEq)]
pub struct ShipBlock {
    pub pos: [i32; 3],
    pub mass: f64,
    pub shape: BlockShape,
}

impl ShipBlock {
    pub fn full(pos: [i32; 3], mass: f64) -> Self {
        Self {
            pos,
            mass,
            shape: BlockShape::Full,
        }
    }

    pub(crate) fn origin(&self) -> Vector {
        Vector::new(
            f64::from(self.pos[0]),
            f64::from(self.pos[1]),
            f64::from(self.pos[2]),
        )
    }

    /// The block's mass spread evenly over its boxes (over the whole cube if
    /// it is full or doesn't collide), in the ship's frame.
    fn mass_properties(&self) -> MassProperties {
        let unit = [Cuboid::new([0.0; 3], [1.0; 3])];
        let boxes: &[Cuboid] = match &self.shape {
            BlockShape::Boxes(boxes) => boxes,
            BlockShape::Full | BlockShape::Empty => &unit,
        };
        let volume: f64 = boxes.iter().map(Cuboid::volume).sum();
        let origin = self.origin();
        boxes
            .iter()
            .map(|b| {
                let density = self.mass / volume;
                MassProperties::from_cuboid(density, b.half_extents())
                    .transform_by(&Pose::from_translation(origin + b.center()))
            })
            .sum()
    }
}

/// A ship's mass properties in its own frame.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipMass {
    /// Kilograms.
    pub mass: f64,
    /// The centre of mass in the ship's frame.
    pub center: [f64; 3],
    /// The inertia tensor about the centre of mass, in the ship's frame
    /// (kg·m²), row by row.
    pub inertia: [[f64; 3]; 3],
}

/// The mass properties of `blocks` (see [`ShipBlock`]).
pub fn mass_of(blocks: &[ShipBlock]) -> ShipMass {
    from_rapier(&sum_mass_properties(blocks))
}

pub(crate) fn sum_mass_properties(blocks: &[ShipBlock]) -> MassProperties {
    blocks.iter().map(ShipBlock::mass_properties).sum()
}

pub(crate) fn from_rapier(m: &MassProperties) -> ShipMass {
    let c = m.local_com;
    let t = m.reconstruct_inertia_matrix();
    ShipMass {
        mass: m.mass(),
        center: [c.x, c.y, c.z],
        inertia: [
            [t.x_axis.x, t.y_axis.x, t.z_axis.x],
            [t.x_axis.y, t.y_axis.y, t.z_axis.y],
            [t.x_axis.z, t.y_axis.z, t.z_axis.z],
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f64, b: f64) -> bool {
        (a - b).abs() < 1e-9 * b.abs().max(1.0)
    }

    #[test]
    fn one_block_is_a_uniform_cube() {
        let m = mass_of(&[ShipBlock::full([0, 0, 0], 600.0)]);
        assert!(close(m.mass, 600.0));
        assert_eq!(m.center, [0.5, 0.5, 0.5]);
        for i in 0..3 {
            for j in 0..3 {
                let expected = if i == j { 100.0 } else { 0.0 }; // m (1² + 1²) / 12
                assert!(close(m.inertia[i][j], expected), "{i} {j}: {:?}", m.inertia);
            }
        }
    }

    #[test]
    fn a_rod_of_three_blocks_follows_the_parallel_axis_theorem() {
        let blocks: Vec<_> = (0..3).map(|x| ShipBlock::full([x, 0, 0], 1000.0)).collect();
        let m = mass_of(&blocks);
        assert!(close(m.mass, 3000.0));
        assert_eq!(m.center, [1.5, 0.5, 0.5]);
        // About x: each block m/6. About y and z: m/6 + m d², d = 1, 0, 1.
        assert!(close(m.inertia[0][0], 500.0));
        assert!(close(m.inertia[1][1], 2500.0));
        assert!(close(m.inertia[2][2], 2500.0));
    }

    #[test]
    fn an_l_shape_has_products_of_inertia() {
        // Blocks at (0,0,0), (1,0,0) and (0,1,0), 1 kg each: the centre of mass is
        // (5/6, 5/6, 1/2), and I_xy = -sum m (x - cx)(y - cy) = -(-1/3·-1/3 + 2/3·-1/3 + -1/3·2/3) = 1/3.
        let blocks = [
            ShipBlock::full([0, 0, 0], 1.0),
            ShipBlock::full([1, 0, 0], 1.0),
            ShipBlock::full([0, 1, 0], 1.0),
        ];
        let m = mass_of(&blocks);
        for (got, want) in m.center.iter().zip([5.0 / 6.0, 5.0 / 6.0, 0.5]) {
            assert!(close(*got, want), "{:?}", m.center);
        }
        assert!(close(m.inertia[0][1], 1.0 / 3.0), "{:?}", m.inertia);
        assert!(close(m.inertia[1][0], 1.0 / 3.0), "{:?}", m.inertia);
        assert!(m.inertia[0][2].abs() < 1e-12 && m.inertia[1][2].abs() < 1e-12);
    }

    #[test]
    fn a_slab_puts_its_mass_in_its_box() {
        let slab = ShipBlock {
            pos: [2, 0, 0],
            mass: 1250.0,
            shape: BlockShape::Boxes(vec![Cuboid::new([0.0; 3], [1.0, 0.5, 1.0])]),
        };
        let m = mass_of(&[slab]);
        assert!(close(m.mass, 1250.0));
        assert_eq!(m.center, [2.5, 0.25, 0.5]);
    }

    #[test]
    fn boxes_share_a_block_s_mass_by_volume() {
        // A stair: a bottom slab (volume 1/2) and a back quarter (volume 1/4).
        let stair = ShipBlock {
            pos: [0, 0, 0],
            mass: 3.0,
            shape: BlockShape::Boxes(vec![
                Cuboid::new([0.0; 3], [1.0, 0.5, 1.0]),
                Cuboid::new([0.0, 0.5, 0.5], [1.0, 1.0, 1.0]),
            ]),
        };
        let m = mass_of(&[stair]);
        assert!(close(m.mass, 3.0));
        // 2 kg at y = 0.25, 1 kg at y = 0.75; 2 kg at z = 0.5, 1 kg at z = 0.75.
        assert!(close(m.center[1], (2.0 * 0.25 + 0.75) / 3.0));
        assert!(close(m.center[2], (2.0 * 0.5 + 0.75) / 3.0));
    }

    #[test]
    fn a_block_without_collision_still_weighs() {
        let torch = ShipBlock {
            pos: [0, 0, 0],
            mass: 1.0,
            shape: BlockShape::Empty,
        };
        let m = mass_of(&[ShipBlock::full([1, 0, 0], 1.0), torch]);
        assert!(close(m.mass, 2.0));
        assert_eq!(m.center, [1.0, 0.5, 0.5]);
    }

    #[test]
    fn cuboid_validation() {
        let ok = Cuboid::new([0.0, 0.0, 0.0], [1.0, 1.5, 1.0]);
        assert!(ok.is_valid_within(-1.0, 2.0));
        assert!(!ok.is_valid_within(0.0, 1.0));
        assert!(!Cuboid::new([0.0; 3], [0.0, 1.0, 1.0]).is_valid_within(0.0, 1.0));
        assert!(!Cuboid::new([0.0; 3], [f64::NAN, 1.0, 1.0]).is_valid_within(0.0, 1.0));
        assert!(!Cuboid::new([1.0, 0.0, 0.0], [0.5, 1.0, 1.0]).is_valid_within(0.0, 1.0));
    }
}
```

The L-shape test checks the sign convention: parry stores I_xy = −Σ m (x − cx)(y − cy), which for those three blocks is +1/3.

- [ ] **Step 5: Run the tests.** This downloads and builds Rapier and updates `Cargo.lock`.

Run: `cd core && cargo test -p we-physics`
Expected: 7 passed, and one warning, that `is_valid_within` is never used: the world uses it from Task 2.

- [ ] **Step 6: Commit.**

```bash
git add core/Cargo.toml core/Cargo.lock core/crates/we-physics
git commit -m "M2a: we-physics, blocks and their mass properties"
```

---

### Task 2: The physics world

`World` holds terrain sections and ships, and steps them.
- **Sections** are set whole: a 64-word mask of full-cube blocks, in Minecraft's section order (`i = y·256 + z·16 + x`), plus the other blocks' boxes in the section's frame. Setting a section compares its mask with the old one and flips only the voxels that changed, in the one terrain `Voxels` collider. That collider is created with the first voxel and dropped with the last. Each section's boxes are one compound collider, replaced whole. Ships near a changed section are woken explicitly.
- **Ships** carry their forces and torque in their own frame. Before each step, the world puts them in world terms at the ship's current pose. Rapier keeps user forces until reset, so a force set once acts at every step.
- **Mass:** Rapier sums a body's mass properties only at its next step, after applying that step's forces, so `add_ship` recomputes them at once. Without that, the first step's forces act on a massless body.
- **Rapier's defaults are kept:** 4 solver iterations per step, friction 0.5, no restitution, and a speed cap of 400 m/s per substep. The cap is fine for the surface frame, which ends at about 150 m/s; M3 decides what orbital speeds need.

**Files:**
- Create: `core/crates/we-physics/src/world.rs`, `core/crates/we-physics/tests/behaviour.rs`
- Modify: `core/crates/we-physics/src/lib.rs`

- [ ] **Step 1: Write the behaviour tests first.** Create `core/crates/we-physics/tests/behaviour.rs`:

```rust
//! How ships move and rest: the measurements behind M2's design, kept true.

use std::ops::Range;
use we_physics::*;

const FULL: [u64; SECTION_WORDS] = [u64::MAX; SECTION_WORDS];
const EMPTY: [u64; SECTION_WORDS] = [0; SECTION_WORDS];

/// Full sections at section y = −1 for every section x in `xs` and z in `zs`:
/// a floor whose top is at y = 0.
fn floor(world: &mut World, xs: Range<i32>, zs: Range<i32>) {
    for x in xs {
        for z in zs.clone() {
            world
                .set_section(SectionPos { x, y: -1, z }, &FULL, &[])
                .unwrap();
        }
    }
}

fn cube(n: i32, mass: f64) -> Vec<ShipBlock> {
    let mut blocks = Vec::new();
    for x in 0..n {
        for y in 0..n {
            for z in 0..n {
                blocks.push(ShipBlock::full([x, y, z], mass));
            }
        }
    }
    blocks
}

fn run(world: &mut World, mut steps: u32) {
    while steps > 0 {
        let n = steps.min(MAX_STEPS);
        world.step(n).unwrap();
        steps -= n;
    }
}

fn state(world: &World, id: u32) -> ShipState {
    world.ship(id).unwrap()
}

/// The angle of a unit quaternion's rotation, in radians.
fn tilt(motion: &ShipMotion) -> f64 {
    2.0 * motion.rotation[3].abs().min(1.0).acos()
}

#[test]
fn a_ship_comes_to_rest_on_the_terrain_and_sleeps() {
    let mut world = World::new();
    floor(&mut world, -1..1, -1..1);
    world
        .add_ship(1, &ShipMotion::at([-2.0, 2.0, -2.0]), &cube(4, 2500.0))
        .unwrap();
    run(&mut world, 1200);
    let s = state(&world, 1);
    assert!(s.motion.position[1].abs() < 1e-3, "{s:?}");
    assert!(tilt(&s.motion) < 1e-4, "{s:?}");
    assert!(s.sleeping, "{s:?}");
}

#[test]
fn a_ship_slides_across_section_edges_without_catching() {
    // Pushed through its centre of mass at twice friction's pull, a 2x2x2 ship
    // crosses the section edges at x = 0, 16 and 32. Were the floor one collider
    // per section, it would catch on the first edge and tip (the M2a probe).
    let mut world = World::new();
    floor(&mut world, -1..4, -1..1);
    world
        .add_ship(1, &ShipMotion::at([-6.0, 0.0, -1.0]), &cube(2, 1000.0))
        .unwrap();
    run(&mut world, 30);
    let push = 2.0 * 0.5 * 8000.0 * STANDARD_GRAVITY;
    world
        .set_ship_controls(
            1,
            &[ShipForce {
                force: [push, 0.0, 0.0],
                point: [1.0, 1.0, 1.0],
            }],
            [0.0; 3],
        )
        .unwrap();
    let (mut max_vy, mut max_tilt) = (0.0f64, 0.0f64);
    for _ in 0..240 {
        world.step(1).unwrap();
        let s = state(&world, 1);
        max_vy = max_vy.max(s.motion.linvel[1].abs());
        max_tilt = max_tilt.max(tilt(&s.motion));
    }
    let s = state(&world, 1);
    assert!(s.motion.position[0] > 32.5, "{s:?}");
    assert!(max_vy < 0.01, "vertical speed {max_vy}");
    assert!(max_tilt < 0.01, "tilt {max_tilt}");
}

#[test]
fn mass_properties_come_from_the_blocks() {
    let mut world = World::new();
    let rod: Vec<_> = (0..3).map(|x| ShipBlock::full([x, 0, 0], 1000.0)).collect();
    world
        .add_ship(7, &ShipMotion::at([0.0, 50.0, 0.0]), &rod)
        .unwrap();
    let m = world.ship_mass(7).unwrap();
    let expected = mass_of(&rod);
    assert!((m.mass - 3000.0).abs() < 1e-9);
    for i in 0..3 {
        assert!((m.center[i] - expected.center[i]).abs() < 1e-9);
        for j in 0..3 {
            assert!(
                (m.inertia[i][j] - expected.inertia[i][j]).abs() < 1e-6,
                "{m:?}"
            );
        }
    }
}

#[test]
fn an_off_centre_force_spins_the_ship() {
    // A 3,000 kg rod, pushed up at its end, 1.5 m from its centre of mass:
    // I_z = 2,500 kg m², so one step gives 1,000 × 1.5 / 2,500 / 60 rad/s.
    let mut world = World::new();
    let rod: Vec<_> = (0..3).map(|x| ShipBlock::full([x, 0, 0], 1000.0)).collect();
    world.add_ship(1, &ShipMotion::at([0.0; 3]), &rod).unwrap();
    world.set_ship_gravity(1, 0.0).unwrap();
    world
        .set_ship_controls(
            1,
            &[ShipForce {
                force: [0.0, 1000.0, 0.0],
                point: [3.0, 0.5, 0.5],
            }],
            [0.0; 3],
        )
        .unwrap();
    world.step(1).unwrap();
    let s = state(&world, 1);
    assert!((s.motion.angvel[2] - 0.01).abs() < 1e-9, "{s:?}");
    assert!(
        (s.motion.linvel[1] - 1000.0 / 3000.0 / 60.0).abs() < 1e-9,
        "{s:?}"
    );
}

#[test]
fn forces_and_torques_turn_with_the_ship() {
    // Turned a quarter turn about +z, the ship's +y points along world −x.
    let half = std::f64::consts::FRAC_PI_4;
    let mut motion = ShipMotion::at([0.0; 3]);
    motion.rotation = [0.0, 0.0, half.sin(), half.cos()];
    let mut world = World::new();
    world.add_ship(1, &motion, &cube(1, 600.0)).unwrap();
    world.set_ship_gravity(1, 0.0).unwrap();
    world
        .set_ship_controls(
            1,
            &[ShipForce {
                force: [0.0, 600.0, 0.0],
                point: [0.5, 0.5, 0.5],
            }],
            [0.0, 10.0, 0.0],
        )
        .unwrap();
    run(&mut world, 60);
    let s = state(&world, 1);
    // 1 m/s² for 1 s along world −x; the torque about the ship's +y turns it about world −x.
    assert!((s.motion.linvel[0] + 1.0).abs() < 1e-3, "{s:?}");
    assert!(s.motion.linvel[1].abs() < 1e-3, "{s:?}");
    assert!(
        s.motion.angvel[0] < -0.05 && s.motion.angvel[1].abs() < 1e-3,
        "{s:?}"
    );
}

#[test]
fn controls_can_be_switched_off() {
    let mut world = World::new();
    world
        .add_ship(1, &ShipMotion::at([0.0; 3]), &cube(1, 600.0))
        .unwrap();
    world.set_ship_gravity(1, 0.0).unwrap();
    world
        .set_ship_controls(
            1,
            &[ShipForce {
                force: [600.0, 0.0, 0.0],
                point: [0.5; 3],
            }],
            [0.0; 3],
        )
        .unwrap();
    run(&mut world, 60);
    world.set_ship_controls(1, &[], [0.0; 3]).unwrap();
    let before = state(&world, 1).motion.linvel[0];
    run(&mut world, 60);
    let after = state(&world, 1).motion.linvel[0];
    assert!(
        (before - 1.0).abs() < 1e-9 && (after - before).abs() < 1e-12,
        "{before} {after}"
    );
}

#[test]
fn partial_blocks_collide_as_their_boxes() {
    let bottom = Cuboid::new([0.0; 3], [1.0, 0.5, 1.0]);
    let top = Cuboid::new([0.0, 0.5, 0.0], [1.0, 1.0, 1.0]);
    let mut world = World::new();
    floor(&mut world, -1..1, -1..1);
    // A full block standing on a bottom slab rests with its frame at y = 0 ...
    let tower = [
        ShipBlock {
            pos: [0, 0, 0],
            mass: 1250.0,
            shape: BlockShape::Boxes(vec![bottom]),
        },
        ShipBlock::full([0, 1, 0], 2500.0),
    ];
    world
        .add_ship(1, &ShipMotion::at([-4.0, 1.0, 0.0]), &tower)
        .unwrap();
    // ... but the block falls onto the slab, 0.5 lower, if that slab is a top slab.
    let hanging = [ShipBlock {
        pos: [0, 0, 0],
        mass: 1250.0,
        shape: BlockShape::Boxes(vec![top]),
    }];
    world
        .add_ship(2, &ShipMotion::at([4.0, 1.0, 0.0]), &hanging)
        .unwrap();
    run(&mut world, 600);
    assert!(
        state(&world, 1).motion.position[1].abs() < 1e-3,
        "{:?}",
        state(&world, 1)
    );
    assert!(
        (state(&world, 2).motion.position[1] + 0.5).abs() < 1e-3,
        "{:?}",
        state(&world, 2)
    );
}

#[test]
fn terrain_boxes_hold_ships_up() {
    // A section with no full blocks, only a slab floor: boxes 0.5 high.
    let mut world = World::new();
    let slab_floor = Cuboid::new([0.0; 3], [16.0, 0.5, 16.0]);
    world
        .set_section(SectionPos { x: 0, y: 0, z: 0 }, &EMPTY, &[slab_floor])
        .unwrap();
    world
        .add_ship(1, &ShipMotion::at([6.0, 3.0, 6.0]), &cube(2, 1000.0))
        .unwrap();
    run(&mut world, 600);
    assert!(
        (state(&world, 1).motion.position[1] - 0.5).abs() < 1e-3,
        "{:?}",
        state(&world, 1)
    );
}

#[test]
fn ships_stack() {
    let mut world = World::new();
    floor(&mut world, -1..1, -1..1);
    let mut base = Vec::new();
    for x in 0..8 {
        for y in 0..2 {
            for z in 0..8 {
                base.push(ShipBlock::full([x, y, z], 2500.0));
            }
        }
    }
    world
        .add_ship(1, &ShipMotion::at([-4.0, 0.2, -4.0]), &base)
        .unwrap();
    world
        .add_ship(2, &ShipMotion::at([-2.5, 3.0, -2.5]), &cube(5, 2500.0))
        .unwrap();
    run(&mut world, 1200);
    let (a, b) = (state(&world, 1), state(&world, 2));
    assert!(a.motion.position[1].abs() < 1e-3, "{a:?}");
    assert!((b.motion.position[1] - 2.0).abs() < 2e-3, "{b:?}");
    assert!(a.sleeping && b.sleeping);
}

#[test]
fn removing_the_ground_wakes_a_ship_and_drops_it() {
    let mut world = World::new();
    floor(&mut world, -1..1, -1..1);
    world
        .add_ship(1, &ShipMotion::at([2.0, 0.0, 2.0]), &cube(2, 1000.0))
        .unwrap();
    run(&mut world, 600);
    assert!(state(&world, 1).sleeping);
    // Hollow out the top layer under the ship's corner section only: still held by the rest.
    let mut holed = FULL;
    holed[(15 * 256 + 2 * 16) / 64] = 0; // y = 15, z = 2 and 3: x = 0..16 of both rows
    world
        .set_section(SectionPos { x: 0, y: -1, z: 0 }, &holed, &[])
        .unwrap();
    run(&mut world, 120);
    let s = state(&world, 1);
    assert!(
        (s.motion.position[1] + 1.0).abs() < 1e-3,
        "dropped one block into the trench: {s:?}"
    );
    world.clear_section(SectionPos { x: 0, y: -1, z: 0 });
    run(&mut world, 60);
    assert!(
        state(&world, 1).motion.position[1] < -4.0,
        "{:?}",
        state(&world, 1)
    );
    assert_eq!(world.section_count(), 3);
}

#[test]
fn a_fast_ship_does_not_tunnel_through_one_block_of_floor() {
    let mut world = World::new();
    let mut layer = EMPTY;
    for word in &mut layer[(15 * 256) / 64..] {
        *word = u64::MAX; // y = 15: the floor is the one block layer at y = −1
    }
    world
        .set_section(
            SectionPos {
                x: -1,
                y: -1,
                z: -1,
            },
            &layer,
            &[],
        )
        .unwrap();
    let mut motion = ShipMotion::at([-8.0, 60.0, -8.0]);
    motion.linvel = [0.0, -300.0, 0.0];
    world.add_ship(1, &motion, &cube(2, 2500.0)).unwrap();
    run(&mut world, 120);
    let s = state(&world, 1);
    assert!(
        s.motion.position[1] > -0.01 && s.motion.position[1] < 0.01,
        "{s:?}"
    );
}

#[test]
fn idle_ships_sleep() {
    let mut world = World::new();
    floor(&mut world, -6..6, -1..1);
    for i in 0..20 {
        let x = -90.0 + 9.0 * f64::from(i);
        world
            .add_ship(i, &ShipMotion::at([x, 0.5, -2.0]), &cube(4, 2500.0))
            .unwrap();
    }
    run(&mut world, 900);
    assert!(
        world.ships().iter().all(|s| s.sleeping),
        "{:?}",
        world.ships()
    );
}

#[test]
fn gravity_is_set_per_ship() {
    let mut world = World::new();
    world
        .add_ship(1, &ShipMotion::at([0.0, 100.0, 0.0]), &cube(1, 1.0))
        .unwrap();
    world
        .add_ship(2, &ShipMotion::at([10.0, 100.0, 0.0]), &cube(1, 1.0))
        .unwrap();
    world.set_ship_gravity(2, 1.62).unwrap();
    run(&mut world, 60);
    // g t² / 2 after a second, to within the integrator's error (Rapier's substeps make it 0.4% more).
    let fell = |id| 100.0 - state(&world, id).motion.position[1];
    assert!(
        (fell(1) / (STANDARD_GRAVITY / 2.0) - 1.0).abs() < 0.01,
        "{}",
        fell(1)
    );
    assert!(
        (fell(1) / fell(2) - STANDARD_GRAVITY / 1.62).abs() < 1e-9,
        "{} {}",
        fell(1),
        fell(2)
    );
}

#[test]
fn motion_can_be_set_and_ships_are_listed_by_id() {
    let mut world = World::new();
    for id in [9, 2, 5] {
        world
            .add_ship(
                id,
                &ShipMotion::at([f64::from(id) * 10.0, 50.0, 0.0]),
                &cube(1, 1.0),
            )
            .unwrap();
    }
    let ids: Vec<u32> = world.ships().iter().map(|s| s.id).collect();
    assert_eq!(ids, [2, 5, 9]);
    let target = ShipMotion {
        position: [1.0, 2.0, 3.0],
        rotation: [0.0, 1.0, 0.0, 0.0],
        linvel: [4.0, 0.0, 0.0],
        angvel: [0.0, 0.5, 0.0],
    };
    world.set_ship_motion(5, &target).unwrap();
    let s = state(&world, 5);
    assert_eq!(s.motion.position, target.position);
    assert_eq!(s.motion.rotation, target.rotation);
    assert_eq!(s.motion.linvel, target.linvel);
    assert!(!s.sleeping);
    world.remove_ship(5).unwrap();
    assert_eq!(world.ship_count(), 2);
    assert!(world.ship(5).is_none());
}

#[test]
fn bad_arguments_are_refused() {
    let mut world = World::new();
    let at = ShipMotion::at([0.0; 3]);
    let one = cube(1, 1.0);
    world.add_ship(1, &at, &one).unwrap();
    assert_eq!(
        world.add_ship(1, &at, &one),
        Err(PhysicsError::DuplicateShip(1))
    );
    assert_eq!(world.remove_ship(2), Err(PhysicsError::UnknownShip(2)));
    assert_eq!(
        world.set_ship_gravity(2, 1.0),
        Err(PhysicsError::UnknownShip(2))
    );
    let invalid =
        |r: Result<(), PhysicsError>| assert!(matches!(r, Err(PhysicsError::Invalid(_))), "{r:?}");
    invalid(world.add_ship(2, &at, &[]));
    invalid(world.add_ship(2, &at, &cube(1, 0.0)));
    invalid(world.add_ship(2, &at, &cube(1, f64::NAN)));
    invalid(world.add_ship(
        2,
        &at,
        &[ShipBlock::full([0; 3], 1.0), ShipBlock::full([0; 3], 1.0)],
    ));
    invalid(world.add_ship(
        2,
        &at,
        &[ShipBlock {
            pos: [0; 3],
            mass: 1.0,
            shape: BlockShape::Empty,
        }],
    ));
    invalid(world.add_ship(
        2,
        &at,
        &[ShipBlock {
            pos: [0; 3],
            mass: 1.0,
            shape: BlockShape::Boxes(vec![]),
        }],
    ));
    let too_tall = Cuboid::new([0.0; 3], [1.0, 2.5, 1.0]);
    invalid(world.add_ship(
        2,
        &at,
        &[ShipBlock {
            pos: [0; 3],
            mass: 1.0,
            shape: BlockShape::Boxes(vec![too_tall]),
        }],
    ));
    let mut spun = at;
    spun.rotation = [0.0; 4];
    invalid(world.add_ship(2, &spun, &one));
    let mut huge = Vec::new();
    for i in 0..=MAX_SHIP_BLOCKS as i32 {
        huge.push(ShipBlock::full([i % 256, i / 65_536, (i / 256) % 256], 1.0));
    }
    invalid(world.add_ship(2, &at, &huge));
    invalid(world.set_ship_gravity(1, f64::INFINITY));
    invalid(world.set_ship_controls(
        1,
        &[ShipForce {
            force: [f64::NAN, 0.0, 0.0],
            point: [0.0; 3],
        }],
        [0.0; 3],
    ));
    let many = vec![
        ShipForce {
            force: [0.0; 3],
            point: [0.0; 3]
        };
        MAX_SHIP_FORCES + 1
    ];
    invalid(world.set_ship_controls(1, &many, [0.0; 3]));
    invalid(world.step(MAX_STEPS + 1));
    let outside = Cuboid::new([0.0; 3], [18.0, 1.0, 1.0]);
    invalid(world.set_section(SectionPos { x: 0, y: 0, z: 0 }, &EMPTY, &[outside]));
    assert_eq!(world.ship_count(), 1);
    assert_eq!(world.section_count(), 0);
}
```

- [ ] **Step 2: Write the world.** Create `core/crates/we-physics/src/world.rs`:

```rust
//! A physics world: terrain sections and ships, stepped at a fixed rate.

use crate::blocks::{
    self, BlockShape, Cuboid, MAX_BLOCK_BOXES, MAX_SHIP_BLOCKS, ShipBlock, ShipMass,
};
use crate::{SECTION_BLOCKS, SECTION_SIZE, SECTION_WORDS};
use rapier3d_f64::parry::bounding_volume::Aabb;
use rapier3d_f64::prelude::*;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;

/// Seconds per step: Minecraft's 20 Hz tick is three steps.
pub const STEP_SECONDS: f64 = 1.0 / 60.0;
/// Most steps one [`World::step`] call may take (ten seconds).
pub const MAX_STEPS: u32 = 600;
/// A new ship's gravity in m/s², until [`World::set_ship_gravity`] says otherwise.
pub const STANDARD_GRAVITY: f64 = 9.81;
/// Most forces one ship may have (one per thruster).
pub const MAX_SHIP_FORCES: usize = 4096;

/// A terrain section: blocks [16x, 16x + 16) × [16y, 16y + 16) × [16z, 16z + 16).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct SectionPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

/// Where a ship is and how it moves.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipMotion {
    /// Where the ship's frame has its origin, in world coordinates.
    pub position: [f64; 3],
    /// The unit quaternion (x, y, z, w) that turns the ship's frame into the world's.
    pub rotation: [f64; 4],
    /// The velocity of the centre of mass, m/s.
    pub linvel: [f64; 3],
    /// Radians per second about each world axis.
    pub angvel: [f64; 3],
}

impl ShipMotion {
    /// At rest at `position`, unrotated.
    pub fn at(position: [f64; 3]) -> Self {
        Self {
            position,
            rotation: [0.0, 0.0, 0.0, 1.0],
            linvel: [0.0; 3],
            angvel: [0.0; 3],
        }
    }
}

/// A ship's motion after a step, and whether it is asleep (at rest, costing almost nothing).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipState {
    pub id: u32,
    pub motion: ShipMotion,
    pub sleeping: bool,
}

/// A force on a ship, in its own frame: `force` newtons at `point`, both turning with the ship.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipForce {
    pub force: [f64; 3],
    pub point: [f64; 3],
}

/// Why a [`World`] call did nothing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhysicsError {
    /// An argument was out of range; the text says which.
    Invalid(&'static str),
    UnknownShip(u32),
    DuplicateShip(u32),
}

impl fmt::Display for PhysicsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(why) => write!(f, "invalid argument: {why}"),
            Self::UnknownShip(id) => write!(f, "no ship {id}"),
            Self::DuplicateShip(id) => write!(f, "ship {id} already exists"),
        }
    }
}

impl std::error::Error for PhysicsError {}

struct Section {
    full: Box<[u64; SECTION_WORDS]>,
    partial: Option<ColliderHandle>,
}

struct Ship {
    body: RigidBodyHandle,
    forces: Vec<ShipForce>,
    torque: [f64; 3],
}

impl Ship {
    fn is_driven(&self) -> bool {
        !self.forces.is_empty() || self.torque != [0.0; 3]
    }
}

/// Terrain and ships. See the crate documentation.
pub struct World {
    physics: PhysicsWorld,
    /// All terrain sections' full blocks, as one voxel shape; `None` while there are none.
    terrain: Option<ColliderHandle>,
    terrain_voxels: usize,
    sections: HashMap<SectionPos, Section>,
    ships: BTreeMap<u32, Ship>,
}

impl Default for World {
    fn default() -> Self {
        Self::new()
    }
}

fn vector(v: [f64; 3]) -> Vector {
    Vector::new(v[0], v[1], v[2])
}

fn array(v: Vector) -> [f64; 3] {
    [v.x, v.y, v.z]
}

fn finite(v: &[f64]) -> bool {
    v.iter().all(|x| x.is_finite())
}

/// The block at `index` (Minecraft's section order: y, then z, then x) of `pos`.
fn voxel_key(pos: SectionPos, index: usize) -> IVector {
    let (x, y, z) = (index & 15, index >> 8, (index >> 4) & 15);
    let s = i64::from(SECTION_SIZE);
    IVector::new(
        i64::from(pos.x) * s + x as i64,
        i64::from(pos.y) * s + y as i64,
        i64::from(pos.z) * s + z as i64,
    )
}

fn section_origin(pos: SectionPos) -> Vector {
    let s = f64::from(SECTION_SIZE);
    Vector::new(
        f64::from(pos.x) * s,
        f64::from(pos.y) * s,
        f64::from(pos.z) * s,
    )
}

fn motion_pose(motion: &ShipMotion) -> Result<Pose, PhysicsError> {
    if !(finite(&motion.position)
        && finite(&motion.rotation)
        && finite(&motion.linvel)
        && finite(&motion.angvel))
    {
        return Err(PhysicsError::Invalid("ship motion must be finite"));
    }
    let [x, y, z, w] = motion.rotation;
    let q = Rotation::from_xyzw(x, y, z, w);
    let norm = q.length();
    if !(0.5..=2.0).contains(&norm) {
        return Err(PhysicsError::Invalid(
            "ship rotation must be a unit quaternion",
        ));
    }
    Ok(Pose::from_parts(vector(motion.position), q / norm))
}

impl World {
    pub fn new() -> Self {
        let mut physics = PhysicsWorld::new();
        // Each ship's gravity scale is its local g (World::set_ship_gravity).
        physics.gravity = Vector::NEG_Y;
        physics.integration_parameters.dt = STEP_SECONDS;
        Self {
            physics,
            terrain: None,
            terrain_voxels: 0,
            sections: HashMap::new(),
            ships: BTreeMap::new(),
        }
    }

    /// Sets terrain section `pos`, replacing what was there. Bit `i` of
    /// `full` (word `i / 64`, bit `i % 64`) marks block `i` as a full cube,
    /// with blocks in Minecraft's section order: `i = y·256 + z·16 + x`.
    /// `partial` holds the boxes of every other colliding block, in the
    /// section's frame (each within −1..=17 on every axis). Wakes ships near
    /// the section.
    pub fn set_section(
        &mut self,
        pos: SectionPos,
        full: &[u64; SECTION_WORDS],
        partial: &[Cuboid],
    ) -> Result<(), PhysicsError> {
        if partial.len() > SECTION_BLOCKS * MAX_BLOCK_BOXES {
            return Err(PhysicsError::Invalid("too many boxes in a section"));
        }
        if !partial
            .iter()
            .all(|b| b.is_valid_within(-1.0, f64::from(SECTION_SIZE) + 1.0))
        {
            return Err(PhysicsError::Invalid(
                "a section box is empty, not finite or out of range",
            ));
        }
        let old = self.sections.remove(&pos);
        let old_full = old.as_ref().map_or([0; SECTION_WORDS], |s| *s.full);
        self.edit_voxels(pos, &old_full, full);
        if let Some(handle) = old.and_then(|s| s.partial) {
            self.physics.remove_collider(handle);
        }
        let partial = if partial.is_empty() {
            None
        } else {
            let origin = section_origin(pos);
            let shapes = partial
                .iter()
                .map(|b| {
                    let h = b.half_extents();
                    (
                        Pose::from_translation(origin + b.center()),
                        SharedShape::cuboid(h.x, h.y, h.z),
                    )
                })
                .collect();
            Some(
                self.physics
                    .insert_collider(ColliderBuilder::compound(shapes).build(), None),
            )
        };
        self.sections.insert(
            pos,
            Section {
                full: Box::new(*full),
                partial,
            },
        );
        self.wake_ships_near(pos);
        Ok(())
    }

    /// Removes terrain section `pos`, if it was set, and wakes ships near it.
    pub fn clear_section(&mut self, pos: SectionPos) {
        if let Some(section) = self.sections.remove(&pos) {
            self.edit_voxels(pos, &section.full, &[0; SECTION_WORDS]);
            if let Some(handle) = section.partial {
                self.physics.remove_collider(handle);
            }
            self.wake_ships_near(pos);
        }
    }

    /// The number of terrain sections set.
    pub fn section_count(&self) -> usize {
        self.sections.len()
    }

    fn edit_voxels(
        &mut self,
        pos: SectionPos,
        old: &[u64; SECTION_WORDS],
        new: &[u64; SECTION_WORDS],
    ) {
        let mut filled = Vec::new();
        let mut emptied = Vec::new();
        for (w, (&a, &b)) in old.iter().zip(new).enumerate() {
            let mut changed = a ^ b;
            while changed != 0 {
                let bit = changed.trailing_zeros() as usize;
                changed &= changed - 1;
                let key = voxel_key(pos, w * 64 + bit);
                if b >> bit & 1 == 1 {
                    filled.push(key)
                } else {
                    emptied.push(key)
                }
            }
        }
        if filled.is_empty() && emptied.is_empty() {
            return;
        }
        self.terrain_voxels = self.terrain_voxels + filled.len() - emptied.len();
        match self.terrain {
            None => {
                // No voxels existed, so nothing was emptied.
                let collider = ColliderBuilder::voxels(Vector::ONE, &filled).build();
                self.terrain = Some(self.physics.insert_collider(collider, None));
            }
            Some(handle) if self.terrain_voxels == 0 => {
                self.physics.remove_collider(handle);
                self.terrain = None;
            }
            Some(handle) => {
                let voxels = self.physics.colliders[handle]
                    .shape_mut()
                    .as_voxels_mut()
                    .expect("the terrain collider is a voxel shape");
                for key in filled {
                    voxels.set_voxel(key, true);
                }
                for key in emptied {
                    voxels.set_voxel(key, false);
                }
            }
        }
    }

    fn wake_ships_near(&mut self, pos: SectionPos) {
        let min = section_origin(pos) - Vector::ONE;
        let near = Aabb::new(min, min + Vector::splat(f64::from(SECTION_SIZE) + 2.0));
        let asleep: Vec<RigidBodyHandle> = self
            .ships
            .values()
            .map(|s| s.body)
            .filter(|&h| {
                let body = &self.physics.bodies[h];
                body.is_sleeping()
                    && body
                        .colliders()
                        .iter()
                        .any(|&c| self.physics.colliders[c].compute_aabb().intersects(&near))
            })
            .collect();
        for handle in asleep {
            self.physics.wake_up(handle, true);
        }
    }

    /// Adds ship `id`, built from `blocks` (see [`ShipBlock`]), moving as `motion` says.
    ///
    /// Each block needs a positive mass and a position of its own. A block's
    /// boxes number 1 to [`MAX_BLOCK_BOXES`], each within −1..=2 of its
    /// block on every axis. At least one block must collide.
    pub fn add_ship(
        &mut self,
        id: u32,
        motion: &ShipMotion,
        blocks: &[ShipBlock],
    ) -> Result<(), PhysicsError> {
        if self.ships.contains_key(&id) {
            return Err(PhysicsError::DuplicateShip(id));
        }
        let pose = motion_pose(motion)?;
        if blocks.is_empty() || blocks.len() > MAX_SHIP_BLOCKS {
            return Err(PhysicsError::Invalid(
                "a ship has 1 to MAX_SHIP_BLOCKS blocks",
            ));
        }
        let mut seen = HashSet::with_capacity(blocks.len());
        let mut full = Vec::new();
        let mut boxes = Vec::new();
        for block in blocks {
            if !(block.mass.is_finite() && block.mass > 0.0) {
                return Err(PhysicsError::Invalid(
                    "a block's mass must be positive and finite",
                ));
            }
            if !seen.insert(block.pos) {
                return Err(PhysicsError::Invalid("two blocks share a position"));
            }
            match &block.shape {
                BlockShape::Full => full.push(IVector::new(
                    i64::from(block.pos[0]),
                    i64::from(block.pos[1]),
                    i64::from(block.pos[2]),
                )),
                BlockShape::Boxes(list) => {
                    if list.is_empty() || list.len() > MAX_BLOCK_BOXES {
                        return Err(PhysicsError::Invalid(
                            "a block has 1 to MAX_BLOCK_BOXES boxes",
                        ));
                    }
                    for b in list {
                        if !b.is_valid_within(-1.0, 2.0) {
                            return Err(PhysicsError::Invalid(
                                "a block box is empty, not finite or out of range",
                            ));
                        }
                        let h = b.half_extents();
                        boxes.push((
                            Pose::from_translation(block.origin() + b.center()),
                            SharedShape::cuboid(h.x, h.y, h.z),
                        ));
                    }
                }
                BlockShape::Empty => {}
            }
        }
        if full.is_empty() && boxes.is_empty() {
            return Err(PhysicsError::Invalid("a ship needs a block that collides"));
        }
        let body = RigidBodyBuilder::dynamic()
            .pose(pose)
            .linvel(vector(motion.linvel))
            .angvel(vector(motion.angvel))
            .additional_mass_properties(blocks::sum_mass_properties(blocks))
            .gravity_scale(STANDARD_GRAVITY)
            .build();
        let handle = self.physics.insert_body(body);
        // The body carries the mass; the colliders only collide.
        if !full.is_empty() {
            let voxels = ColliderBuilder::voxels(Vector::ONE, &full)
                .density(0.0)
                .build();
            self.physics.insert_collider(voxels, Some(handle));
        }
        if !boxes.is_empty() {
            let compound = ColliderBuilder::compound(boxes).density(0.0).build();
            self.physics.insert_collider(compound, Some(handle));
        }
        // Rapier would otherwise sum the mass only at the next step, after applying this step's forces.
        let physics = &mut self.physics;
        physics.bodies[handle].recompute_mass_properties_from_colliders(&physics.colliders);
        self.ships.insert(
            id,
            Ship {
                body: handle,
                forces: Vec::new(),
                torque: [0.0; 3],
            },
        );
        Ok(())
    }

    /// Removes ship `id`.
    pub fn remove_ship(&mut self, id: u32) -> Result<(), PhysicsError> {
        let ship = self
            .ships
            .remove(&id)
            .ok_or(PhysicsError::UnknownShip(id))?;
        self.physics.remove_body(ship.body);
        Ok(())
    }

    fn ship_body(&mut self, id: u32) -> Result<&mut RigidBody, PhysicsError> {
        let ship = self.ships.get(&id).ok_or(PhysicsError::UnknownShip(id))?;
        Ok(&mut self.physics.bodies[ship.body])
    }

    /// Moves ship `id` to `motion` at once (a teleport), and wakes it.
    pub fn set_ship_motion(&mut self, id: u32, motion: &ShipMotion) -> Result<(), PhysicsError> {
        let pose = motion_pose(motion)?;
        let body = self.ship_body(id)?;
        body.set_position(pose, true);
        body.set_linvel(vector(motion.linvel), true);
        body.set_angvel(vector(motion.angvel), true);
        Ok(())
    }

    /// Ship `id`'s gravity, in m/s² along −Y (the surface frame's down).
    pub fn set_ship_gravity(&mut self, id: u32, gravity: f64) -> Result<(), PhysicsError> {
        if !gravity.is_finite() {
            return Err(PhysicsError::Invalid("gravity must be finite"));
        }
        self.ship_body(id)?.set_gravity_scale(gravity, true);
        Ok(())
    }

    /// The forces and torque (N·m, in the ship's frame) that act on ship `id`
    /// at every step until they are set again. Empty forces and a zero
    /// torque leave it coasting.
    pub fn set_ship_controls(
        &mut self,
        id: u32,
        forces: &[ShipForce],
        torque: [f64; 3],
    ) -> Result<(), PhysicsError> {
        if forces.len() > MAX_SHIP_FORCES {
            return Err(PhysicsError::Invalid("too many forces on one ship"));
        }
        if !(forces.iter().all(|f| finite(&f.force) && finite(&f.point)) && finite(&torque)) {
            return Err(PhysicsError::Invalid("forces and torque must be finite"));
        }
        let ship = self
            .ships
            .get_mut(&id)
            .ok_or(PhysicsError::UnknownShip(id))?;
        ship.forces = forces.to_vec();
        ship.torque = torque;
        if !ship.is_driven() {
            let body = &mut self.physics.bodies[ship.body];
            body.reset_forces(false);
            body.reset_torques(false);
        }
        Ok(())
    }

    /// Advances `steps` steps of [`STEP_SECONDS`] each.
    pub fn step(&mut self, steps: u32) -> Result<(), PhysicsError> {
        if steps > MAX_STEPS {
            return Err(PhysicsError::Invalid("too many steps in one call"));
        }
        for _ in 0..steps {
            for ship in self.ships.values().filter(|s| s.is_driven()) {
                // The forces turn with the ship, so they are put in world terms every step.
                let body = &mut self.physics.bodies[ship.body];
                let pose = *body.position();
                body.reset_forces(false);
                body.reset_torques(false);
                for f in &ship.forces {
                    body.add_force_at_point(
                        pose.rotation * vector(f.force),
                        pose.transform_point(vector(f.point)),
                        true,
                    );
                }
                body.add_torque(pose.rotation * vector(ship.torque), true);
            }
            self.physics.step();
        }
        Ok(())
    }

    /// The number of ships.
    pub fn ship_count(&self) -> usize {
        self.ships.len()
    }

    /// Ship `id`'s state, if it exists.
    pub fn ship(&self, id: u32) -> Option<ShipState> {
        self.ships.get(&id).map(|s| self.state(id, s))
    }

    /// Every ship's state, by id.
    pub fn ships(&self) -> Vec<ShipState> {
        self.ships
            .iter()
            .map(|(&id, s)| self.state(id, s))
            .collect()
    }

    fn state(&self, id: u32, ship: &Ship) -> ShipState {
        let body = &self.physics.bodies[ship.body];
        let pose = body.position();
        let q = pose.rotation;
        ShipState {
            id,
            motion: ShipMotion {
                position: array(pose.translation),
                rotation: [q.x, q.y, q.z, q.w],
                linvel: array(body.linvel()),
                angvel: array(body.angvel()),
            },
            sleeping: body.is_sleeping(),
        }
    }

    /// Ship `id`'s mass properties in its own frame, if it exists.
    pub fn ship_mass(&self, id: u32) -> Option<ShipMass> {
        let ship = self.ships.get(&id)?;
        Some(blocks::from_rapier(
            &self.physics.bodies[ship.body]
                .mass_properties()
                .local_mprops,
        ))
    }
}
```

- [ ] **Step 3: Export it.** In `core/crates/we-physics/src/lib.rs`, add `mod world;` after `mod blocks;`, and after the `pub use blocks::{…};` item add:

```rust
pub use world::{
    MAX_SHIP_FORCES, MAX_STEPS, PhysicsError, STANDARD_GRAVITY, STEP_SECONDS, SectionPos,
    ShipForce, ShipMotion, ShipState, World,
};
```

- [ ] **Step 4: Run the tests, lints and formatting.**

Run: `cd core && cargo test -p we-physics && cargo clippy -p we-physics --all-targets -- -D warnings && cargo fmt --all --check`
Expected: 7 and 15 passed; no warnings; no formatting changes.

- [ ] **Step 5: Commit.**

```bash
git add core/crates/we-physics
git commit -m "M2a: the physics world: terrain sections, ships, controls and steps"
```

---

### Task 3: The C ABI (v6)

Batched flat arrays, one call per ship, section, tick or read, never per block. The layouts are documented at the top of `physics.rs` and mirrored by `NativeCore.java`. A new status, `WE_ERR_EXISTS` (−4), means a ship id is taken. As everywhere in `we-ffi`, every function catches panics, and freeing catches a panicking drop too.

**Files:**
- Create: `core/crates/we-ffi/src/physics.rs`
- Modify: `core/crates/we-ffi/Cargo.toml`, `core/crates/we-ffi/src/lib.rs`, `core/crates/we-ffi/tests/abi.rs`

- [ ] **Step 1: Depend on the crate.** In `core/crates/we-ffi/Cargo.toml`, add `we-physics.workspace = true` before `we-planet.workspace = true`.

- [ ] **Step 2: The functions, tests included.** Create `core/crates/we-ffi/src/physics.rs`:

```rust
//! Physics worlds (M2a): terrain sections and ships, behind an opaque handle.
//!
//! Layouts shared with `NativeCore.java`, all little-endian native values:
//! - **motion**, 13 doubles: position (x, y, z), rotation quaternion
//!   (x, y, z, w), linear velocity (x, y, z), angular velocity (x, y, z).
//! - **ship record**, 15 doubles: the id, the motion, then 1 if asleep or 0.
//! - **block**, 4 ints: x, y, z on the ship's grid, then its shape:
//!   [`WE_PHYSICS_SHAPE_FULL`] (−1) for a full cube, 0 for no collision, or
//!   k ≥ 1 for the next k boxes of the box array.
//! - **box**, 6 doubles: min (x, y, z), then max (x, y, z).
//! - **force**, 6 doubles: force (x, y, z) in newtons, then the point it acts
//!   at (x, y, z), both in the ship's frame.
//! - **mass**, 13 doubles: the mass, the centre of mass (x, y, z) in the
//!   ship's frame, then the inertia tensor about it, row by row.
//!
//! A `WePhysics` is **not** thread-safe: calls on one handle must not
//! overlap, though they may come from different threads in turn.

use crate::{WE_ERR_BAD_INDEX, WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use std::panic::{AssertUnwindSafe, catch_unwind};
use we_physics::{
    BlockShape, Cuboid, MAX_BLOCK_BOXES, MAX_SHIP_BLOCKS, MAX_SHIP_FORCES, PhysicsError,
    SECTION_BLOCKS, SECTION_WORDS, SectionPos, ShipBlock, ShipForce, ShipMotion, World,
};

/// A block's shape code for a full cube.
pub const WE_PHYSICS_SHAPE_FULL: i32 = -1;
/// Ship `id` already exists.
pub const WE_ERR_EXISTS: i32 = -4;
/// Doubles in a motion.
pub const WE_PHYSICS_MOTION_LEN: usize = 13;
/// Doubles in a ship record.
pub const WE_PHYSICS_SHIP_LEN: usize = 15;
/// Doubles in a ship's mass properties.
pub const WE_PHYSICS_MASS_LEN: usize = 13;

/// Opaque handle owned by the Java side.
pub struct WePhysics(World);

fn status(result: Result<(), PhysicsError>) -> i32 {
    match result {
        Ok(()) => WE_OK,
        Err(PhysicsError::Invalid(_)) => WE_ERR_INVALID,
        Err(PhysicsError::UnknownShip(_)) => WE_ERR_BAD_INDEX,
        Err(PhysicsError::DuplicateShip(_)) => WE_ERR_EXISTS,
    }
}

/// Reads `n` values from `ptr`, which needn't be aligned.
///
/// # Safety
/// `ptr` must be valid for reading `n` values of `T` (it may be dangling if `n` is 0).
unsafe fn read<T: Copy>(ptr: *const T, n: usize) -> Vec<T> {
    (0..n)
        .map(|i| unsafe { ptr.add(i).read_unaligned() })
        .collect()
}

/// # Safety
/// `ptr` must be valid for writing `values.len()` doubles; it needn't be aligned.
unsafe fn write(ptr: *mut f64, values: &[f64]) {
    for (i, v) in values.iter().enumerate() {
        unsafe { ptr.add(i).write_unaligned(*v) };
    }
}

fn motion_from(v: &[f64]) -> ShipMotion {
    ShipMotion {
        position: [v[0], v[1], v[2]],
        rotation: [v[3], v[4], v[5], v[6]],
        linvel: [v[7], v[8], v[9]],
        angvel: [v[10], v[11], v[12]],
    }
}

fn cuboids(v: &[f64]) -> Vec<Cuboid> {
    v.chunks_exact(6)
        .map(|b| Cuboid::new([b[0], b[1], b[2]], [b[3], b[4], b[5]]))
        .collect()
}

/// A new, empty physics world, or null on failure. Free it with [`we_physics_free`].
#[unsafe(no_mangle)]
pub extern "C" fn we_physics_new() -> *mut WePhysics {
    catch_unwind(|| Box::into_raw(Box::new(WePhysics(World::new()))))
        .unwrap_or(std::ptr::null_mut())
}

/// # Safety
/// `world` must be null or a live pointer from [`we_physics_new`], and must not be used after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_free(world: *mut WePhysics) {
    if !world.is_null() {
        // A panic while dropping must not unwind into the JVM.
        let _ = catch_unwind(AssertUnwindSafe(|| drop(unsafe { Box::from_raw(world) })));
    }
}

/// Sets terrain section (`sx`, `sy`, `sz`): `full` holds its 64-word mask of
/// full-cube blocks (`we_physics::World::set_section`), `boxes` its other
/// blocks' `box_count` boxes in the section's frame.
///
/// # Safety
/// `world` must be null or live; `full` must be null or valid for reading 64
/// `u64`s; `boxes` must be valid for reading `6 · box_count` doubles (or null
/// if `box_count` is 0). None needs to be aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_set_section(
    world: *mut WePhysics,
    sx: i32,
    sy: i32,
    sz: i32,
    full: *const u64,
    boxes: *const f64,
    box_count: u32,
) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    if full.is_null() || (boxes.is_null() && box_count > 0) {
        return WE_ERR_NULL;
    }
    let box_count = box_count as usize;
    if box_count > SECTION_BLOCKS * MAX_BLOCK_BOXES {
        return WE_ERR_INVALID;
    }
    guard(|| {
        let mask: [u64; SECTION_WORDS] = unsafe { read(full, SECTION_WORDS) }
            .try_into()
            .expect("64 words");
        let partial = cuboids(&unsafe { read(boxes, 6 * box_count) });
        status(world.0.set_section(
            SectionPos {
                x: sx,
                y: sy,
                z: sz,
            },
            &mask,
            &partial,
        ))
    })
}

/// Removes terrain section (`sx`, `sy`, `sz`); nothing happens if it wasn't set.
///
/// # Safety
/// `world` must be null or live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_clear_section(
    world: *mut WePhysics,
    sx: i32,
    sy: i32,
    sz: i32,
) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    guard(|| {
        world.0.clear_section(SectionPos {
            x: sx,
            y: sy,
            z: sz,
        });
        WE_OK
    })
}

/// Adds ship `id` made of `block_count` blocks (4 ints each) with their
/// `masses` (kg), whose boxes are the `box_count` boxes of `boxes`, taken in
/// block order. `motion` is its starting motion. Returns `WE_ERR_EXISTS` if
/// the id is taken and `WE_ERR_INVALID` for bad blocks
/// (`we_physics::World::add_ship`), or if the blocks' shapes don't use
/// exactly `box_count` boxes.
///
/// # Safety
/// `world` must be null or live; `motion` must be null or valid for reading
/// 13 doubles; `blocks`, `masses` and `boxes` must be valid for reading
/// `4 · block_count` ints, `block_count` doubles and `6 · box_count` doubles
/// (each may be null if its count is 0). None needs to be aligned.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn we_physics_add_ship(
    world: *mut WePhysics,
    id: u32,
    motion: *const f64,
    blocks: *const i32,
    masses: *const f64,
    block_count: u32,
    boxes: *const f64,
    box_count: u32,
) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    if motion.is_null()
        || ((blocks.is_null() || masses.is_null()) && block_count > 0)
        || (boxes.is_null() && box_count > 0)
    {
        return WE_ERR_NULL;
    }
    let (block_count, box_count) = (block_count as usize, box_count as usize);
    if block_count > MAX_SHIP_BLOCKS || box_count > MAX_SHIP_BLOCKS * MAX_BLOCK_BOXES {
        return WE_ERR_INVALID;
    }
    guard(|| {
        let motion = motion_from(&unsafe { read(motion, WE_PHYSICS_MOTION_LEN) });
        let codes = unsafe { read(blocks, 4 * block_count) };
        let masses = unsafe { read(masses, block_count) };
        let mut all_boxes = cuboids(&unsafe { read(boxes, 6 * box_count) }).into_iter();
        let mut ship = Vec::with_capacity(block_count);
        for (code, mass) in codes.chunks_exact(4).zip(masses) {
            let shape = match code[3] {
                WE_PHYSICS_SHAPE_FULL => BlockShape::Full,
                0 => BlockShape::Empty,
                k if k > 0 && k as usize <= MAX_BLOCK_BOXES => {
                    let own: Vec<Cuboid> = all_boxes.by_ref().take(k as usize).collect();
                    if own.len() != k as usize {
                        return WE_ERR_INVALID;
                    }
                    BlockShape::Boxes(own)
                }
                _ => return WE_ERR_INVALID,
            };
            ship.push(ShipBlock {
                pos: [code[0], code[1], code[2]],
                mass,
                shape,
            });
        }
        if all_boxes.next().is_some() {
            return WE_ERR_INVALID;
        }
        status(world.0.add_ship(id, &motion, &ship))
    })
}

/// Removes ship `id`; `WE_ERR_BAD_INDEX` if there is none.
///
/// # Safety
/// `world` must be null or live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_remove_ship(world: *mut WePhysics, id: u32) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    guard(|| status(world.0.remove_ship(id)))
}

/// Moves ship `id` to `motion` (13 doubles) at once, and wakes it.
///
/// # Safety
/// `world` must be null or live; `motion` must be null or valid for reading 13 doubles.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_set_ship_motion(
    world: *mut WePhysics,
    id: u32,
    motion: *const f64,
) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    if motion.is_null() {
        return WE_ERR_NULL;
    }
    guard(|| {
        let motion = motion_from(&unsafe { read(motion, WE_PHYSICS_MOTION_LEN) });
        status(world.0.set_ship_motion(id, &motion))
    })
}

/// Ship `id`'s gravity in m/s² along −Y.
///
/// # Safety
/// `world` must be null or live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_set_ship_gravity(
    world: *mut WePhysics,
    id: u32,
    gravity: f64,
) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    guard(|| status(world.0.set_ship_gravity(id, gravity)))
}

/// The `force_count` forces (6 doubles each) and the torque (3 doubles, N·m)
/// acting on ship `id`, all in its frame, until set again.
///
/// # Safety
/// `world` must be null or live; `forces` must be valid for reading
/// `6 · force_count` doubles (or null if `force_count` is 0); `torque` must
/// be null or valid for reading 3 doubles.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_set_ship_controls(
    world: *mut WePhysics,
    id: u32,
    forces: *const f64,
    force_count: u32,
    torque: *const f64,
) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    if torque.is_null() || (forces.is_null() && force_count > 0) {
        return WE_ERR_NULL;
    }
    let force_count = force_count as usize;
    if force_count > MAX_SHIP_FORCES {
        return WE_ERR_INVALID;
    }
    guard(|| {
        let forces: Vec<ShipForce> = unsafe { read(forces, 6 * force_count) }
            .chunks_exact(6)
            .map(|f| ShipForce {
                force: [f[0], f[1], f[2]],
                point: [f[3], f[4], f[5]],
            })
            .collect();
        let t = unsafe { read(torque, 3) };
        status(world.0.set_ship_controls(id, &forces, [t[0], t[1], t[2]]))
    })
}

/// Advances `steps` steps of 1/60 s (at most `we_physics::MAX_STEPS`).
///
/// # Safety
/// `world` must be null or live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_step(world: *mut WePhysics, steps: u32) -> i32 {
    let Some(world) = (unsafe { world.as_mut() }) else {
        return WE_ERR_NULL;
    };
    guard(|| status(world.0.step(steps)))
}

/// The number of ships, or `WE_ERR_NULL`.
///
/// # Safety
/// `world` must be null or live.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_ship_count(world: *const WePhysics) -> i32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return WE_ERR_NULL;
    };
    i32::try_from(world.0.ship_count()).unwrap_or(i32::MAX)
}

/// Writes the records (15 doubles each) of up to `capacity` ships, by id, to
/// `out`, and returns the number of ships there are.
///
/// # Safety
/// `world` must be null or live; `out` must be valid for writing
/// `15 · capacity` doubles (or null if `capacity` is 0). It needn't be aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_read_ships(
    world: *const WePhysics,
    out: *mut f64,
    capacity: u32,
) -> i32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return WE_ERR_NULL;
    };
    if out.is_null() && capacity > 0 {
        return WE_ERR_NULL;
    }
    guard(|| {
        let ships = world.0.ships();
        for (i, s) in ships.iter().take(capacity as usize).enumerate() {
            let m = &s.motion;
            let mut record = [0.0; WE_PHYSICS_SHIP_LEN];
            record[0] = f64::from(s.id);
            record[1..4].copy_from_slice(&m.position);
            record[4..8].copy_from_slice(&m.rotation);
            record[8..11].copy_from_slice(&m.linvel);
            record[11..14].copy_from_slice(&m.angvel);
            record[14] = if s.sleeping { 1.0 } else { 0.0 };
            unsafe { write(out.add(i * WE_PHYSICS_SHIP_LEN), &record) };
        }
        i32::try_from(ships.len()).unwrap_or(i32::MAX)
    })
}

/// Writes ship `id`'s mass properties (13 doubles) to `out`.
///
/// # Safety
/// `world` must be null or live; `out` must be null or valid for writing 13 doubles. It needn't be aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_physics_ship_mass(
    world: *const WePhysics,
    id: u32,
    out: *mut f64,
) -> i32 {
    let Some(world) = (unsafe { world.as_ref() }) else {
        return WE_ERR_NULL;
    };
    if out.is_null() {
        return WE_ERR_NULL;
    }
    guard(|| {
        let Some(m) = world.0.ship_mass(id) else {
            return WE_ERR_BAD_INDEX;
        };
        let mut record = [0.0; WE_PHYSICS_MASS_LEN];
        record[0] = m.mass;
        record[1..4].copy_from_slice(&m.center);
        for (i, row) in m.inertia.iter().enumerate() {
            record[4 + 3 * i..7 + 3 * i].copy_from_slice(row);
        }
        unsafe { write(out, &record) };
        WE_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Handle(*mut WePhysics);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe { we_physics_free(self.0) }
        }
    }

    fn world() -> Handle {
        let h = we_physics_new();
        assert!(!h.is_null());
        Handle(h)
    }

    const AT_REST: [f64; 13] = [
        0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
    ];

    fn add_cube(w: &Handle, id: u32, motion: &[f64; 13]) -> i32 {
        let blocks = [0, 0, 0, WE_PHYSICS_SHAPE_FULL];
        unsafe {
            we_physics_add_ship(
                w.0,
                id,
                motion.as_ptr(),
                blocks.as_ptr(),
                [1000.0].as_ptr(),
                1,
                std::ptr::null(),
                0,
            )
        }
    }

    fn records(w: &Handle) -> Vec<[f64; 15]> {
        let n = unsafe { we_physics_ship_count(w.0) } as usize;
        let mut out = vec![0.0; n * 15];
        assert_eq!(
            unsafe { we_physics_read_ships(w.0, out.as_mut_ptr(), n as u32) },
            n as i32
        );
        out.chunks_exact(15)
            .map(|r| r.try_into().unwrap())
            .collect()
    }

    #[test]
    fn a_ship_falls_onto_a_section_and_rests() {
        let w = world();
        let full = [u64::MAX; 64];
        unsafe {
            assert_eq!(
                we_physics_set_section(w.0, 0, -1, 0, full.as_ptr(), std::ptr::null(), 0),
                WE_OK
            );
        }
        let mut motion = AT_REST;
        motion[0] = 4.0;
        motion[2] = 4.0;
        assert_eq!(add_cube(&w, 3, &motion), WE_OK);
        for _ in 0..4 {
            assert_eq!(unsafe { we_physics_step(w.0, 300) }, WE_OK);
        }
        let r = records(&w)[0];
        assert_eq!(r[0], 3.0);
        assert!(r[2].abs() < 1e-3, "{r:?}");
        assert_eq!(r[14], 1.0, "asleep");
    }

    #[test]
    fn boxes_are_handed_out_in_block_order() {
        // Two slabs and a full block between them: the slabs take one box each.
        let w = world();
        let blocks = [0, 0, 0, 1, 1, 0, 0, WE_PHYSICS_SHAPE_FULL, 2, 0, 0, 1];
        let masses = [1.0, 2.0, 1.0];
        let boxes = [0.0, 0.0, 0.0, 1.0, 0.5, 1.0, 0.0, 0.0, 0.0, 1.0, 0.5, 1.0];
        let status = unsafe {
            we_physics_add_ship(
                w.0,
                1,
                AT_REST.as_ptr(),
                blocks.as_ptr(),
                masses.as_ptr(),
                3,
                boxes.as_ptr(),
                2,
            )
        };
        assert_eq!(status, WE_OK);
        let mut mass = [0.0; 13];
        assert_eq!(
            unsafe { we_physics_ship_mass(w.0, 1, mass.as_mut_ptr()) },
            WE_OK
        );
        assert!((mass[0] - 4.0).abs() < 1e-12);
        assert!((mass[1] - 1.5).abs() < 1e-12, "{mass:?}");
        assert!(
            (mass[2] - (2.0 * 0.5 + 2.0 * 0.25) / 4.0).abs() < 1e-12,
            "{mass:?}"
        );
        // One box too few or too many is refused.
        for count in [1, 3] {
            let extra = [boxes.as_slice(), &boxes[..6]].concat();
            let status = unsafe {
                we_physics_add_ship(
                    w.0,
                    2,
                    AT_REST.as_ptr(),
                    blocks.as_ptr(),
                    masses.as_ptr(),
                    3,
                    extra.as_ptr(),
                    count,
                )
            };
            assert_eq!(status, WE_ERR_INVALID, "{count} boxes");
        }
    }

    #[test]
    fn controls_and_motion_reach_the_ship() {
        let w = world();
        assert_eq!(add_cube(&w, 1, &AT_REST), WE_OK);
        let force = [0.0, 0.0, 1000.0, 0.5, 0.5, 0.5];
        unsafe {
            assert_eq!(we_physics_set_ship_gravity(w.0, 1, 0.0), WE_OK);
            assert_eq!(
                we_physics_set_ship_controls(w.0, 1, force.as_ptr(), 1, [0.0; 3].as_ptr()),
                WE_OK
            );
            assert_eq!(we_physics_step(w.0, 60), WE_OK);
        }
        let r = records(&w)[0];
        assert!((r[10] - 1.0).abs() < 1e-9, "1 m/s² for 1 s along z: {r:?}");
        let mut moved = AT_REST;
        moved[0] = 99.0;
        unsafe { assert_eq!(we_physics_set_ship_motion(w.0, 1, moved.as_ptr()), WE_OK) };
        assert_eq!(records(&w)[0][1], 99.0);
        unsafe {
            assert_eq!(we_physics_remove_ship(w.0, 1), WE_OK);
            assert_eq!(we_physics_ship_count(w.0), 0);
            assert_eq!(we_physics_clear_section(w.0, 0, 0, 0), WE_OK);
        }
    }

    #[test]
    fn read_ships_reports_all_but_writes_only_capacity() {
        let w = world();
        for id in [4, 2] {
            assert_eq!(add_cube(&w, id, &AT_REST), WE_OK);
        }
        let mut one = [f64::NAN; 15];
        assert_eq!(
            unsafe { we_physics_read_ships(w.0, one.as_mut_ptr(), 1) },
            2
        );
        assert_eq!(one[0], 2.0, "by id");
        assert_eq!(
            unsafe { we_physics_read_ships(w.0, std::ptr::null_mut(), 0) },
            2
        );
    }

    #[test]
    fn errors_have_their_codes() {
        let w = world();
        assert_eq!(add_cube(&w, 1, &AT_REST), WE_OK);
        assert_eq!(add_cube(&w, 1, &AT_REST), WE_ERR_EXISTS);
        let mut bad = AT_REST;
        bad[6] = 0.0;
        assert_eq!(add_cube(&w, 2, &bad), WE_ERR_INVALID);
        let mut out = [0.0; 13];
        unsafe {
            assert_eq!(we_physics_remove_ship(w.0, 9), WE_ERR_BAD_INDEX);
            assert_eq!(
                we_physics_ship_mass(w.0, 9, out.as_mut_ptr()),
                WE_ERR_BAD_INDEX
            );
            assert_eq!(we_physics_step(w.0, 601), WE_ERR_INVALID);
            assert_eq!(we_physics_step(std::ptr::null_mut(), 1), WE_ERR_NULL);
            assert_eq!(we_physics_ship_count(std::ptr::null()), WE_ERR_NULL);
            assert_eq!(
                we_physics_set_section(w.0, 0, 0, 0, std::ptr::null(), std::ptr::null(), 0),
                WE_ERR_NULL
            );
            assert_eq!(
                we_physics_set_section(w.0, 0, 0, 0, [0u64; 64].as_ptr(), std::ptr::null(), 1),
                WE_ERR_NULL
            );
            let blocks = [0, 0, 0, 17];
            let status = we_physics_add_ship(
                w.0,
                3,
                AT_REST.as_ptr(),
                blocks.as_ptr(),
                [1.0].as_ptr(),
                1,
                std::ptr::null(),
                0,
            );
            assert_eq!(
                status, WE_ERR_INVALID,
                "17 boxes is more than a block may have"
            );
            assert_eq!(
                we_physics_set_ship_controls(w.0, 1, std::ptr::null(), 0, std::ptr::null()),
                WE_ERR_NULL
            );
            we_physics_free(std::ptr::null_mut());
        }
    }
}
```

- [ ] **Step 3: Wire them in, ABI v6.** In `core/crates/we-ffi/src/lib.rs`:
  - in the module docs, after the paragraph that ends "any number of threads may call them at once.", add:

```rust
//!
//! A `WePhysics` (see `physics.rs`) is not thread-safe either: calls on one
//! handle must not overlap, though they may come from different threads in turn.
```

  - add `mod physics;` before `mod seams;`, and before `pub use seams::…` add:

```rust
pub use physics::{
    WE_ERR_EXISTS, WE_PHYSICS_MASS_LEN, WE_PHYSICS_MOTION_LEN, WE_PHYSICS_SHAPE_FULL,
    WE_PHYSICS_SHIP_LEN, WePhysics, we_physics_add_ship, we_physics_clear_section, we_physics_free,
    we_physics_new, we_physics_read_ships, we_physics_remove_ship, we_physics_set_section,
    we_physics_set_ship_controls, we_physics_set_ship_gravity, we_physics_set_ship_motion,
    we_physics_ship_count, we_physics_ship_mass, we_physics_step,
};
```

  - `pub const WE_ABI_VERSION: u32 = 5;` becomes `6`, and so does the `5` in the `reports_abi_version` test.

- [ ] **Step 4: The call sequence Java will use.** Append to `core/crates/we-ffi/tests/abi.rs`:

```rust
#[test]
fn physics_call_sequence() {
    // The physics thread's order: a world, terrain, a ship, its controls, steps, then the records.
    let world = we_physics_new();
    assert!(!world.is_null());
    unsafe {
        let full = [u64::MAX; 64];
        assert_eq!(
            we_physics_set_section(world, 0, -1, 0, full.as_ptr(), std::ptr::null(), 0),
            WE_OK
        );
        let motion = [
            2.0, 3.0, 2.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ];
        let blocks = [0, 0, 0, WE_PHYSICS_SHAPE_FULL, 1, 0, 0, 1];
        let slab = [0.0, 0.0, 0.0, 1.0, 0.5, 1.0];
        assert_eq!(
            we_physics_add_ship(
                world,
                1,
                motion.as_ptr(),
                blocks.as_ptr(),
                [2500.0, 1250.0].as_ptr(),
                2,
                slab.as_ptr(),
                1
            ),
            WE_OK
        );
        assert_eq!(we_physics_set_ship_gravity(world, 1, 9.81), WE_OK);
        assert_eq!(
            we_physics_set_ship_controls(world, 1, std::ptr::null(), 0, [0.0; 3].as_ptr()),
            WE_OK
        );
        for _ in 0..200 {
            assert_eq!(we_physics_step(world, 3), WE_OK);
        }
        assert_eq!(we_physics_ship_count(world), 1);
        let mut records = [0.0f64; WE_PHYSICS_SHIP_LEN];
        assert_eq!(we_physics_read_ships(world, records.as_mut_ptr(), 1), 1);
        assert_eq!(records[0], 1.0);
        assert!(records[2].abs() < 1e-3, "rests on the floor: {records:?}");
        let mut mass = [0.0f64; WE_PHYSICS_MASS_LEN];
        assert_eq!(we_physics_ship_mass(world, 1, mass.as_mut_ptr()), WE_OK);
        assert_eq!(mass[0], 3750.0);
        assert_eq!(we_physics_remove_ship(world, 1), WE_OK);
        assert_eq!(we_physics_clear_section(world, 0, -1, 0), WE_OK);
        we_physics_free(world);
    }
}
```

- [ ] **Step 5: Run the whole workspace's checks.**

Run: `cd core && cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace`
Expected: all pass, including 5 new `physics::tests` and `physics_call_sequence`.

- [ ] **Step 6: Commit.**

```bash
git add core/crates/we-ffi
git commit -m "M2a: we_physics_* through the C ABI (v6)"
```

---

### Task 4: The Java bridge

The records mirror the native layouts, and the two builders grow plain arrays. Every call allocates its buffers in a confined arena, as the bridge's other calls do. An empty array goes over as NULL, which the core accepts with a count of 0. Ship ids are Java `int`s, passed as the core's `u32`.

**Files:**
- Create: `mod/src/main/java/…/nativebridge/{ShipMotion,ShipState,ShipForce,ShipMass,ShipBlocks,TerrainSection}.java`, `mod/src/test/java/…/nativebridge/NativePhysicsTest.java`
- Modify: `mod/src/main/java/…/nativebridge/NativeCore.java`

- [ ] **Step 1: The test.** Create `mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativePhysicsTest.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The physics world through the bridge (we-physics, M2a). */
class NativePhysicsTest {
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    /** A section whose 16x16x16 blocks are all full cubes. */
    private static TerrainSection solid() {
        TerrainSection section = new TerrainSection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.full(x, y, z);
                }
            }
        }
        return section;
    }

    private static ShipBlocks rod() {
        return new ShipBlocks().full(0, 0, 0, 1000).full(1, 0, 0, 1000).full(2, 0, 0, 1000);
    }

    @Test
    void aShipFallsOntoTheTerrainAndSleeps() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            world.setSection(0, -1, 0, solid()); // its top is at y = 0
            world.addShip(1, ShipMotion.at(4, 3, 4), new ShipBlocks().full(0, 0, 0, 2500).full(1, 0, 0, 2500));
            for (int i = 0; i < 20; i++) {
                world.step(60);
            }
            ShipState s = world.ships().get(0);
            assertEquals(1, s.id());
            assertEquals(0, s.motion().y(), 1e-3);
            assertTrue(s.sleeping());
        }
    }

    @Test
    void massPropertiesComeFromTheBlocks() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            world.addShip(7, ShipMotion.at(0, 50, 0), rod());
            ShipMass m = world.shipMass(7);
            assertEquals(3000, m.mass(), 1e-9);
            assertEquals(1.5, m.cx(), 1e-12);
            assertEquals(0.5, m.cy(), 1e-12);
            assertEquals(500, m.inertia()[0], 1e-6); // about x
            assertEquals(2500, m.inertia()[4], 1e-6); // about y
            assertEquals(0, m.inertia()[1], 1e-6);
        }
    }

    @Test
    void anOffCentreForceSpinsTheShip() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            world.addShip(1, ShipMotion.at(0, 0, 0), rod());
            world.setShipGravity(1, 0);
            world.setShipControls(1, List.of(new ShipForce(0, 1000, 0, 3, 0.5, 0.5)), 0, 0, 0);
            world.step(1);
            ShipMotion m = world.ships().get(0).motion();
            assertEquals(0.01, m.wz(), 1e-9); // 1,000 N x 1.5 m / 2,500 kg m² / 60 s
            assertEquals(1000.0 / 3000 / 60, m.vy(), 1e-9);
        }
    }

    @Test
    void slabsCollideAsTheirBoxes() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            // A terrain section that is only a slab floor, and a top slab dropped on it.
            world.setSection(0, 0, 0, new TerrainSection().box(0, 0, 0, 16, 0.5, 16));
            world.addShip(1, ShipMotion.at(6, 3, 6), new ShipBlocks().boxes(0, 0, 0, 1250, 0, 0.5, 0, 1, 1, 1));
            for (int i = 0; i < 10; i++) {
                world.step(60);
            }
            assertEquals(0.0, world.ships().get(0).motion().y(), 1e-3, "the top slab's underside lands at 0.5");
        }
    }

    @Test
    void shipsAreListedByIdAndCanBeMovedAndRemoved() {
        try (NativeCore.PhysicsWorld world = core.newPhysicsWorld()) {
            for (int id : new int[] {9, 2, 5}) {
                world.addShip(id, ShipMotion.at(10 * id, 50, 0), new ShipBlocks().full(0, 0, 0, 1));
            }
            assertEquals(List.of(2, 5, 9), world.ships().stream().map(ShipState::id).toList());
            world.setShipMotion(5, new ShipMotion(1, 2, 3, 0, 1, 0, 0, 4, 0, 0, 0, 0, 0));
            ShipMotion m = world.ships().get(1).motion();
            assertEquals(1, m.x());
            assertEquals(1, m.qy());
            assertEquals(4, m.vx());
            world.removeShip(5);
            assertEquals(2, world.ships().size());
            world.clearSection(0, 0, 0);
        }
    }

    @Test
    void badCallsThrow() {
        NativeCore.PhysicsWorld world = core.newPhysicsWorld();
        world.addShip(1, ShipMotion.at(0, 0, 0), new ShipBlocks().full(0, 0, 0, 1));
        NativeCoreException e = assertThrows(NativeCoreException.class,
                () -> world.addShip(1, ShipMotion.at(0, 0, 0), new ShipBlocks().full(0, 0, 0, 1)));
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        e = assertThrows(NativeCoreException.class, () -> world.removeShip(2));
        assertTrue(e.getMessage().contains("no ship 2"), e.getMessage());
        assertThrows(NativeCoreException.class, () -> world.addShip(3, ShipMotion.at(0, 0, 0), new ShipBlocks()));
        assertThrows(NativeCoreException.class,
                () -> world.addShip(3, ShipMotion.at(0, 0, 0), new ShipBlocks().full(0, 0, 0, -1)));
        assertThrows(NativeCoreException.class, () -> world.step(601));
        assertThrows(IllegalArgumentException.class, () -> new ShipBlocks().boxes(0, 0, 0, 1, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TerrainSection().full(16, 0, 0));
        world.close();
        world.close();
        assertThrows(IllegalStateException.class, () -> world.step(1));
    }
}
```

- [ ] **Step 2: The records.** Create these four files in `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/`:

`ShipMotion.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

/**
 * Where a ship is and how it moves: the position of its frame's origin ({@code x}, {@code y}, {@code z}, world
 * coordinates), the unit quaternion ({@code qx}, {@code qy}, {@code qz}, {@code qw}) that turns its frame into the
 * world's, the velocity of its centre of mass ({@code vx}, {@code vy}, {@code vz}, m/s), and its angular velocity
 * ({@code wx}, {@code wy}, {@code wz}, rad/s about the world axes).
 */
public record ShipMotion(
        double x, double y, double z,
        double qx, double qy, double qz, double qw,
        double vx, double vy, double vz,
        double wx, double wy, double wz) {
    /** Doubles in the native layout. */
    static final int LENGTH = 13;

    /** At rest at ({@code x}, {@code y}, {@code z}), unrotated. */
    public static ShipMotion at(double x, double y, double z) {
        return new ShipMotion(x, y, z, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
    }

    double[] toArray() {
        return new double[] {x, y, z, qx, qy, qz, qw, vx, vy, vz, wx, wy, wz};
    }

    static ShipMotion fromArray(double[] a, int offset) {
        return new ShipMotion(a[offset], a[offset + 1], a[offset + 2], a[offset + 3], a[offset + 4], a[offset + 5],
                a[offset + 6], a[offset + 7], a[offset + 8], a[offset + 9], a[offset + 10], a[offset + 11],
                a[offset + 12]);
    }
}
```

`ShipState.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

/** Ship {@code id}'s motion after a step, and whether it is asleep (at rest, costing almost nothing). */
public record ShipState(int id, ShipMotion motion, boolean sleeping) {
}
```

`ShipForce.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

/**
 * A force of ({@code fx}, {@code fy}, {@code fz}) newtons at point ({@code px}, {@code py}, {@code pz}), both in the
 * ship's frame, so they turn with the ship: a thruster's push.
 */
public record ShipForce(double fx, double fy, double fz, double px, double py, double pz) {
}
```

`ShipMass.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

/**
 * A ship's {@code mass} (kg), its centre of mass ({@code cx}, {@code cy}, {@code cz}) in its own frame, and its
 * {@code inertia} tensor about that centre (kg m², 3x3, row by row). It holds an array, so compare it with
 * {@code Arrays.equals}.
 */
public record ShipMass(double mass, double cx, double cy, double cz, double[] inertia) {
    public ShipMass {
        if (inertia.length != 9) {
            throw new IllegalArgumentException("inertia must have 9 numbers");
        }
    }
}
```

- [ ] **Step 3: The builders.** In the same package, `ShipBlocks.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

import java.util.Arrays;

/**
 * The blocks of a ship, for {@link NativeCore.PhysicsWorld#addShip}: where each sits on the ship's grid (block
 * (x, y, z) fills [x, x + 1) × [y, y + 1) × [z, z + 1) of the ship's frame), its mass in kilograms, and how it
 * collides. Positions must be distinct and masses positive.
 */
public final class ShipBlocks {
    /** Most blocks a ship may have; keep equal to {@code we_physics::MAX_SHIP_BLOCKS}. */
    public static final int MAX_BLOCKS = 32_768;
    /** Most boxes one block may have; keep equal to {@code we_physics::MAX_BLOCK_BOXES}. */
    public static final int MAX_BOXES_PER_BLOCK = 16;
    /** {@code WE_PHYSICS_SHAPE_FULL}. */
    private static final int FULL = -1;

    private int[] blocks = new int[4 * 16];
    private double[] masses = new double[16];
    private double[] boxes = new double[6 * 16];
    private int count;
    private int boxCount;

    /** A full cube at ({@code x}, {@code y}, {@code z}). */
    public ShipBlocks full(int x, int y, int z, double mass) {
        return add(x, y, z, mass, FULL);
    }

    /**
     * A block at ({@code x}, {@code y}, {@code z}) that collides as the given boxes, six numbers each (min x, y, z,
     * then max x, y, z) in the block's frame: 0 to 1, or up to 1 beyond for fences and walls. Its mass is spread
     * over the boxes.
     */
    public ShipBlocks boxes(int x, int y, int z, double mass, double... minMax) {
        int n = minMax.length / 6;
        if (minMax.length % 6 != 0 || n < 1 || n > MAX_BOXES_PER_BLOCK) {
            throw new IllegalArgumentException("A block has 1 to " + MAX_BOXES_PER_BLOCK + " boxes of 6 numbers");
        }
        if (6 * (boxCount + n) > boxes.length) {
            boxes = Arrays.copyOf(boxes, Math.max(2 * boxes.length, 6 * (boxCount + n)));
        }
        System.arraycopy(minMax, 0, boxes, 6 * boxCount, minMax.length);
        boxCount += n;
        return add(x, y, z, mass, n);
    }

    /** A block at ({@code x}, {@code y}, {@code z}) that doesn't collide (a torch, a flower); its mass still counts. */
    public ShipBlocks empty(int x, int y, int z, double mass) {
        return add(x, y, z, mass, 0);
    }

    private ShipBlocks add(int x, int y, int z, double mass, int shape) {
        if (count == MAX_BLOCKS) {
            throw new IllegalStateException("A ship has at most " + MAX_BLOCKS + " blocks");
        }
        if (4 * (count + 1) > blocks.length) {
            blocks = Arrays.copyOf(blocks, 2 * blocks.length);
            masses = Arrays.copyOf(masses, 2 * masses.length);
        }
        blocks[4 * count] = x;
        blocks[4 * count + 1] = y;
        blocks[4 * count + 2] = z;
        blocks[4 * count + 3] = shape;
        masses[count] = mass;
        count++;
        return this;
    }

    public int size() {
        return count;
    }

    int boxCount() {
        return boxCount;
    }

    /** Four ints per block: x, y, z, and -1 (full), 0 (no collision) or its number of boxes. */
    int[] blockArray() {
        return Arrays.copyOf(blocks, 4 * count);
    }

    double[] massArray() {
        return Arrays.copyOf(masses, count);
    }

    double[] boxArray() {
        return Arrays.copyOf(boxes, 6 * boxCount);
    }
}
```

and `TerrainSection.java`:

```java
package io.github.chabelabela.worldengine.nativebridge;

import java.util.Arrays;

/**
 * One 16x16x16 terrain section, for {@link NativeCore.PhysicsWorld#setSection}: which of its blocks are full cubes,
 * and the boxes of the others that collide (slabs, stairs, fences).
 */
public final class TerrainSection {
    private final long[] full = new long[64];
    private double[] boxes = new double[0];
    private int boxCount;

    /** Marks block ({@code x}, {@code y}, {@code z}) of the section, each 0-15, as a full cube. */
    public TerrainSection full(int x, int y, int z) {
        if ((x | y | z) >>> 4 != 0) {
            throw new IllegalArgumentException("(" + x + ", " + y + ", " + z + ") is outside a section");
        }
        int i = (y << 8) | (z << 4) | x; // Minecraft's section order
        full[i >>> 6] |= 1L << (i & 63);
        return this;
    }

    /** Adds one box in the section's frame: 0 to 16 on every axis, or up to 1 beyond for fences and walls. */
    public TerrainSection box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (6 * (boxCount + 1) > boxes.length) {
            boxes = Arrays.copyOf(boxes, Math.max(48, 2 * boxes.length));
        }
        double[] b = {minX, minY, minZ, maxX, maxY, maxZ};
        System.arraycopy(b, 0, boxes, 6 * boxCount, 6);
        boxCount++;
        return this;
    }

    /** True if nothing in the section collides. */
    public boolean isEmpty() {
        return boxCount == 0 && Arrays.stream(full).allMatch(w -> w == 0);
    }

    long[] fullMask() {
        return full.clone();
    }

    int boxCount() {
        return boxCount;
    }

    double[] boxArray() {
        return Arrays.copyOf(boxes, 6 * boxCount);
    }
}
```

- [ ] **Step 4: `NativeCore`.** In `mod/src/main/java/io/github/chabelabela/worldengine/nativebridge/NativeCore.java`:
  - imports: `import java.util.ArrayList;` before `import java.util.Arrays;`, and `import java.util.List;` after it;
  - `EXPECTED_ABI_VERSION = 5` becomes `6`;
  - after the `SEAM_NOT_FOLDABLE` constant, add:

```java
    /** Doubles per ship record from {@code we_physics_read_ships}: the id, the motion, then 1 if asleep. */
    private static final int SHIP_RECORD = 15;
```

  - after `private final MethodHandle localSkyFn;`, add the fields:

```java
    private final MethodHandle physicsNewFn;
    private final MethodHandle physicsFreeFn;
    private final MethodHandle setSectionFn;
    private final MethodHandle clearSectionFn;
    private final MethodHandle addShipFn;
    private final MethodHandle removeShipFn;
    private final MethodHandle setShipMotionFn;
    private final MethodHandle setShipGravityFn;
    private final MethodHandle setShipControlsFn;
    private final MethodHandle stepFn;
    private final MethodHandle shipCountFn;
    private final MethodHandle readShipsFn;
    private final MethodHandle shipMassFn;
```

  - at the end of the constructor, after the `localSkyFn = …;` statement, look them up:

```java
        physicsNewFn = linker.downcallHandle(find(lib, "we_physics_new"), FunctionDescriptor.of(ADDRESS));
        physicsFreeFn = linker.downcallHandle(find(lib, "we_physics_free"), FunctionDescriptor.ofVoid(ADDRESS));
        setSectionFn = linker.downcallHandle(
                find(lib, "we_physics_set_section"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        clearSectionFn = linker.downcallHandle(
                find(lib, "we_physics_clear_section"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        addShipFn = linker.downcallHandle(
                find(lib, "we_physics_add_ship"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT));
        removeShipFn = linker.downcallHandle(
                find(lib, "we_physics_remove_ship"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        setShipMotionFn = linker.downcallHandle(
                find(lib, "we_physics_set_ship_motion"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        setShipGravityFn = linker.downcallHandle(
                find(lib, "we_physics_set_ship_gravity"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_DOUBLE));
        setShipControlsFn = linker.downcallHandle(
                find(lib, "we_physics_set_ship_controls"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        stepFn = linker.downcallHandle(
                find(lib, "we_physics_step"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        shipCountFn = linker.downcallHandle(
                find(lib, "we_physics_ship_count"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
        readShipsFn = linker.downcallHandle(
                find(lib, "we_physics_read_ships"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        shipMassFn = linker.downcallHandle(
                find(lib, "we_physics_ship_mass"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
```

  - after the `check` method, add two helpers:

```java
    /** Like {@link #check}, naming the statuses a physics call about ship {@code id} can return. */
    private static void checkShip(int status, String call, int id) {
        switch (status) {
            case -2 -> throw new NativeCoreException(call + ": no ship " + id);
            case -3 -> throw new NativeCoreException(call + ": invalid arguments for ship " + id);
            case -4 -> throw new NativeCoreException(call + ": ship " + id + " already exists");
            default -> check(status, call);
        }
    }

    /** A segment holding {@code values}, or NULL if there are none (the core accepts NULL for an empty array). */
    private static MemorySegment doubles(Arena arena, double[] values) {
        return values.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_DOUBLE, values);
    }
```

  - and before the class's closing brace, after the `Universe` class, add the world:

```java
    /** A new, empty physics world (we-physics): terrain sections and ships, stepped at 60 Hz. */
    public PhysicsWorld newPhysicsWorld() {
        MemorySegment handle;
        try {
            handle = (MemorySegment) physicsNewFn.invokeExact();
        } catch (Throwable t) {
            throw wrap(t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new NativeCoreException("we_physics_new returned null");
        }
        return new PhysicsWorld(handle);
    }

    /**
     * Owns one native physics world: terrain fed a 16x16x16 section at a time, and ships, each a rigid body made of
     * its blocks. Not thread-safe: calls must not overlap, though they may come from different threads in turn
     * ({@code physics.PhysicsThread} keeps it on one). It holds native memory, so always {@link #close()} it; there
     * is no cleaner.
     */
    public final class PhysicsWorld implements AutoCloseable {
        private MemorySegment handle;

        private PhysicsWorld(MemorySegment handle) {
            this.handle = handle;
        }

        private MemorySegment live() {
            if (handle == null) {
                throw new IllegalStateException("Physics world already closed");
            }
            return handle;
        }

        /**
         * Sets terrain section ({@code sx}, {@code sy}, {@code sz}), replacing what was there, and wakes ships near
         * it.
         */
        public void setSection(int sx, int sy, int sz, TerrainSection section) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment full = arena.allocateFrom(JAVA_LONG, section.fullMask());
                MemorySegment boxes = doubles(arena, section.boxArray());
                check((int) setSectionFn.invokeExact(h, sx, sy, sz, full, boxes, section.boxCount()),
                        "we_physics_set_section(" + sx + ", " + sy + ", " + sz + ")");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Removes terrain section ({@code sx}, {@code sy}, {@code sz}), if it was set. */
        public void clearSection(int sx, int sy, int sz) {
            MemorySegment h = live();
            try {
                check((int) clearSectionFn.invokeExact(h, sx, sy, sz), "we_physics_clear_section");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Adds ship {@code id}, made of {@code blocks}, moving as {@code motion} says. */
        public void addShip(int id, ShipMotion motion, ShipBlocks blocks) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = arena.allocateFrom(JAVA_DOUBLE, motion.toArray());
                int[] codes = blocks.blockArray();
                MemorySegment b = codes.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, codes);
                MemorySegment masses = doubles(arena, blocks.massArray());
                MemorySegment boxes = doubles(arena, blocks.boxArray());
                checkShip((int) addShipFn.invokeExact(h, id, m, b, masses, blocks.size(), boxes, blocks.boxCount()),
                        "we_physics_add_ship", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        public void removeShip(int id) {
            MemorySegment h = live();
            try {
                checkShip((int) removeShipFn.invokeExact(h, id), "we_physics_remove_ship", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Moves ship {@code id} to {@code motion} at once (a teleport), and wakes it. */
        public void setShipMotion(int id, ShipMotion motion) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment m = arena.allocateFrom(JAVA_DOUBLE, motion.toArray());
                checkShip((int) setShipMotionFn.invokeExact(h, id, m), "we_physics_set_ship_motion", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Ship {@code id}'s gravity, in m/s² along -Y; a new ship has 9.81. */
        public void setShipGravity(int id, double gravity) {
            MemorySegment h = live();
            try {
                checkShip((int) setShipGravityFn.invokeExact(h, id, gravity), "we_physics_set_ship_gravity", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /**
         * The forces and the torque ({@code tx}, {@code ty}, {@code tz}, N m), all in the ship's frame, that act on
         * ship {@code id} at every step until they are set again. No forces and no torque leave it coasting.
         */
        public void setShipControls(int id, List<ShipForce> forces, double tx, double ty, double tz) {
            MemorySegment h = live();
            double[] f = new double[6 * forces.size()];
            for (int i = 0; i < forces.size(); i++) {
                ShipForce force = forces.get(i);
                System.arraycopy(new double[] {force.fx(), force.fy(), force.fz(), force.px(), force.py(), force.pz()},
                        0, f, 6 * i, 6);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment torque = arena.allocateFrom(JAVA_DOUBLE, tx, ty, tz);
                checkShip((int) setShipControlsFn.invokeExact(h, id, doubles(arena, f), forces.size(), torque),
                        "we_physics_set_ship_controls", id);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Advances {@code steps} steps of 1/60 s each (at most 600). */
        public void step(int steps) {
            MemorySegment h = live();
            try {
                check((int) stepFn.invokeExact(h, steps), "we_physics_step(" + steps + ")");
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Every ship's state, by id. */
        public List<ShipState> ships() {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                int count = (int) shipCountFn.invokeExact(h);
                check(count, "we_physics_ship_count");
                if (count == 0) {
                    return List.of();
                }
                MemorySegment out = arena.allocate(JAVA_DOUBLE, (long) SHIP_RECORD * count);
                check((int) readShipsFn.invokeExact(h, out, count), "we_physics_read_ships");
                double[] r = out.toArray(JAVA_DOUBLE);
                List<ShipState> ships = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int o = SHIP_RECORD * i;
                    ships.add(new ShipState((int) (long) r[o], ShipMotion.fromArray(r, o + 1), r[o + 14] != 0));
                }
                return List.copyOf(ships);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }

        /** Ship {@code id}'s mass properties, summed from its blocks. */
        public ShipMass shipMass(int id) {
            MemorySegment h = live();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_DOUBLE, 13);
                checkShip((int) shipMassFn.invokeExact(h, id, out), "we_physics_ship_mass", id);
                double[] r = out.toArray(JAVA_DOUBLE);
                return new ShipMass(r[0], r[1], r[2], r[3], Arrays.copyOfRange(r, 4, 13));
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
                physicsFreeFn.invokeExact(h);
            } catch (Throwable t) {
                throw wrap(t);
            }
        }
    }
```

- [ ] **Step 5: Run the test.** Run: `cd mod && ./gradlew test --tests '*NativePhysicsTest'`. Expected: 6 passed.

- [ ] **Step 6: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/nativebridge mod/src/test/java/io/github/chabelabela/worldengine/nativebridge/NativePhysicsTest.java
git commit -m "M2a: the physics world through the Java bridge"
```

---

### Task 5: The physics thread

`PhysicsThread` owns a world on a single-thread executor named "World Engine physics". Every command and tick is queued in order, so the world is only ever used by that thread, and FFM's confined arenas stay on the thread that calls. `tick()` queues `STEPS_PER_TICK` (3) steps and then publishes the ships' states through a volatile field. `latest()` reads that field, so the server thread never waits. If the physics thread falls behind, ticks queue up; M2b measures whether that happens.

**Files:**
- Create: `mod/src/main/java/…/physics/PhysicsSnapshot.java`, `mod/src/main/java/…/physics/PhysicsThread.java`, `mod/src/test/java/…/physics/PhysicsThreadTest.java`

- [ ] **Step 1: The test.** Create `mod/src/test/java/io/github/chabelabela/worldengine/physics/PhysicsThreadTest.java`:

```java
package io.github.chabelabela.worldengine.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import io.github.chabelabela.worldengine.nativebridge.NativeCoreException;
import io.github.chabelabela.worldengine.nativebridge.ShipBlocks;
import io.github.chabelabela.worldengine.nativebridge.ShipMotion;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PhysicsThreadTest {
    private static NativeCore core;

    @BeforeAll
    static void loadCore() {
        String path = System.getProperty("worldengine.native.path");
        assertNotNull(path, "the Gradle test task must set worldengine.native.path");
        core = NativeCore.load(Path.of(path));
    }

    private static void addFallingBlock(PhysicsThread physics) {
        physics.submit(world -> world.addShip(1, ShipMotion.at(0, 100, 0), new ShipBlocks().full(0, 0, 0, 1)));
    }

    @Test
    void inlineTicksPublishAtOnce() throws InterruptedException {
        try (PhysicsThread physics = PhysicsThread.inline(core)) {
            assertEquals(PhysicsSnapshot.EMPTY, physics.latest());
            addFallingBlock(physics);
            for (int i = 0; i < 20; i++) {
                physics.tick();
            }
            PhysicsSnapshot s = physics.latest();
            assertEquals(20, s.tick());
            // One second of free fall: about 4.9 m.
            assertEquals(100 - 4.9, s.ship(1).motion().y(), 0.1);
            assertNull(s.ship(2));
        }
    }

    @Test
    void threadedTicksRunInOrderOnThePhysicsThread() throws InterruptedException {
        AtomicReference<String> thread = new AtomicReference<>();
        try (PhysicsThread physics = PhysicsThread.start(core)) {
            addFallingBlock(physics);
            physics.submit(world -> thread.set(Thread.currentThread().getName()));
            for (int i = 0; i < 20; i++) {
                physics.tick();
            }
            assertTrue(physics.awaitIdle(Duration.ofSeconds(10)));
            assertEquals(PhysicsThread.THREAD_NAME, thread.get());
            assertEquals(20, physics.latest().tick());
            assertEquals(100 - 4.9, physics.latest().ship(1).motion().y(), 0.1);
            assertNull(physics.failure());
        }
    }

    @Test
    void aFailedCommandStopsTheWorld() throws InterruptedException {
        try (PhysicsThread physics = PhysicsThread.inline(core)) {
            addFallingBlock(physics);
            physics.tick();
            physics.submit(world -> world.removeShip(42));
            assertInstanceOf(NativeCoreException.class, physics.failure());
            physics.tick();
            assertEquals(1, physics.latest().tick(), "ticks after the failure are skipped");
        }
    }

    @Test
    void aClosedThreadRefusesWork() throws InterruptedException {
        PhysicsThread physics = PhysicsThread.start(core);
        physics.close();
        physics.close();
        assertThrows(IllegalStateException.class, physics::tick);
    }
}
```

- [ ] **Step 2: The snapshot.** Create `mod/src/main/java/io/github/chabelabela/worldengine/physics/PhysicsSnapshot.java`:

```java
package io.github.chabelabela.worldengine.physics;

import io.github.chabelabela.worldengine.nativebridge.ShipState;
import java.util.List;

/** Every ship's state after physics tick {@code tick} (0 before the first), by id. */
public record PhysicsSnapshot(long tick, List<ShipState> ships) {
    public static final PhysicsSnapshot EMPTY = new PhysicsSnapshot(0, List.of());

    public PhysicsSnapshot {
        ships = List.copyOf(ships);
    }

    /** Ship {@code id}'s state, or null if there was no such ship. */
    public ShipState ship(int id) {
        for (ShipState s : ships) {
            if (s.id() == id) {
                return s;
            }
        }
        return null;
    }
}
```

- [ ] **Step 3: The thread.** Create `mod/src/main/java/io/github/chabelabela/worldengine/physics/PhysicsThread.java`:

```java
package io.github.chabelabela.worldengine.physics;

import io.github.chabelabela.worldengine.nativebridge.NativeCore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a native physics world on a thread of its own, so the server thread never waits on it.
 *
 * <p>The server thread queues commands ({@link #submit}) and, once per server tick, {@link #tick()}: three physics
 * steps of 1/60 s, after which the ships' states are published. It reads the last published states with
 * {@link #latest()}, which never blocks: usually the previous tick's. Commands and ticks run in the order they were
 * queued.
 *
 * <p>If a command or a tick throws, the world stops: later commands and ticks are skipped, {@link #latest()} keeps
 * the last good states, and {@link #failure()} returns what was thrown, so the host can report it instead of
 * crashing the server. {@link #inline} runs everything on the calling thread instead, for deterministic tests.
 */
public final class PhysicsThread implements AutoCloseable {
    /** Physics steps per 20 Hz server tick. */
    public static final int STEPS_PER_TICK = 3;
    public static final String THREAD_NAME = "World Engine physics";

    private final NativeCore.PhysicsWorld world;
    private final ExecutorService executor;
    private volatile PhysicsSnapshot latest = PhysicsSnapshot.EMPTY;
    private volatile Throwable failure;
    private long ticks; // only touched by queued tasks
    private boolean closed; // only touched by the owning thread

    private PhysicsThread(NativeCore.PhysicsWorld world, ExecutorService executor) {
        this.world = world;
        this.executor = executor;
    }

    /** A new, empty physics world on its own daemon thread. */
    public static PhysicsThread start(NativeCore core) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        return new PhysicsThread(core.newPhysicsWorld(), executor);
    }

    /** A new, empty physics world whose commands and ticks run at once, on the calling thread. */
    public static PhysicsThread inline(NativeCore core) {
        return new PhysicsThread(core.newPhysicsWorld(), null);
    }

    /** Queues {@code command} to run on the physics thread with the world. */
    public void submit(Consumer<NativeCore.PhysicsWorld> command) {
        queue(() -> command.accept(world), true);
    }

    /** Queues one server tick: {@link #STEPS_PER_TICK} steps, then publishes the ships' states. */
    public void tick() {
        queue(() -> {
            world.step(STEPS_PER_TICK);
            ticks++;
            latest = new PhysicsSnapshot(ticks, world.ships());
        }, true);
    }

    /** The ships' states after the last tick that has finished. Never blocks. */
    public PhysicsSnapshot latest() {
        return latest;
    }

    /** What stopped the world, or null if it is running. */
    public Throwable failure() {
        return failure;
    }

    /**
     * Waits until everything queued so far has run, for at most {@code timeout}; true if it has. For tests and
     * shutdown only: the server thread must never wait on physics.
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        queue(done::countDown, false);
        return done.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void queue(Runnable task, boolean skipIfFailed) {
        if (closed) {
            throw new IllegalStateException("Physics thread closed");
        }
        Runnable guarded = () -> {
            if (skipIfFailed && failure != null) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                failure = t;
            }
        };
        if (executor == null) {
            guarded.run();
        } else {
            executor.execute(guarded);
        }
    }

    /** Runs what is queued, frees the world and stops the thread. Waits up to 5 seconds. */
    @Override
    public void close() throws InterruptedException {
        if (closed) {
            return;
        }
        queue(world::close, false);
        closed = true;
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The physics thread didn't stop within 5 seconds");
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests.** Run: `cd mod && ./gradlew test`. Expected: all pass, the 4 new `PhysicsThreadTest` tests among them.

- [ ] **Step 5: Commit.**

```bash
git add mod/src/main/java/io/github/chabelabela/worldengine/physics mod/src/test/java/io/github/chabelabela/worldengine/physics
git commit -m "M2a: the physics thread: three steps per tick, never waited on"
```

---

### Task 6: Push, CI, docs

- [ ] **Step 1: Push and watch CI.** Every job must pass:
  - `core`: the new crates on every platform, and the library for all 5 targets;
  - `mod`: all JUnit tests, the GameTests and the client GameTest;
  - `mod-tests`: the native tests on Windows and macOS.

- [ ] **Step 2: Docs.**
  - **`docs/ROADMAP.md` §5 M2:** in the "Plans" list, mark this plan done. In the `we-physics` bullet, add "**Core done in M2a** ([plan](superpowers/plans/2026-11-24-m2a-physics-core.md)): Rapier voxel colliders for ships and terrain, mass properties from per-block masses, forces at points, and a physics thread at 60 Hz. The block properties table comes with ships in M2b." In "Next steps", record M2a as done and M2b as next.
  - **`docs/superpowers/plans/2026-11-24-m2-overview.md`:** mark M2a done.
  - **`README.md`:** the status line (M2a done, M2b next), and in the table, add ", done" to this plan's line.

- [ ] **Step 3: Commit and push.**

```bash
git add README.md docs
git commit -m "M2a: docs"
git push
```

There are no manual checks: nothing in the game uses the physics core until M2b.

---

## Known limits (by design, for later)

- **Partial blocks catch where sections meet.** A section's boxes are one compound collider, so a ship sliding along slabs or stairs across a section edge can catch on it, as full blocks would if they weren't one voxel shape. Merge boxes across sections if this shows in play.
- **Speed is capped at 400 m/s** (Rapier's default), well above the surface frame's 150 m/s. M3 decides what orbital speeds need.
- **Not bit-identical across platforms.** The server is authoritative, so clients never re-simulate. `enhanced-determinism` stays off.
- **The physics thread can fall behind** under a heavy load. Then ticks queue up and the published states lag. M2b measures it, and can skip ticks if it happens.
- **A ship's blocks can't change yet.** Editing a ship's blocks (building aboard) comes in M2d. Until then a changed ship is removed and added again.
- **Friction and bounce are Rapier's defaults** for every block. Ice, slime and honey come with the block properties table (M2b).

## Self-review against the M2 overview and ROADMAP

| Requirement | Where |
|---|---|
| `we-physics`: Rapier f64 worlds (ROADMAP §5 M2, §4.2) | Tasks 1–2 |
| Colliders built from blocks (ROADMAP: merged boxes) | Voxel shapes plus boxes, chosen over merged boxes by the overview's probe (Task 2) |
| Mass, centre of mass and inertia from per-block masses (ROADMAP) | `blocks.rs` (Task 1); the per-block table itself is M2b |
| Physics on its own thread at a fixed 60 Hz, double-buffered, the main thread never waiting (ROADMAP §4.1) | `PhysicsThread` (Task 5) |
| A coarse, batched C ABI that catches panics, versioned (ROADMAP §4.1) | `we-ffi/src/physics.rs` (Task 3), ABI v6 |
| An off-centre thruster makes it tumble (M2 exit) | `an_off_centre_force_spins_the_ship`, `forces_and_torques_turn_with_the_ship` (Task 2) |
| Twenty idle ships under 2 ms per tick (M2 exit) | `idle_ships_sleep` (Task 2); the cost is measured in the overview (0.001 ms per step) |
| Ships collide with terrain (ROADMAP, ADR 0001) | Terrain sections (Task 2); feeding them from chunks is M2b |
