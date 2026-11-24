//! Drives the C ABI through the rlib in the same order the Java bridge does.

use worldengine_core::*;

#[test]
fn java_call_sequence() {
    assert_eq!(we_abi_version(), WE_ABI_VERSION);
    assert_eq!(we_terrain_version(), we_planet::TERRAIN_VERSION);
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
    let mut sky = [0.0f64; 11];
    assert_eq!(
        unsafe { we_local_sky(16_384, 1, 8_192.0, 8_192.0, sky.as_mut_ptr()) },
        WE_OK
    );
    assert_eq!(sky[0], 0.0, "spawn is longitude 0");
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
    assert!(
        heights
            .iter()
            .all(|h| (we_planet::MIN_HEIGHT as i32..=we_planet::MAX_HEIGHT as i32).contains(h))
    );
    assert!(biomes.iter().all(|b| *b <= 12));
}

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
