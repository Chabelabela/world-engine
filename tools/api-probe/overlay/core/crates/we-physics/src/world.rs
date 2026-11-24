//! A physics world: terrain sections and ships, stepped at a fixed rate.

use crate::blocks::{
    self, BlockShape, Cuboid, MAX_BLOCK_BOXES, MAX_SHIP_BLOCKS, ShipBlock, ShipMass,
};
use crate::{SECTION_BLOCKS, SECTION_SIZE, SECTION_WORDS};
use rapier3d_f64::parry::bounding_volume::Aabb;
use rapier3d_f64::prelude::*;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;

/// Seconds per step: Minecraft's 20 Hz tick is three steps.
pub const STEP_SECONDS: f64 = 1.0 / 60.0;
/// Most steps one [`World::step`] call may take (ten seconds).
pub const MAX_STEPS: u32 = 600;
/// A new ship's gravity in m/s², until [`World::set_ship_gravity`] says otherwise.
pub const STANDARD_GRAVITY: f64 = 9.81;
/// Most forces one ship may have (one per thruster).
pub const MAX_SHIP_FORCES: usize = 4096;

/// A terrain section: blocks [16x, 16x + 16) × [16y, 16y + 16) × [16z, 16z + 16).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct SectionPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

/// Where a ship is and how it moves.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipMotion {
    /// Where the ship's frame has its origin, in world coordinates.
    pub position: [f64; 3],
    /// The unit quaternion (x, y, z, w) that turns the ship's frame into the world's.
    pub rotation: [f64; 4],
    /// The velocity of the centre of mass, m/s.
    pub linvel: [f64; 3],
    /// Radians per second about each world axis.
    pub angvel: [f64; 3],
}

impl ShipMotion {
    /// At rest at `position`, unrotated.
    pub fn at(position: [f64; 3]) -> Self {
        Self {
            position,
            rotation: [0.0, 0.0, 0.0, 1.0],
            linvel: [0.0; 3],
            angvel: [0.0; 3],
        }
    }
}

/// A ship's motion after a step, and whether it is asleep (at rest, costing almost nothing).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipState {
    pub id: u32,
    pub motion: ShipMotion,
    pub sleeping: bool,
}

/// A force on a ship, in its own frame: `force` newtons at `point`, both turning with the ship.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipForce {
    pub force: [f64; 3],
    pub point: [f64; 3],
}

/// Why a [`World`] call did nothing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhysicsError {
    /// An argument was out of range; the text says which.
    Invalid(&'static str),
    UnknownShip(u32),
    DuplicateShip(u32),
}

impl fmt::Display for PhysicsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(why) => write!(f, "invalid argument: {why}"),
            Self::UnknownShip(id) => write!(f, "no ship {id}"),
            Self::DuplicateShip(id) => write!(f, "ship {id} already exists"),
        }
    }
}

impl std::error::Error for PhysicsError {}

struct Section {
    full: Box<[u64; SECTION_WORDS]>,
    partial: Option<ColliderHandle>,
}

struct Ship {
    body: RigidBodyHandle,
    forces: Vec<ShipForce>,
    torque: [f64; 3],
}

impl Ship {
    fn is_driven(&self) -> bool {
        !self.forces.is_empty() || self.torque != [0.0; 3]
    }
}

/// Terrain and ships. See the crate documentation.
pub struct World {
    physics: PhysicsWorld,
    /// All terrain sections' full blocks, as one voxel shape; `None` while there are none.
    terrain: Option<ColliderHandle>,
    terrain_voxels: usize,
    sections: HashMap<SectionPos, Section>,
    ships: BTreeMap<u32, Ship>,
}

impl Default for World {
    fn default() -> Self {
        Self::new()
    }
}

fn vector(v: [f64; 3]) -> Vector {
    Vector::new(v[0], v[1], v[2])
}

fn array(v: Vector) -> [f64; 3] {
    [v.x, v.y, v.z]
}

fn finite(v: &[f64]) -> bool {
    v.iter().all(|x| x.is_finite())
}

/// The block at `index` (Minecraft's section order: y, then z, then x) of `pos`.
fn voxel_key(pos: SectionPos, index: usize) -> IVector {
    let (x, y, z) = (index & 15, index >> 8, (index >> 4) & 15);
    let s = i64::from(SECTION_SIZE);
    IVector::new(
        i64::from(pos.x) * s + x as i64,
        i64::from(pos.y) * s + y as i64,
        i64::from(pos.z) * s + z as i64,
    )
}

fn section_origin(pos: SectionPos) -> Vector {
    let s = f64::from(SECTION_SIZE);
    Vector::new(
        f64::from(pos.x) * s,
        f64::from(pos.y) * s,
        f64::from(pos.z) * s,
    )
}

