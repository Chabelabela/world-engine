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
