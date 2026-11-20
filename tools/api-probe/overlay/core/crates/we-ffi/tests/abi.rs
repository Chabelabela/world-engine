//! Drives the C ABI through the rlib in the same order the Java bridge does.

use worldengine_core::*;

#[test]
fn java_call_sequence() {
    assert_eq!(we_abi_version(), WE_ABI_VERSION);
    assert_eq!(we_terrain_version(), we_planet::TERRAIN_VERSION);
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