fn motion_pose(motion: &ShipMotion) -> Result<Pose, PhysicsError> {
    if !(finite(&motion.position)
        && finite(&motion.rotation)
        && finite(&motion.linvel)
        && finite(&motion.angvel))
    {
        return Err(PhysicsError::Invalid("ship motion must be finite"));
    }
    let [x, y, z, w] = motion.rotation;
    let q = Rotation::from_xyzw(x, y, z, w);
    let norm = q.length();
    if !(0.5..=2.0).contains(&norm) {
        return Err(PhysicsError::Invalid(
            "ship rotation must be a unit quaternion",
        ));
    }
    Ok(Pose::from_parts(vector(motion.position), q / norm))
}

impl World {
    pub fn new() -> Self {
        let mut physics = PhysicsWorld::new();
        // Each ship's gravity scale is its local g (World::set_ship_gravity).
        physics.gravity = Vector::NEG_Y;
        physics.integration_parameters.dt = STEP_SECONDS;
        Self {
            physics,
            terrain: None,
            terrain_voxels: 0,
            sections: HashMap::new(),
            ships: BTreeMap::new(),
        }
    }

    /// Sets terrain section `pos`, replacing what was there. Bit `i` of
    /// `full` (word `i / 64`, bit `i % 64`) marks block `i` as a full cube,
    /// with blocks in Minecraft's section order: `i = y·256 + z·16 + x`.
    /// `partial` holds the boxes of every other colliding block, in the
    /// section's frame (each within −1..=17 on every axis). Wakes ships near
    /// the section.
    pub fn set_section(
        &mut self,
        pos: SectionPos,
        full: &[u64; SECTION_WORDS],
        partial: &[Cuboid],
    ) -> Result<(), PhysicsError> {
        if partial.len() > SECTION_BLOCKS * MAX_BLOCK_BOXES {
            return Err(PhysicsError::Invalid("too many boxes in a section"));
        }
        if !partial
            .iter()
            .all(|b| b.is_valid_within(-1.0, f64::from(SECTION_SIZE) + 1.0))
        {
            return Err(PhysicsError::Invalid(
                "a section box is empty, not finite or out of range",
            ));
        }
        let old = self.sections.remove(&pos);
        let old_full = old.as_ref().map_or([0; SECTION_WORDS], |s| *s.full);
        self.edit_voxels(pos, &old_full, full);
        if let Some(handle) = old.and_then(|s| s.partial) {
            self.physics.remove_collider(handle);
        }
        let partial = if partial.is_empty() {
            None
        } else {
            let origin = section_origin(pos);
            let shapes = partial
                .iter()
                .map(|b| {
                    let h = b.half_extents();
                    (
                        Pose::from_translation(origin + b.center()),
                        SharedShape::cuboid(h.x, h.y, h.z),
                    )
                })
                .collect();
            Some(
                self.physics
                    .insert_collider(ColliderBuilder::compound(shapes).build(), None),
            )
        };
        self.sections.insert(
            pos,
            Section {
                full: Box::new(*full),
                partial,
            },
        );
        self.wake_ships_near(pos);
        Ok(())
    }

    /// Removes terrain section `pos`, if it was set, and wakes ships near it.
    pub fn clear_section(&mut self, pos: SectionPos) {
        if let Some(section) = self.sections.remove(&pos) {
            self.edit_voxels(pos, &section.full, &[0; SECTION_WORDS]);
            if let Some(handle) = section.partial {
                self.physics.remove_collider(handle);
            }
            self.wake_ships_near(pos);
        }
    }

    /// The number of terrain sections set.
    pub fn section_count(&self) -> usize {
        self.sections.len()
    }

    fn edit_voxels(
        &mut self,
        pos: SectionPos,
        old: &[u64; SECTION_WORDS],
        new: &[u64; SECTION_WORDS],
    ) {
        let mut filled = Vec::new();
        let mut emptied = Vec::new();
        for (w, (&a, &b)) in old.iter().zip(new).enumerate() {
            let mut changed = a ^ b;
            while changed != 0 {
                let bit = changed.trailing_zeros() as usize;
                changed &= changed - 1;
                let key = voxel_key(pos, w * 64 + bit);
                if b >> bit & 1 == 1 {
                    filled.push(key)
                } else {
                    emptied.push(key)
                }
            }
        }
        if filled.is_empty() && emptied.is_empty() {
            return;
        }
        self.terrain_voxels = self.terrain_voxels + filled.len() - emptied.len();
        match self.terrain {
            None => {
                // No voxels existed, so nothing was emptied.
                let collider = ColliderBuilder::voxels(Vector::ONE, &filled).build();
                self.terrain = Some(self.physics.insert_collider(collider, None));
            }
            Some(handle) if self.terrain_voxels == 0 => {
                self.physics.remove_collider(handle);
                self.terrain = None;
            }
            Some(handle) => {
                let voxels = self.physics.colliders[handle]
                    .shape_mut()
                    .as_voxels_mut()
                    .expect("the terrain collider is a voxel shape");
                for key in filled {
                    voxels.set_voxel(key, true);
                }
                for key in emptied {
                    voxels.set_voxel(key, false);
                }
            }
        }
    }

