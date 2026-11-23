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
    /// gives a rotation. Just beyond the face's edges (an overlap band's width)
    /// the grid's own frame carries on; far beyond them the result means nothing.
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
            // 0.0 - v turns -0.0 into +0.0, so the range is (-π, π]: +π at E3's
            // centre, 0 at the poles.
            longitude: libm::atan2(0.0 - up.x, 0.0 - up.z),
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
            // Mid-edge points just beyond the face (not corners: the 15° bound
            // of the grid frame holds only near the face).
            points.push((face, -10.5, n / 2.0));
            points.push((face, n + 10.5, n / 2.0));
            points.push((face, n / 2.0, -10.5));
            points.push((face, n / 2.0, n + 10.5));
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
        assert!((sky(Face::E3, half, half).longitude - PI).abs() < 1e-12);
        assert!((sky(Face::E0, half, half).longitude + FRAC_PI_2).abs() < 1e-12);
        // The poles have longitude exactly 0.
        assert_eq!(sky(Face::North, half, half).longitude, 0.0);
        assert_eq!(sky(Face::South, half, half).longitude, 0.0);
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
    fn the_pole_stands_at_the_latitude() {
        for (face, x, z) in samples() {
            let s = sky(face, x, z);
            let height = (s.tilt * DVec3::NEG_Z).y;
            assert!(
                (height - s.latitude.sin()).abs() < 1e-9,
                "{face:?} {x} {z}: {height} vs {}",
                s.latitude.sin()
            );
        }
        let n = f64::from(N);
        let s = sky(Face::North, n / 2.0, n - 0.5);
        assert!((s.latitude - PI / 4.0).abs() < 0.01, "{}", s.latitude);
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
