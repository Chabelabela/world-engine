use glam::{DQuat, DVec3};
use std::f64::consts::TAU;

/// Solve Kepler's equation `M = E - e·sin(E)` for the eccentric anomaly `E`.
///
/// Valid for elliptic orbits (`0 <= e < 1`). Newton–Raphson, converges to
/// machine precision in a handful of iterations.
pub fn solve_kepler(mean_anomaly: f64, eccentricity: f64) -> f64 {
    debug_assert!((0.0..1.0).contains(&eccentricity), "elliptic orbits only");
    let m = mean_anomaly.rem_euclid(TAU);
    let mut e_anom = if eccentricity < 0.8 {
        m
    } else {
        std::f64::consts::PI
    };
    for _ in 0..50 {
        let f = e_anom - eccentricity * e_anom.sin() - m;
        let f_prime = 1.0 - eccentricity * e_anom.cos();
        let step = f / f_prime;
        e_anom -= step;
        if step.abs() < 1e-15 {
            break;
        }
    }
    e_anom
}

/// Classical orbital elements of a closed (elliptic) orbit around a parent body.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct KeplerOrbit {
    /// Semi-major axis, metres.
    pub semi_major_axis: f64,
    /// Eccentricity, `0 <= e < 1`.
    pub eccentricity: f64,
    /// Inclination, radians.
    pub inclination: f64,
    /// Longitude of the ascending node, radians.
    pub longitude_of_ascending_node: f64,
    /// Argument of periapsis, radians.
    pub argument_of_periapsis: f64,
    /// Mean anomaly at `epoch`, radians.
    pub mean_anomaly_at_epoch: f64,
    /// Reference time for `mean_anomaly_at_epoch`, seconds.
    pub epoch: f64,
}

impl KeplerOrbit {
    /// A circular, equatorial orbit of the given radius, at periapsis at t = 0.
    pub fn circular(radius: f64) -> Self {
        Self {
            semi_major_axis: radius,
            eccentricity: 0.0,
            inclination: 0.0,
            longitude_of_ascending_node: 0.0,
            argument_of_periapsis: 0.0,
            mean_anomaly_at_epoch: 0.0,
            epoch: 0.0,
        }
    }

    /// Mean motion `n = sqrt(mu / a^3)`, radians per second.
    pub fn mean_motion(&self, mu: f64) -> f64 {
        (mu / self.semi_major_axis.powi(3)).sqrt()
    }

    /// Orbital period, seconds.
    pub fn period(&self, mu: f64) -> f64 {
        TAU / self.mean_motion(mu)
    }

    /// Position and velocity relative to the parent body at time `t` (seconds).
    /// `mu` is the parent's standard gravitational parameter (m^3/s^2).
    pub fn state_at(&self, mu: f64, t: f64) -> (DVec3, DVec3) {
        let a = self.semi_major_axis;
        let e = self.eccentricity;
        let n = self.mean_motion(mu);
        let mean_anomaly = self.mean_anomaly_at_epoch + n * (t - self.epoch);
        let ecc_anomaly = solve_kepler(mean_anomaly, e);
        let (sin_e, cos_e) = ecc_anomaly.sin_cos();
        let b = a * (1.0 - e * e).sqrt();
        let e_dot = n / (1.0 - e * cos_e);

        // Perifocal frame in classical z-up form: x toward periapsis,
        // y 90 degrees ahead in the direction of motion.
        let pos_pf = DVec3::new(a * (cos_e - e), b * sin_e, 0.0);
        let vel_pf = DVec3::new(-a * sin_e * e_dot, b * cos_e * e_dot, 0.0);

        let rot = DQuat::from_rotation_z(self.longitude_of_ascending_node)
            * DQuat::from_rotation_x(self.inclination)
            * DQuat::from_rotation_z(self.argument_of_periapsis);
        (zup_to_yup(rot * pos_pf), zup_to_yup(rot * vel_pf))
    }

    /// Position relative to the parent body at time `t`.
    pub fn position_at(&self, mu: f64, t: f64) -> DVec3 {
        self.state_at(mu, t).0
    }
}