    fn wake_ships_near(&mut self, pos: SectionPos) {
        let min = section_origin(pos) - Vector::ONE;
        let near = Aabb::new(min, min + Vector::splat(f64::from(SECTION_SIZE) + 2.0));
        let asleep: Vec<RigidBodyHandle> = self
            .ships
            .values()
            .map(|s| s.body)
            .filter(|&h| {
                let body = &self.physics.bodies[h];
                body.is_sleeping()
                    && body
                        .colliders()
                        .iter()
                        .any(|&c| self.physics.colliders[c].compute_aabb().intersects(&near))
            })
            .collect();
        for handle in asleep {
            self.physics.wake_up(handle, true);
        }
    }

    /// Adds ship `id`, built from `blocks` (see [`ShipBlock`]), moving as `motion` says.
    ///
    /// Each block needs a positive mass and a position of its own. A block's
    /// boxes number 1 to [`MAX_BLOCK_BOXES`], each within −1..=2 of its
    /// block on every axis. At least one block must collide.
    pub fn add_ship(
        &mut self,
        id: u32,
        motion: &ShipMotion,
        blocks: &[ShipBlock],
    ) -> Result<(), PhysicsError> {
        if self.ships.contains_key(&id) {
            return Err(PhysicsError::DuplicateShip(id));
        }
        let pose = motion_pose(motion)?;
        if blocks.is_empty() || blocks.len() > MAX_SHIP_BLOCKS {
            return Err(PhysicsError::Invalid(
                "a ship has 1 to MAX_SHIP_BLOCKS blocks",
            ));
        }
        let mut seen = HashSet::with_capacity(blocks.len());
        let mut full = Vec::new();
        let mut boxes = Vec::new();
        for block in blocks {
            if !(block.mass.is_finite() && block.mass > 0.0) {
                return Err(PhysicsError::Invalid(
                    "a block's mass must be positive and finite",
                ));
            }
            if !seen.insert(block.pos) {
                return Err(PhysicsError::Invalid("two blocks share a position"));
            }
            match &block.shape {
                BlockShape::Full => full.push(IVector::new(
                    i64::from(block.pos[0]),
                    i64::from(block.pos[1]),
                    i64::from(block.pos[2]),
                )),
                BlockShape::Boxes(list) => {
                    if list.is_empty() || list.len() > MAX_BLOCK_BOXES {
                        return Err(PhysicsError::Invalid(
                            "a block has 1 to MAX_BLOCK_BOXES boxes",
                        ));
                    }
                    for b in list {
                        if !b.is_valid_within(-1.0, 2.0) {
                            return Err(PhysicsError::Invalid(
                                "a block box is empty, not finite or out of range",
                            ));
                        }
                        let h = b.half_extents();
                        boxes.push((
                            Pose::from_translation(block.origin() + b.center()),
                            SharedShape::cuboid(h.x, h.y, h.z),
                        ));
                    }
                }
                BlockShape::Empty => {}
            }
        }
        if full.is_empty() && boxes.is_empty() {
            return Err(PhysicsError::Invalid("a ship needs a block that collides"));
        }
        let body = RigidBodyBuilder::dynamic()
            .pose(pose)
            .linvel(vector(motion.linvel))
            .angvel(vector(motion.angvel))
            .additional_mass_properties(blocks::sum_mass_properties(blocks))
            .gravity_scale(STANDARD_GRAVITY)
            .build();
        let handle = self.physics.insert_body(body);
        // The body carries the mass; the colliders only collide.
        if !full.is_empty() {
            let voxels = ColliderBuilder::voxels(Vector::ONE, &full)
                .density(0.0)
                .build();
            self.physics.insert_collider(voxels, Some(handle));
        }
        if !boxes.is_empty() {
            let compound = ColliderBuilder::compound(boxes).density(0.0).build();
            self.physics.insert_collider(compound, Some(handle));
        }
        // Rapier would otherwise sum the mass only at the next step, after applying this step's forces.
        let physics = &mut self.physics;
        physics.bodies[handle].recompute_mass_properties_from_colliders(&physics.colliders);
        self.ships.insert(
            id,
            Ship {
                body: handle,
                forces: Vec::new(),
                torque: [0.0; 3],
            },
        );
        Ok(())
    }

