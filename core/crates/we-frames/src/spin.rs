use glam::{DQuat, DVec3};
use std::f64::consts::TAU;

/// A planet spinning about its body-frame +Y axis. The body frame (where the
/// surface grids live) turns with the planet; the inertial frame is the
/// planet-centred, non-rotating frame. Axial tilt and the planet's own orbit
/// are added on top of this in M3.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Spin {
    /// Seconds per full turn relative to the stars.
    pub sidereal_period: f64,
    /// Rotation angle (radians, about +Y) at t = 0.
    pub phase_at_epoch: f64,
}

impl Spin {
    pub fn angular_velocity(self) -> DVec3 {
        DVec3::Y * (TAU / self.sidereal_period)
    }

    /// Body-to-inertial rotation at time `t`.
    pub fn rotation_at(self, t: f64) -> DQuat {
        let turns = (t / self.sidereal_period).rem_euclid(1.0);
        DQuat::from_rotation_y(self.phase_at_epoch + TAU * turns)
    }

    /// Body-frame position and velocity → inertial position and velocity.
    pub fn to_inertial(self, position: DVec3, velocity: DVec3, t: f64) -> (DVec3, DVec3) {
        let rotation = self.rotation_at(t);
        (
            rotation * position,
            rotation * (velocity + self.angular_velocity().cross(position)),
        )
    }

    /// Inertial position and velocity → body-frame position and velocity.
    pub fn to_body(self, position: DVec3, velocity: DVec3, t: f64) -> (DVec3, DVec3) {
        let inverse = self.rotation_at(t).inverse();
        let body_position = inverse * position;
        (
            body_position,
            inverse * velocity - self.angular_velocity().cross(body_position),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DAY: f64 = 1_200.0;

    fn spin() -> Spin {
        Spin {
            sidereal_period: DAY,
            phase_at_epoch: 0.3,
        }
    }

    #[test]
    fn resting_surface_point_moves_east_at_omega_r() {
        let s = spin();
        let r = 10_430.0;
        let t = 321.0;
        let (_, v) = s.to_inertial(DVec3::new(r, 0.0, 0.0), DVec3::ZERO, t);
        let expected_speed = TAU / DAY * r;
        assert!((v.length() - expected_speed).abs() < 1e-9);
        // East at +X is -Z in the body frame (see Face::E0), rotated to time t.
        let east = s.rotation_at(t) * DVec3::NEG_Z;
        assert!((v.normalize() - east).length() < 1e-12);
    }

    #[test]
    fn round_trip_is_exact() {
        let s = spin();
        let (p, v) = (
            DVec3::new(1_000.0, -2_000.0, 9_000.0),
            DVec3::new(3.0, 40.0, -5.0),
        );
        for t in [0.0, 17.0, 5_000.5, 1.0e8] {
            let (ip, iv) = s.to_inertial(p, v, t);
            let (bp, bv) = s.to_body(ip, iv, t);
            assert!(
                (bp - p).length() < 1e-8 && (bv - v).length() < 1e-10,
                "t={t}"
            );
        }
    }

    #[test]
    fn one_sidereal_day_brings_a_point_back() {
        let s = spin();
        let p = DVec3::new(10_430.0, 5.0, 0.0);
        let (a, _) = s.to_inertial(p, DVec3::ZERO, 100.0);
        let (b, _) = s.to_inertial(p, DVec3::ZERO, 100.0 + DAY);
        assert!((a - b).length() < 1e-8);
    }
}
