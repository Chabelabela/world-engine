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

    /// Inertial position and velocity → surface position and grid velocity.
    pub fn inertial_to_surface(
        self,
        position: DVec3,
        velocity: DVec3,
        t: f64,
    ) -> (SurfacePoint, DVec3) {
        let (body_position, body_velocity) = self.spin.to_body(position, velocity, t);
        let p = self.sphere.to_surface(body_position);
        (p, self.sphere.velocity_to_surface(p, body_velocity))
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
            let (q, w) = frame.inertial_to_surface(position, velocity, t);
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
}