    /// Removes ship `id`.
    pub fn remove_ship(&mut self, id: u32) -> Result<(), PhysicsError> {
        let ship = self
            .ships
            .remove(&id)
            .ok_or(PhysicsError::UnknownShip(id))?;
        self.physics.remove_body(ship.body);
        Ok(())
    }

    fn ship_body(&mut self, id: u32) -> Result<&mut RigidBody, PhysicsError> {
        let ship = self.ships.get(&id).ok_or(PhysicsError::UnknownShip(id))?;
        Ok(&mut self.physics.bodies[ship.body])
    }

    /// Moves ship `id` to `motion` at once (a teleport), and wakes it.
    pub fn set_ship_motion(&mut self, id: u32, motion: &ShipMotion) -> Result<(), PhysicsError> {
        let pose = motion_pose(motion)?;
        let body = self.ship_body(id)?;
        body.set_position(pose, true);
        body.set_linvel(vector(motion.linvel), true);
        body.set_angvel(vector(motion.angvel), true);
        Ok(())
    }

    /// Ship `id`'s gravity, in m/s² along −Y (the surface frame's down).
    pub fn set_ship_gravity(&mut self, id: u32, gravity: f64) -> Result<(), PhysicsError> {
        if !gravity.is_finite() {
            return Err(PhysicsError::Invalid("gravity must be finite"));
        }
        self.ship_body(id)?.set_gravity_scale(gravity, true);
        Ok(())
    }

    /// The forces and torque (N·m, in the ship's frame) that act on ship `id`
    /// at every step until they are set again. Empty forces and a zero
    /// torque leave it coasting.
    pub fn set_ship_controls(
        &mut self,
        id: u32,
        forces: &[ShipForce],
        torque: [f64; 3],
    ) -> Result<(), PhysicsError> {
        if forces.len() > MAX_SHIP_FORCES {
            return Err(PhysicsError::Invalid("too many forces on one ship"));
        }
        if !(forces.iter().all(|f| finite(&f.force) && finite(&f.point)) && finite(&torque)) {
            return Err(PhysicsError::Invalid("forces and torque must be finite"));
        }
        let ship = self
            .ships
            .get_mut(&id)
            .ok_or(PhysicsError::UnknownShip(id))?;
        ship.forces = forces.to_vec();
        ship.torque = torque;
        if !ship.is_driven() {
            let body = &mut self.physics.bodies[ship.body];
            body.reset_forces(false);
            body.reset_torques(false);
        }
        Ok(())
    }

    /// Advances `steps` steps of [`STEP_SECONDS`] each.
    pub fn step(&mut self, steps: u32) -> Result<(), PhysicsError> {
        if steps > MAX_STEPS {
            return Err(PhysicsError::Invalid("too many steps in one call"));
        }
        for _ in 0..steps {
            for ship in self.ships.values().filter(|s| s.is_driven()) {
                // The forces turn with the ship, so they are put in world terms every step.
                let body = &mut self.physics.bodies[ship.body];
                let pose = *body.position();
                body.reset_forces(false);
                body.reset_torques(false);
                for f in &ship.forces {
                    body.add_force_at_point(
                        pose.rotation * vector(f.force),
                        pose.transform_point(vector(f.point)),
                        true,
                    );
                }
                body.add_torque(pose.rotation * vector(ship.torque), true);
            }
            self.physics.step();
        }
        Ok(())
    }

    /// The number of ships.
    pub fn ship_count(&self) -> usize {
        self.ships.len()
    }

    /// Ship `id`'s state, if it exists.
    pub fn ship(&self, id: u32) -> Option<ShipState> {
        self.ships.get(&id).map(|s| self.state(id, s))
    }

    /// Every ship's state, by id.
    pub fn ships(&self) -> Vec<ShipState> {
        self.ships
            .iter()
            .map(|(&id, s)| self.state(id, s))
            .collect()
    }

    fn state(&self, id: u32, ship: &Ship) -> ShipState {
        let body = &self.physics.bodies[ship.body];
        let pose = body.position();
        let q = pose.rotation;
        ShipState {
            id,
            motion: ShipMotion {
                position: array(pose.translation),
                rotation: [q.x, q.y, q.z, q.w],
                linvel: array(body.linvel()),
                angvel: array(body.angvel()),
            },
            sleeping: body.is_sleeping(),
        }
    }

    /// Ship `id`'s mass properties in its own frame, if it exists.
    pub fn ship_mass(&self, id: u32) -> Option<ShipMass> {
        let ship = self.ships.get(&id)?;
        Some(blocks::from_rapier(
            &self.physics.bodies[ship.body]
                .mass_properties()
                .local_mprops,
        ))
    }
}
