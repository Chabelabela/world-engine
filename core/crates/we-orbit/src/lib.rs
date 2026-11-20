//! Keplerian orbits and the celestial body hierarchy.
//!
//! Frame convention (shared by the whole core): right-handed, **Y-up**, metres,
//! seconds — the same axes as Minecraft. Orbits with zero inclination lie in
//! the XZ plane and their angular momentum points along +Y.

pub mod kepler;
pub mod system;

pub use kepler::{KeplerOrbit, solve_kepler};
pub use system::{Body, BodyId, CelestialSystem, Link, SystemError};
