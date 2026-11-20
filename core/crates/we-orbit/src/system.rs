use crate::KeplerOrbit;
use glam::DVec3;
use std::fmt;

/// Identifies a body inside one [`CelestialSystem`]. Ids follow insertion
/// order, so a parent's id is always lower than its children's.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct BodyId(pub u32);

impl BodyId {
    pub fn index(self) -> usize {
        self.0 as usize
    }
}

impl fmt::Display for BodyId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "#{}", self.0)
    }
}

/// How a non-root body moves: on a Keplerian orbit around its parent.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Link {
    pub parent: BodyId,
    pub orbit: KeplerOrbit,
}

/// A star, planet or moon.
#[derive(Clone, Debug, PartialEq)]
pub struct Body {
    pub name: String,
    /// Standard gravitational parameter `G·M`, m^3/s^2.
    pub mu: f64,
    /// Mean radius, metres (1 block = 1 metre).
    pub radius: f64,
    /// `None` only for the root body, which sits at the origin.
    pub link: Option<Link>,
}

/// Why a body could not be added to a [`CelestialSystem`].
#[derive(Clone, Debug, PartialEq)]
pub enum SystemError {
    RootAlreadyExists,
    NoRoot,
    UnknownParent(BodyId),
    InvalidBody { name: String, mu: f64, radius: f64 },
    InvalidOrbit { name: String },
    TooManyBodies,
}

impl fmt::Display for SystemError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::RootAlreadyExists => write!(f, "a system has exactly one root body"),
            Self::NoRoot => write!(f, "add the root body first"),
            Self::UnknownParent(id) => write!(f, "unknown parent body {id}"),
            Self::InvalidBody { name, mu, radius } => {
                write!(f, "invalid body {name}: mu={mu}, radius={radius}")
            }
            Self::InvalidOrbit { name } => write!(f, "invalid orbit for {name}"),
            Self::TooManyBodies => write!(f, "too many bodies"),
        }
    }
}

