//! Rigid bodies made of blocks: ships, and the terrain they touch (M2a).
//!
//! A [`World`] holds terrain, fed to it one 16³ section at a time, and ships,
//! each a rigid body built from its blocks. It steps at a fixed
//! [`STEP_SECONDS`] with Rapier (`rapier3d-f64`).
//!
//! - **Terrain** is one voxel shape for all full-cube blocks, so a ship slides
//!   over section edges without catching on them. Blocks with smaller
//!   collision shapes (slabs, stairs, fences) are boxes, one compound shape
//!   per section.
//! - **A ship** is one voxel shape for its full-cube blocks, plus one compound
//!   of boxes for its other blocks. Its mass, centre of mass and inertia are
//!   summed from per-block masses, each spread evenly over the block's boxes.
//! - **Coordinates:** a ship's blocks sit on its own integer grid. Block
//!   (x, y, z) fills [x, x + 1) × [y, y + 1) × [z, z + 1) of the ship's local
//!   frame, and a ship's pose places that frame in the world. World
//!   coordinates are Minecraft's: metres, Y up.
//! - **Not thread-safe:** a `World` is used by one thread at a time. Results
//!   are reproducible on one platform but not bit-identical across platforms.

mod blocks;
mod world;

pub use blocks::{
    BlockShape, Cuboid, MAX_BLOCK_BOXES, MAX_SHIP_BLOCKS, ShipBlock, ShipMass, mass_of,
};
pub use world::{
    MAX_SHIP_FORCES, MAX_STEPS, PhysicsError, STANDARD_GRAVITY, STEP_SECONDS, SectionPos,
    ShipForce, ShipMotion, ShipState, World,
};

/// Blocks along each edge of a terrain section.
pub const SECTION_SIZE: i32 = 16;
/// Blocks in a terrain section.
pub const SECTION_BLOCKS: usize = 4096;
/// 64-bit words in a section's full-block mask.
pub const SECTION_WORDS: usize = SECTION_BLOCKS / 64;
