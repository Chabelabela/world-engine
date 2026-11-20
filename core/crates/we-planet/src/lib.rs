//! Deterministic planet terrain: height, climate and biome as functions on
//! the sphere, keyed by a seed.
//!
//! - Everything is computed from integer hashing and IEEE arithmetic (plus
//!   `libm` inside `we-frames`), so a seed gives bit-identical terrain on
//!   every platform. Never use `f64::sin`/`tan`/`exp` here: platform maths
//!   libraries differ in the last bit.
//! - Terrain is sampled on the sphere, so it matches across cube seams
//!   without any stitching.
//! - There is no state: [`Terrain`] is `Copy` and every function is pure, so
//!   world generation may call it from any number of threads.

mod hash;
mod noise;

pub use hash::{hash3, layer_seed, mix};
pub use noise::{fbm, gradient};
