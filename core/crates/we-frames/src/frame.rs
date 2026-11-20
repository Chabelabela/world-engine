use crate::cube_sphere::{CubeSphere, SurfacePoint};
use crate::spin::Spin;
use glam::DVec3;

/// A spinning cube-sphere planet: converts states between a face's surface
/// frame and the planet-centred inertial frame. This is the math behind the
/// surface ↔ orbit handoff (ROADMAP §4.3).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PlanetFrame {
    pub sphere: CubeSphere,
    pub spin: Spin,
}

impl PlanetFrame {
    /// Surface position and grid velocity (blocks/s east and south, m/s up)
    /// → inertial position and velocity at time `t`.
    ///
    /// `p.y` is metres above the datum sphere, not Minecraft block y (the
    /// level layout maps between them in M1d). Grid velocity is in blocks/s
    /// horizontally and m/s vertically; one block spans `(R + y) / R` metres
    /// times the local projection scale (0.71 to 1.0), so at 1.5 km altitude
    /// a block is about 1.14 m and 150 m/s is about 131 blocks/s.
    pub fn surface_to_inertial(
        self,
        p: SurfacePoint,
        grid_velocity: DVec3,
        t: f64,
    ) -> (DVec3, DVec3) {
        let position = self.sphere.to_body(p);
        let velocity = self.sphere.velocity_to_body(p, grid_velocity);
        self.spin.to_inertial(position, velocity, t)
    }

    /// Inertial position and velocity → surface position and grid velocity,
    /// or `None` if the position is non-finite or at the planet's centre.
    ///
    /// Grid velocity is in blocks/s horizontally and m/s vertically; see
    /// [`CubeSphere::velocity_to_body`] for the scale (a 150 m/s ship at
    /// 1.5 km altitude moves about 131 blocks/s).
    pub fn inertial_to_surface(
        self,
        position: DVec3,
        velocity: DVec3,
        t: f64,
    ) -> Option<(SurfacePoint, DVec3)> {
        let (body_position, body_velocity) = self.spin.to_body(position, velocity, t);
        let p = self.sphere.to_surface(body_position)?;
        Some((p, self.sphere.velocity_to_surface(p, body_velocity)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::face::Face;
    use proptest::prelude::*;

    fn home() -> PlanetFrame {
        PlanetFrame {
            sphere: CubeSphere::new(16_384),
            spin: Spin {
                sidereal_period: 1_200.0,
                phase_at_epoch: 0.0,
            },
        }
    }

    proptest! {
        // ROADMAP §4.3: surface → orbit → surface must agree within 1 cm and 1 mm/s.
        #[test]
        fn handoff_round_trip_is_within_a_centimetre(
            face in prop::sample::select(Face::ALL.to_vec()),
            x in 0.01f64..16_383.99,
            y in 0.0f64..3_000.0,
            z in 0.01f64..16_383.99,
            vx in -200.0f64..200.0,
            vy in -200.0f64..200.0,
            vz in -200.0f64..200.0,
            t in 0.0f64..1.0e7,
        ) {
            let frame = home();
            let p = SurfacePoint { face, x, y, z };
            let v = DVec3::new(vx, vy, vz);
            let (position, velocity) = frame.surface_to_inertial(p, v, t);
            let (q, w) = frame.inertial_to_surface(position, velocity, t).unwrap();
            prop_assert_eq!(q.face, face);
            let error = frame.sphere.to_body(q) - frame.sphere.to_body(p);
            prop_assert!(error.length() < 0.01, "position error {} m", error.length());
            prop_assert!((w - v).length() < 0.001, "velocity error {} m/s", (w - v).length());
        }
    }

    #[test]
    fn standing_still_on_the_equator_is_moving_in_orbit_terms() {
        let frame = home();
        let p = SurfacePoint {
            face: Face::E0,
            x: 8_192.0,
            y: 0.0,
            z: 8_192.0,
        };
        let (_, v) = frame.surface_to_inertial(p, DVec3::ZERO, 0.0);
        let expected = std::f64::consts::TAU / 1_200.0 * frame.sphere.radius();
        assert!(
            (v.length() - expected).abs() < 1e-9,
            "{} vs {expected}",
            v.length()
        );
    }

    #[test]
    fn prograde_orbit_seen_from_the_ground() {
        // Phase 0 at t = 0: body and inertial frames coincide.
        let frame = home();
        let radius = frame.sphere.radius();
        let altitude = 1_500.0;
        let r = radius + altitude;
        let mu = 9.81 * radius * radius;
        let v_orb = (mu / r).sqrt();
        let (p, v) = frame
            .inertial_to_surface(DVec3::X * r, DVec3::NEG_Z * v_orb, 0.0)
            .expect("valid position");
        assert_eq!(p.face, Face::E0);
        assert!((p.x - 8_192.0).abs() < 1e-6 && (p.z - 8_192.0).abs() < 1e-6);
        assert!((p.y - altitude).abs() < 1e-9);
        let omega_r = frame.spin.angular_velocity().length() * r;
        let expected = (v_orb - omega_r) / (r / radius);
        assert!(
            ((v.x - expected) / expected).abs() < 1e-9,
            "east {} vs {expected}",
            v.x
        );
        assert!(v.y.abs() < 1e-9 && v.z.abs() < 1e-9, "{v:?}");
    }

    #[test]
    fn inertial_to_surface_rejects_bad_positions() {
        let frame = home();
        assert!(
            frame
                .inertial_to_surface(DVec3::ZERO, DVec3::X, 0.0)
                .is_none()
        );
        assert!(
            frame
                .inertial_to_surface(DVec3::NAN, DVec3::X, 0.0)
                .is_none()
        );
    }
}
