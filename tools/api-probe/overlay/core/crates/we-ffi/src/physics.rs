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