impl std::error::Error for SystemError {}

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
        let home = system
            .add_root("home", 9.81 * home_radius * home_radius, home_radius)
            .expect("demo home is valid");
        let moon_radius = 2_600.0;
        system
            .add_orbiting(
                "moon",
                1.62 * moon_radius * moon_radius,
                moon_radius,
                home,
                KeplerOrbit::circular(60_000.0),
            )
            .expect("demo moon is valid");
        system
    }

    fn check_body(name: &str, mu: f64, radius: f64) -> Result<(), SystemError> {
        if mu.is_finite() && mu > 0.0 && radius.is_finite() && radius > 0.0 {
            Ok(())
        } else {
            Err(SystemError::InvalidBody {
                name: name.to_owned(),
                mu,
                radius,
            })
        }
    }

    pub fn add_root(&mut self, name: &str, mu: f64, radius: f64) -> Result<BodyId, SystemError> {
        if !self.bodies.is_empty() {
            return Err(SystemError::RootAlreadyExists);
        }
        Self::check_body(name, mu, radius)?;
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            link: None,
        })
    }

    pub fn add_orbiting(
        &mut self,
        name: &str,
        mu: f64,
        radius: f64,
        parent: BodyId,
        orbit: KeplerOrbit,
    ) -> Result<BodyId, SystemError> {
        if self.bodies.is_empty() {
            return Err(SystemError::NoRoot);
        }
        if parent.index() >= self.bodies.len() {
            return Err(SystemError::UnknownParent(parent));
        }
        Self::check_body(name, mu, radius)?;
        if !orbit.is_valid() {
            return Err(SystemError::InvalidOrbit {
                name: name.to_owned(),
            });
        }
        self.push(Body {
            name: name.to_owned(),
            mu,
            radius,
            link: Some(Link { parent, orbit }),
        })
    }

    fn push(&mut self, body: Body) -> Result<BodyId, SystemError> {
        let id = u32::try_from(self.bodies.len()).map_err(|_| SystemError::TooManyBodies)?;
        self.bodies.push(body);
        Ok(BodyId(id))
    }

    pub fn len(&self) -> usize {
        self.bodies.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bodies.is_empty()
    }

    pub fn body(&self, id: BodyId) -> Option<&Body> {
        self.bodies.get(id.index())
    }

    /// Position of `id` relative to the root body at time `t`, or `None` for
    /// an unknown id.
    pub fn position_at(&self, id: BodyId, t: f64) -> Option<DVec3> {
        let mut body = self.body(id)?;
        let mut position = DVec3::ZERO;
        while let Some(link) = body.link {
            let parent = &self.bodies[link.parent.index()];
            position += link.orbit.position_at(parent.mu, t);
            body = parent;
        }
        Some(position)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HOME: BodyId = BodyId(0);
    const MOON: BodyId = BodyId(1);

    #[test]
    fn demo_has_home_and_moon() {
        let system = CelestialSystem::demo();
        assert_eq!(system.len(), 2);
        assert_eq!(system.body(HOME).unwrap().name, "home");
        let moon = system.body(MOON).unwrap();
        assert_eq!(moon.name, "moon");
        assert_eq!(moon.link.unwrap().parent, HOME);
    }

    #[test]
    fn root_stays_at_origin() {
        let system = CelestialSystem::demo();
        assert_eq!(system.position_at(HOME, 12_345.0), Some(DVec3::ZERO));
    }

    #[test]
    fn moon_stays_60_km_from_home() {
        let system = CelestialSystem::demo();
        for i in 0..10 {
            let d = system.position_at(MOON, i as f64 * 300.0).unwrap().length();
            assert!((d - 60_000.0).abs() < 1e-6, "distance {d}");
        }
    }

    #[test]
    fn moon_period_is_about_47_minutes() {
        let system = CelestialSystem::demo();
        let orbit = system.body(MOON).unwrap().link.unwrap().orbit;
        let period = orbit.period(system.body(HOME).unwrap().mu);
        assert!((2_800.0..2_850.0).contains(&period), "period {period}");
    }

    #[test]
    fn unknown_ids_have_no_body_or_position() {
        let system = CelestialSystem::demo();
        assert!(system.body(BodyId(7)).is_none());
        assert!(system.position_at(BodyId(7), 0.0).is_none());
    }

    #[test]
    fn three_level_hierarchy_sums_positions() {
        let mut system = CelestialSystem::new();
        let star = system.add_root("star", 1.0e12, 50_000.0).unwrap();
        let planet = system
            .add_orbiting(
                "planet",
                1.0e9,
                10_000.0,
                star,
                KeplerOrbit::circular(1.0e6),
            )
            .unwrap();
        let moon = system
            .add_orbiting(
                "moon",
                1.0e7,
                2_000.0,
                planet,
                KeplerOrbit::circular(40_000.0),
            )
            .unwrap();
        let t = 1_234.0;
        let expected = KeplerOrbit::circular(1.0e6).position_at(1.0e12, t)
            + KeplerOrbit::circular(40_000.0).position_at(1.0e9, t);
        assert!((system.position_at(moon, t).unwrap() - expected).length() < 1e-6);
    }

    #[test]
    fn rejects_bad_construction() {
        let mut system = CelestialSystem::new();
        let circular = KeplerOrbit::circular(10.0);
        assert_eq!(
            system.add_orbiting("early", 1.0, 1.0, HOME, circular),
            Err(SystemError::NoRoot)
        );
        assert!(matches!(
            system.add_root("bad", -1.0, 1.0),
            Err(SystemError::InvalidBody { .. })
        ));
        let home = system.add_root("home", 1.0, 1.0).unwrap();
        assert_eq!(
            system.add_root("second", 1.0, 1.0),
            Err(SystemError::RootAlreadyExists)
        );
        assert_eq!(
            system.add_orbiting("lost", 1.0, 1.0, BodyId(7), circular),
            Err(SystemError::UnknownParent(BodyId(7)))
        );
        let eccentric = KeplerOrbit {
            eccentricity: 1.5,
            ..circular
        };
        assert_eq!(
            system.add_orbiting("bad", 1.0, 1.0, home, eccentric),
            Err(SystemError::InvalidOrbit {
                name: "bad".to_owned()
            })
        );
        assert_eq!(system.len(), 1, "failed adds must not change the system");
    }

    #[test]
    fn errors_have_readable_messages() {
        assert_eq!(
            SystemError::UnknownParent(BodyId(7)).to_string(),
            "unknown parent body #7"
        );
        assert_eq!(
            SystemError::RootAlreadyExists.to_string(),
            "a system has exactly one root body"
        );
    }
}
