//! Blocks, their collision boxes, and the mass properties they add up to.

use rapier3d_f64::prelude::{MassProperties, Pose, Vector};

/// Most blocks one ship may have (ROADMAP risk 4: a v1 cap).
pub const MAX_SHIP_BLOCKS: usize = 32_768;
/// Most collision boxes one block may have.
pub const MAX_BLOCK_BOXES: usize = 16;

/// An axis-aligned box from `min` to `max`, in some local frame: a block's
/// (0..1 on each axis, a little beyond for fences and walls) or a terrain
/// section's (0..16).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Cuboid {
    pub min: [f64; 3],
    pub max: [f64; 3],
}

impl Cuboid {
    pub fn new(min: [f64; 3], max: [f64; 3]) -> Self {
        Self { min, max }
    }

    /// True if every coordinate is finite, `min < max` on every axis, and
    /// the box lies within `lo..=hi` on every axis.
    pub(crate) fn is_valid_within(&self, lo: f64, hi: f64) -> bool {
        (0..3).all(|i| {
            let (a, b) = (self.min[i], self.max[i]);
            a.is_finite() && b.is_finite() && a < b && a >= lo && b <= hi
        })
    }

    pub(crate) fn half_extents(&self) -> Vector {
        Vector::new(
            (self.max[0] - self.min[0]) / 2.0,
            (self.max[1] - self.min[1]) / 2.0,
            (self.max[2] - self.min[2]) / 2.0,
        )
    }

    pub(crate) fn center(&self) -> Vector {
        Vector::new(
            (self.min[0] + self.max[0]) / 2.0,
            (self.min[1] + self.max[1]) / 2.0,
            (self.min[2] + self.max[2]) / 2.0,
        )
    }

    fn volume(&self) -> f64 {
        let h = self.half_extents();
        8.0 * h.x * h.y * h.z
    }
}

/// How a block collides.
#[derive(Clone, Debug, PartialEq)]
pub enum BlockShape {
    /// A full cube.
    Full,
    /// Boxes in the block's frame, such as a slab's one or a stair's two.
    Boxes(Vec<Cuboid>),
    /// Nothing to collide with (a torch, a flower). Its mass still counts.
    Empty,
}

/// One block of a ship: where it is on the ship's grid, its mass in
/// kilograms, and how it collides.
#[derive(Clone, Debug, PartialEq)]
pub struct ShipBlock {
    pub pos: [i32; 3],
    pub mass: f64,
    pub shape: BlockShape,
}

impl ShipBlock {
    pub fn full(pos: [i32; 3], mass: f64) -> Self {
        Self {
            pos,
            mass,
            shape: BlockShape::Full,
        }
    }

    pub(crate) fn origin(&self) -> Vector {
        Vector::new(
            f64::from(self.pos[0]),
            f64::from(self.pos[1]),
            f64::from(self.pos[2]),
        )
    }

    /// The block's mass spread evenly over its boxes (over the whole cube if
    /// it is full or doesn't collide), in the ship's frame.
    fn mass_properties(&self) -> MassProperties {
        let unit = [Cuboid::new([0.0; 3], [1.0; 3])];
        let boxes: &[Cuboid] = match &self.shape {
            BlockShape::Boxes(boxes) => boxes,
            BlockShape::Full | BlockShape::Empty => &unit,
        };
        let volume: f64 = boxes.iter().map(Cuboid::volume).sum();
        let origin = self.origin();
        boxes
            .iter()
            .map(|b| {
                let density = self.mass / volume;
                MassProperties::from_cuboid(density, b.half_extents())
                    .transform_by(&Pose::from_translation(origin + b.center()))
            })
            .sum()
    }
}

/// A ship's mass properties in its own frame.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ShipMass {
    /// Kilograms.
    pub mass: f64,
    /// The centre of mass in the ship's frame.
    pub center: [f64; 3],
    /// The inertia tensor about the centre of mass, in the ship's frame
    /// (kg·m²), row by row.
    pub inertia: [[f64; 3]; 3],
}

/// The mass properties of `blocks` (see [`ShipBlock`]).
pub fn mass_of(blocks: &[ShipBlock]) -> ShipMass {
    from_rapier(&sum_mass_properties(blocks))
}

pub(crate) fn sum_mass_properties(blocks: &[ShipBlock]) -> MassProperties {
    blocks.iter().map(ShipBlock::mass_properties).sum()
}

