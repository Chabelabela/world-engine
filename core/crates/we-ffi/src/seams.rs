//! Seam crossing: carrying an entity across one of a planet's seam edges (M1e).

use crate::{WE_ERR_INVALID, WE_ERR_NULL, WE_OK, guard};
use we_frames::{CubeSphere, Face, SurfacePoint};

/// Returned by [`we_seam_cross`] when the point can't be folded: it is on
/// its face (edges included), beyond a corner, or more than one face width
/// out. Not an error: the caller decides what happens there.
pub const WE_SEAM_NOT_FOLDABLE: i32 = 1;

/// Carry grid point (`x`, `z`) of `face`, which lies beyond one of the face's
/// edges, onto the neighbouring face (`we_frames::CubeSphere::fold`), with a
/// grid velocity (`vx` east, `vz` south, any unit) and a Minecraft yaw in
/// degrees. Velocity and yaw are rotated by the fold's quarter turns
/// (`QuarterTurns::apply`, `QuarterTurns::rotate_yaw`).
///
/// Writes 7 doubles to `out`: the new face index (0–5), x, z (grid, on the new
/// face), quarter turns (0–3), rotated vx, vz, and rotated yaw.
///
/// Returns `WE_OK`, [`WE_SEAM_NOT_FOLDABLE`], `WE_ERR_NULL` for a null `out`,
/// or `WE_ERR_INVALID` for a bad `cells` or face index. Stateless and
/// thread-safe.
///
/// # Safety
/// `out` must be null or valid for writing 7 `f64`s. It needn't be aligned.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn we_seam_cross(
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
    let (Some(sphere), Some(face)) = (
        CubeSphere::try_new(cells),
        u8::try_from(face).ok().and_then(Face::from_index),
    ) else {
        return WE_ERR_INVALID;
    };
    guard(|| {
        let Some(folded) = sphere.fold(SurfacePoint { face, x, y: 0.0, z }) else {
            return WE_SEAM_NOT_FOLDABLE;
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
        // SAFETY: the caller guarantees `out` is valid for 7 f64 writes.
        unsafe { std::ptr::copy_nonoverlapping(result.as_ptr(), out, result.len()) };
        WE_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const N: u32 = 16_384;

    fn cross(face: u32, x: f64, z: f64, vx: f64, vz: f64, yaw: f32) -> (i32, [f64; 7]) {
        let mut out = [f64::NAN; 7];
        let status = unsafe { we_seam_cross(N, face, x, z, vx, vz, yaw, out.as_mut_ptr()) };
        (status, out)
    }

    #[test]
    fn crosses_the_equator_wrap_without_rotation() {
        // Just past E3's east edge folds onto E0's west edge, unrotated.
        let (status, out) = cross(3, 16_384.25, 100.5, 0.3, 0.0, -90.0);
        assert_eq!(status, WE_OK);
        assert_eq!(out, [0.0, 0.25, 100.5, 0.0, 0.3, 0.0, -90.0]);
    }

    #[test]
    fn rotates_velocity_and_yaw_with_the_fold() {
        // North's west edge meets E0 (E2 in the M1b spike: a westward sprint
        // continues southward, yaw 90 -> 0).
        let (status, out) = cross(4, -0.25, 100.5, -0.281, 0.0, 90.0);
        assert_eq!(status, WE_OK);
        assert_eq!(out[0], 0.0, "lands on E0");
        assert_eq!(out[3], 3.0, "three quarter turns");
        assert_eq!((out[4], out[5]), (0.0, 0.281));
        assert_eq!(out[6], 0.0);
    }

    #[test]
    fn matches_we_frames_fold_for_every_face_and_side() {
        let sphere = CubeSphere::new(N);
        let n = f64::from(N);
        for face in Face::ALL {
            for (x, z) in [
                (-0.5, 777.25),
                (n + 0.5, 777.25),
                (777.25, -0.5),
                (777.25, n + 0.5),
            ] {
                let folded = sphere.fold(SurfacePoint { face, x, y: 0.0, z }).unwrap();
                let (status, out) = cross(u32::from(face.index()), x, z, 1.0, 0.0, 0.0);
                assert_eq!(status, WE_OK);
                assert_eq!(out[0], f64::from(folded.point.face.index()));
                assert_eq!((out[1], out[2]), (folded.point.x, folded.point.z));
                assert_eq!(out[3], f64::from(folded.turns.count()));
                assert_eq!((out[4], out[5]), folded.turns.apply(1.0, 0.0));
            }
        }
    }

    #[test]
    fn leaves_points_it_cannot_fold() {
        assert_eq!(cross(0, 10.0, 10.0, 0.0, 0.0, 0.0).0, WE_SEAM_NOT_FOLDABLE); // on the face
        assert_eq!(cross(0, -1.0, -1.0, 0.0, 0.0, 0.0).0, WE_SEAM_NOT_FOLDABLE); // beyond a corner
        assert_eq!(
            cross(0, f64::NAN, 1.0, 0.0, 0.0, 0.0).0,
            WE_SEAM_NOT_FOLDABLE
        );
    }

    #[test]
    fn rejects_bad_input() {
        let mut out = [0.0; 7];
        let p = out.as_mut_ptr();
        assert_eq!(
            unsafe { we_seam_cross(16_383, 0, -1.0, 10.0, 0.0, 0.0, 0.0, p) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_seam_cross(N, 6, -1.0, 10.0, 0.0, 0.0, 0.0, p) },
            WE_ERR_INVALID
        );
        assert_eq!(
            unsafe { we_seam_cross(N, 0, -1.0, 10.0, 0.0, 0.0, 0.0, std::ptr::null_mut()) },
            WE_ERR_NULL
        );
    }
}
