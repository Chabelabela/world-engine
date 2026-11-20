//! Cube-sphere planets and the frames World Engine moves things between.
//!
//! - A planet is a cube whose six faces are square block grids ([`Face`]),
//!   projected onto a sphere with the equal-angle mapping ([`CubeSphere`]).
//! - Gameplay happens in the *surface frame*: one face's flat block grid,
//!   gravity along −Y, vanilla rules intact. Its axes (east, up, south) are a
//!   proper rotation of Minecraft's (X, Y, Z), so nothing is ever mirrored.
//! - The *body frame* is planet-centred and spins with the planet; the
//!   *inertial frame* is planet-centred and does not ([`Spin`], [`PlanetFrame`]).
//! - Walking off a face edge continues on the neighbouring face as if the
//!   cube were unfolded flat across that edge ([`CubeSphere::fold`]).
//! - [`PlanetLayout`] places the six faces in one Minecraft level.
//!
//! Units and axes match the rest of the core: metres, seconds, right-handed,
//! Y-up. Grid x grows east, grid z grows south (Minecraft's convention).

mod cube_sphere;
mod face;
mod frame;
mod layout;
mod spin;

pub use cube_sphere::{CubeSphere, Folded, SurfacePoint};
pub use face::{Edge, Face, QuarterTurns};
pub use frame::PlanetFrame;
pub use layout::PlanetLayout;
pub use spin::Spin;
