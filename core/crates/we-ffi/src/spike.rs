//! M1b spike only (branch `spike/m1-handoff`, never merged): seam crossing for experiment E2.
//!
//! Exposes `we_frames::CubeSphere::fold` and `QuarterTurns` to the mod so the spike uses the real math
//! instead of a Java port. Not part of the stable ABI: `WE_ABI_VERSION` is unchanged.

use crate::{WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use we_frames::{CubeSphere, Face, SurfacePoint};

/// Returned when the point can't be folded: it is inside its face, beyond a corner, or more than one face
/// width out.
pub const WE_SPIKE_NOT_FOLDABLE: i32 = 1;

/// Carries a grid position that has left `face` across its edge onto the neighbouring face, together
/// with a grid velocity (blocks per tick, x east, z south) and a Minecraft yaw.
///
/// Writes 7 doubles to `out`: new face index, x, z (grid, on the new face), quarter turns (0..=3),
/// rotated velocity x, z, rotated yaw. Returns `WE_OK`, `WE_SPIKE_NOT_FOLDABLE`, `WE_ERR_NULL` or
/// `WE_ERR_INVALID` (bad `cells` or face index).
///
/// # Safety
/// `out` must be null or point to 7 writable, properly aligned `f64`s.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn we_spike_cross(
    cells: u32,
    face: u32,
    x: f64,
    z: f64,
    vx: f64,
    vz: f64,
    yaw: f32,
    out: *mut f64,
) -> i32 {
    if out.is_null() {
        return WE_ERR_NULL;
    }
    guard(|| {
        let (Some(sphere), Some(face)) = (
            CubeSphere::try_new(cells),
            u8::try_from(face).ok().and_then(Face::from_index),
        ) else {
            return WE_ERR_INVALID;
        };
        let Some(folded) = sphere.fold(SurfacePoint { face, x, y: 0.0, z }) else {
            return WE_SPIKE_NOT_FOLDABLE;
        };
        let (nvx, nvz) = folded.turns.apply(vx, vz);
        let result = [
            f64::from(folded.point.face.index()),
            folded.point.x,
            folded.point.z,
            f64::from(folded.turns.count()),
            nvx,
            nvz,
            f64::from(folded.turns.rotate_yaw(yaw)),
        ];
        // SAFETY: the caller guarantees `out` points to 7 writable f64s.
        unsafe { std::ptr::copy_nonoverlapping(result.as_ptr(), out, result.len()) };
        WE_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crosses_the_equator_wrap_without_rotation() {
        let mut out = [0.0; 7];
        // Just past E3's east edge (x = 16_384.25) folds onto E0's west edge, no rotation.
        let status = unsafe {
            we_spike_cross(
                16_384,
                3,
                16_384.25,
                100.5,
                0.3,
                0.0,
                -90.0,
                out.as_mut_ptr(),
            )
        };
        assert_eq!(status, WE_OK);
        assert_eq!(out[0], 0.0);
        assert!((out[1] - 0.25).abs() < 1e-9 && (out[2] - 100.5).abs() < 1e-9);
        assert_eq!(out[3], 0.0);
        assert_eq!((out[4], out[5], out[6]), (0.3, 0.0, -90.0));
    }

    #[test]
    fn rejects_points_inside_and_bad_input() {
        let mut out = [0.0; 7];
        assert_eq!(
            unsafe { we_spike_cross(16_384, 0, 10.0, 10.0, 0.0, 0.0, 0.0, out.as_mut_ptr()) },
            WE_SPIKE_NOT_FOLDABLE
        );
        assert_eq!(
            unsafe { we_spike_cross(16_383, 0, -1.0, 10.0, 0.0, 0.0, 0.0, out.as_mut_ptr()) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_spike_cross(16_384, 9, -1.0, 10.0, 0.0, 0.0, 0.0, out.as_mut_ptr()) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_spike_cross(16_384, 0, -1.0, 10.0, 0.0, 0.0, 0.0, std::ptr::null_mut()) },
            WE_ERR_NULL
        );
    }
}
