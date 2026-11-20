//! Terrain for world generation. Stateless: no handle, no globals.

use crate::{WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use we_frames::{CubeSphere, Face};
use we_planet::{MAX_COLUMNS, PlanetKind, Terrain, fill_columns};

/// The terrain model's version, `we_planet::TERRAIN_VERSION`. Worlds store it
/// so that a later change to the terrain model is detected instead of
/// silently mixing old and new chunks.
#[unsafe(no_mangle)]
pub extern "C" fn we_terrain_version() -> u32 {
    we_planet::TERRAIN_VERSION
}

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
/// or `WE_ERR_INVALID` for a bad face, kind or cell count, `cells` above
/// `i32::MAX` (`x0`/`z0` are `i32`), a region outside the face, or more than
/// `we_planet::MAX_COLUMNS` columns. An empty region (a zero count) still has
/// its position validated.
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
    if total > MAX_COLUMNS || cells > i32::MAX as u32 {
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
    fn face_edge_is_valid_and_one_past_is_not() {
        let count = 4;
        let edge = (HOME_CELLS - count) as i32;
        assert_eq!(call(0, edge, edge, count, count).0, WE_OK);
        assert_eq!(call(0, edge + 1, 0, count, count).0, WE_ERR_INVALID);
        assert_eq!(call(0, 0, edge + 1, count, count).0, WE_ERR_INVALID);
        // Empty regions are still position-checked.
        assert_eq!(call(0, HOME_CELLS as i32 + 1, 0, 0, 3).0, WE_ERR_INVALID);
    }

    #[test]
    fn cells_beyond_i32_are_invalid() {
        let mut h = [0i32; 1];
        let mut b = [0u8; 1];
        let status = unsafe {
            we_terrain_columns(
                1,
                i32::MAX as u32 + 1,
                0,
                0,
                0,
                0,
                1,
                1,
                h.as_mut_ptr(),
                b.as_mut_ptr(),
            )
        };
        assert_eq!(status, WE_ERR_INVALID);
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