/// Rotate a vector from the classical astrodynamics z-up frame into the
/// engine's Y-up frame (a proper rotation of -90 degrees about X).
fn zup_to_yup(v: DVec3) -> DVec3 {
    DVec3::new(v.x, v.z, -v.y)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    const MU: f64 = 1.0672e9; // home planet, see CelestialSystem::demo

    fn rel_err(a: f64, b: f64) -> f64 {
        ((a - b) / b).abs()
    }

    #[test]
    fn kepler_solver_satisfies_equation() {
        for &e in &[0.0, 0.1, 0.5, 0.9, 0.99] {
            for i in 0..64 {
                let m = i as f64 * TAU / 64.0;
                let ecc = solve_kepler(m, e);
                let residual = (ecc - e * ecc.sin() - m.rem_euclid(TAU)).abs();
                assert!(residual < 1e-12, "e={e} M={m} residual={residual}");
            }
        }
    }

    #[test]
    fn circular_orbit_keeps_radius_and_speed() {
        let orbit = KeplerOrbit::circular(13_430.0);
        let expected_speed = (MU / 13_430.0).sqrt();
        for i in 0..20 {
            let (pos, vel) = orbit.state_at(MU, i as f64 * 17.3);
            assert!(rel_err(pos.length(), 13_430.0) < 1e-12);
            assert!(rel_err(vel.length(), expected_speed) < 1e-12);
        }
    }

    #[test]
    fn orbit_returns_to_start_after_one_period() {
        let orbit = KeplerOrbit {
            eccentricity: 0.3,
            inclination: 0.4,
            longitude_of_ascending_node: 1.1,
            argument_of_periapsis: 2.0,
            mean_anomaly_at_epoch: 0.7,
            ..KeplerOrbit::circular(60_000.0)
        };
        let period = orbit.period(MU);
        let start = orbit.position_at(MU, 123.0);
        let after = orbit.position_at(MU, 123.0 + period);
        assert!((after - start).length() < 1e-6 * 60_000.0);
    }

    #[test]
    fn eccentric_orbit_obeys_vis_viva() {
        let orbit = KeplerOrbit {
            eccentricity: 0.6,
            ..KeplerOrbit::circular(40_000.0)
        };
        for i in 0..50 {
            let (pos, vel) = orbit.state_at(MU, i as f64 * 97.0);
            let r = pos.length();
            let expected_v2 = MU * (2.0 / r - 1.0 / 40_000.0);
            assert!(rel_err(vel.length_squared(), expected_v2) < 1e-10);
        }
    }

    #[test]
    fn periapsis_and_apoapsis_distances() {
        let orbit = KeplerOrbit {
            eccentricity: 0.25,
            ..KeplerOrbit::circular(20_000.0)
        };
        let half_period = orbit.period(MU) / 2.0;
        assert!(rel_err(orbit.position_at(MU, 0.0).length(), 20_000.0 * 0.75) < 1e-12);
        assert!(rel_err(orbit.position_at(MU, half_period).length(), 20_000.0 * 1.25) < 1e-12);
    }

    #[test]
    fn equatorial_orbit_lies_in_xz_plane_and_turns_about_plus_y() {
        let orbit = KeplerOrbit::circular(13_430.0);
        let (pos, vel) = orbit.state_at(MU, 42.0);
        assert!(pos.y.abs() < 1e-9);
        let h = pos.cross(vel).normalize();
        assert!((h - DVec3::Y).length() < 1e-12, "angular momentum {h:?}");
    }

    #[test]
    fn polar_orbit_angular_momentum_is_horizontal() {
        let orbit = KeplerOrbit {
            inclination: PI / 2.0,
            ..KeplerOrbit::circular(13_430.0)
        };
        let (pos, vel) = orbit.state_at(MU, 10.0);
        let h = pos.cross(vel).normalize();
        assert!(h.y.abs() < 1e-12, "angular momentum {h:?}");
    }
}
