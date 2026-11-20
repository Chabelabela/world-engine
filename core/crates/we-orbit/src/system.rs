use crate::KeplerOrbit;
use glam::DVec3;

/// Index of a body inside a [`CelestialSystem`].
pub type BodyId = usize;

/// A star, planet or moon.
#[derive(Clone, Debug, PartialEq)]
pub struct Body {
    pub name: String,
    /// Standard gravitational parameter `G·M`, m^3/s^2.
    pub mu: f64,
    /// Mean radius, metres (1 block = 1 metre).
    pub radius: f64,
    /// `None` for the root body, which sits at the origin.
    pub parent: Option<BodyId>,
    /// `None` for the root body.
    pub orbit: Option<KeplerOrbit>,
}

/// A hierarchy of bodies on Keplerian rails (patched-conics style).
#[derive(Clone, Debug, Default)]
pub struct CelestialSystem {
    bodies: Vec<Body>,
}

impl CelestialSystem {
    pub fn new() -> Self {
        Self::default()
    }

    /// The reference scale from docs/ROADMAP.md: a home planet whose cube-sphere
    /// faces are 16,384 blocks wide (radius ~= 10,430 m, 1 g) and a 1.62 m/s^2
    /// moon on a circular 60 km orbit.
    pub fn demo() -> Self {
        let mut system = Self::new();
        let home_radius = 10_430.0;
        let home = system.add_root("home", 9.81 * home_radius * home_radius, home_radius);
        let moon_radius = 2_600.0;
        system.add_orbiting(
            "moon",
            1.62 * moon_radius * moon_radius,
            moon_radius,
            home,
            KeplerOrbit::circular(60_000.0),
        );
        system
    }

    pub fn add_root(&mut self, name: &str, mu: f64, radius: f64) -> BodyId {
        assert!(self.bodies.is_empty(), "a system has exactly one root body");
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            parent: None,
            orbit: None,
        })
    }

    pub fn add_orbiting(
        &mut self,
        name: &str,
        mu: f64,
        radius: f64,
        parent: BodyId,
        orbit: KeplerOrbit,
    ) -> BodyId {
        assert!(parent < self.bodies.len(), "unknown parent body {parent}");
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            parent: Some(parent),
            orbit: Some(orbit),
        })
    }

    fn push(&mut self, body: Body) -> BodyId {
        self.bodies.push(body);
        self.bodies.len() - 1
    }

    pub fn len(&self) -> usize {
        self.bodies.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bodies.is_empty()
    }

    pub fn body(&self, id: BodyId) -> &Body {
        &self.bodies[id]
    }

    /// Position of `id` relative to the root body at time `t`.
    pub fn position_at(&self, id: BodyId, t: f64) -> DVec3 {
        let body = &self.bodies[id];
        match (body.parent, body.orbit) {
            (Some(parent), Some(orbit)) => {
                self.position_at(parent, t) + orbit.position_at(self.bodies[parent].mu, t)
            }
            _ => DVec3::ZERO,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn demo_has_home_and_moon() {
        let system = CelestialSystem::demo();
        assert_eq!(system.len(), 2);
        assert_eq!(system.body(0).name, "home");
        assert_eq!(system.body(1).name, "moon");
        assert_eq!(system.body(1).parent, Some(0));
    }

    #[test]
    fn root_stays_at_origin() {
        let system = CelestialSystem::demo();
        assert_eq!(system.position_at(0, 12_345.0), DVec3::ZERO);
    }

    #[test]
    fn moon_stays_60_km_from_home() {
        let system = CelestialSystem::demo();
        for i in 0..10 {
            let d = system.position_at(1, i as f64 * 300.0).length();
            assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
        }
    }

    #[test]
    fn moon_period_is_about_47_minutes() {
        let system = CelestialSystem::demo();
        let period = system.body(1).orbit.unwrap().period(system.body(0).mu);
        assert!((2_800.0..2_850.0).contains(&period), "period {period}");
    }

    #[test]
    #[should_panic(expected = "unknown parent body")]
    fn rejects_unknown_parent() {
        let mut system = CelestialSystem::new();
        system.add_root("star", 1.0, 1.0);
        system.add_orbiting("lost", 1.0, 1.0, 7, KeplerOrbit::circular(10.0));
    }
}