pub(crate) fn from_rapier(m: &MassProperties) -> ShipMass {
    let c = m.local_com;
    let t = m.reconstruct_inertia_matrix();
    ShipMass {
        mass: m.mass(),
        center: [c.x, c.y, c.z],
        inertia: [
            [t.x_axis.x, t.y_axis.x, t.z_axis.x],
            [t.x_axis.y, t.y_axis.y, t.z_axis.y],
            [t.x_axis.z, t.y_axis.z, t.z_axis.z],
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f64, b: f64) -> bool {
        (a - b).abs() < 1e-9 * b.abs().max(1.0)
    }

    #[test]
    fn one_block_is_a_uniform_cube() {
        let m = mass_of(&[ShipBlock::full([0, 0, 0], 600.0)]);
        assert!(close(m.mass, 600.0));
        assert_eq!(m.center, [0.5, 0.5, 0.5]);
        for i in 0..3 {
            for j in 0..3 {
                let expected = if i == j { 100.0 } else { 0.0 }; // m (1² + 1²) / 12
                assert!(close(m.inertia[i][j], expected), "{i} {j}: {:?}", m.inertia);
            }
        }
    }

    #[test]
    fn a_rod_of_three_blocks_follows_the_parallel_axis_theorem() {
        let blocks: Vec<_> = (0..3).map(|x| ShipBlock::full([x, 0, 0], 1000.0)).collect();
        let m = mass_of(&blocks);
        assert!(close(m.mass, 3000.0));
        assert_eq!(m.center, [1.5, 0.5, 0.5]);
        // About x: each block m/6. About y and z: m/6 + m d², d = 1, 0, 1.
        assert!(close(m.inertia[0][0], 500.0));
        assert!(close(m.inertia[1][1], 2500.0));
        assert!(close(m.inertia[2][2], 2500.0));
    }

    #[test]
    fn an_l_shape_has_products_of_inertia() {
        // Blocks at (0,0,0), (1,0,0) and (0,1,0), 1 kg each: the centre of mass is
        // (5/6, 5/6, 1/2), and I_xy = -sum m (x - cx)(y - cy) = -(-1/3·-1/3 + 2/3·-1/3 + -1/3·2/3) = 1/3.
        let blocks = [
            ShipBlock::full([0, 0, 0], 1.0),
            ShipBlock::full([1, 0, 0], 1.0),
            ShipBlock::full([0, 1, 0], 1.0),
        ];
        let m = mass_of(&blocks);
        for (got, want) in m.center.iter().zip([5.0 / 6.0, 5.0 / 6.0, 0.5]) {
            assert!(close(*got, want), "{:?}", m.center);
        }
        assert!(close(m.inertia[0][1], 1.0 / 3.0), "{:?}", m.inertia);
        assert!(close(m.inertia[1][0], 1.0 / 3.0), "{:?}", m.inertia);
        assert!(m.inertia[0][2].abs() < 1e-12 && m.inertia[1][2].abs() < 1e-12);
    }

    #[test]
    fn a_slab_puts_its_mass_in_its_box() {
        let slab = ShipBlock {
            pos: [2, 0, 0],
            mass: 1250.0,
            shape: BlockShape::Boxes(vec![Cuboid::new([0.0; 3], [1.0, 0.5, 1.0])]),
        };
        let m = mass_of(&[slab]);
        assert!(close(m.mass, 1250.0));
        assert_eq!(m.center, [2.5, 0.25, 0.5]);
    }

    #[test]
    fn boxes_share_a_block_s_mass_by_volume() {
        // A stair: a bottom slab (volume 1/2) and a back quarter (volume 1/4).
        let stair = ShipBlock {
            pos: [0, 0, 0],
            mass: 3.0,
            shape: BlockShape::Boxes(vec![
                Cuboid::new([0.0; 3], [1.0, 0.5, 1.0]),
                Cuboid::new([0.0, 0.5, 0.5], [1.0, 1.0, 1.0]),
            ]),
        };
        let m = mass_of(&[stair]);
        assert!(close(m.mass, 3.0));
        // 2 kg at y = 0.25, 1 kg at y = 0.75; 2 kg at z = 0.5, 1 kg at z = 0.75.
        assert!(close(m.center[1], (2.0 * 0.25 + 0.75) / 3.0));
        assert!(close(m.center[2], (2.0 * 0.5 + 0.75) / 3.0));
    }

    #[test]
    fn a_block_without_collision_still_weighs() {
        let torch = ShipBlock {
            pos: [0, 0, 0],
            mass: 1.0,
            shape: BlockShape::Empty,
        };
        let m = mass_of(&[ShipBlock::full([1, 0, 0], 1.0), torch]);
        assert!(close(m.mass, 2.0));
        assert_eq!(m.center, [1.0, 0.5, 0.5]);
    }

    #[test]
    fn cuboid_validation() {
        let ok = Cuboid::new([0.0, 0.0, 0.0], [1.0, 1.5, 1.0]);
        assert!(ok.is_valid_within(-1.0, 2.0));
        assert!(!ok.is_valid_within(0.0, 1.0));
        assert!(!Cuboid::new([0.0; 3], [0.0, 1.0, 1.0]).is_valid_within(0.0, 1.0));
        assert!(!Cuboid::new([0.0; 3], [f64::NAN, 1.0, 1.0]).is_valid_within(0.0, 1.0));
        assert!(!Cuboid::new([1.0, 0.0, 0.0], [0.5, 1.0, 1.0]).is_valid_within(0.0, 1.0));
    }
}
