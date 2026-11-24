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
